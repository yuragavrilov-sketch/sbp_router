# SBP Router — Management/Monitoring API (SP1) — Design Spec

## Контекст

Часть более крупной работы: вокруг готового SBP-роутера (`back/`) строится экосистема для
мониторинга и управления — React-фронт (`front/`), BFF (`front-bff/`, Spring Boot) и общие
OpenAPI-контракты (`contract/`).

Решения по экосистеме (зафиксированы при брейншторме):

- **Объём управления:** мониторинг + изменение рантайм-конфигурации (без истории/аудита, без БД).
- **Стек BFF:** Spring Boot (Java).
- **Контракты:** OpenAPI (contract-first), генерация Java-моделей для back/bff и TS-типов для front.
- **Порядок работ:** последовательно, `back` первым. **Этот документ покрывает только SP1 — `back`.**
  SP2 (`front-bff`) и SP3 (`front`) получат свои спеки отдельно.

Бэкенд сегодня: реактивный XML-прокси (Spring WebFlux), **без БД**, вся конфигурация
`sbp-router` живёт в `application.yml` и захватывается потребителями в конструкторе. Наружу:
`POST /api/gcsvc` (боевой трафик), `/actuator/health`, `/actuator/prometheus`, JSON-логи.
Management-API нет.

## Цель SP1

Добавить к роутеру **управляющую плоскость**, не затрагивая боевой путь `POST /api/gcsvc`:

1. Рантайм-изменение всей конфигурации `sbp-router` (флаг, терминалы, upstreams, extraction-rules)
   без рестарта, с сохранением правок поверх yml (write-through в файл).
2. Мониторинг: лента последних запросов + краткий статус (агрегаты остаются в Prometheus).
3. OpenAPI-контракт admin-API в `contract/` как единый источник правды для back и (позже) bff.

### Не входит в SP1 (YAGNI)

- История/аудит изменений, версионирование конфигурации с откатом, роли/RBAC — исключено явно.
- Авторизация пользователей и агрегация метрик из Prometheus — это BFF (SP2).
- Поиск/фильтрация по запросам (мини-APM) — отвергнуто в пользу простой ленты.

## Архитектура

```
   Платформа ──► POST /api/gcsvc ──► [GcsvcHandler ─ XmlFieldExtractor ─ RoutingDecisionEngine ─ ProxyClient]
                                              │  читают конфиг по факту                          │ пишет запись
                                              ▼                                                  ▼
                                        ConfigStore                                      RequestHistoryStore
                                   (AtomicReference<RouterConfigSnapshot>)               (in-memory ring buffer)
                                         ▲     │ write-through                                   │
   BFF ──► /api/admin/* ──────────────────┘     ▼                                                │
   (bearer token)                       runtime-overrides.json (config-volume)                   │
            └──────────── GET /api/admin/requests ◄───────────────────────────────────────────────┘
```

Два контура строго разделены:

- **Боевой контур** — `POST /api/gcsvc`. Не изменяется по поведению; единственная правка —
  потребители конфига читают `ConfigStore.current()` вместо кэша, а `GcsvcHandler` пишет запись
  в `RequestHistoryStore`.
- **Управляющий контур** — `/api/admin/*`. Внутренний, под bearer-токеном; на боевой путь не влияет.

### Граница безопасности

- `POST /api/gcsvc` остаётся как есть (свой контур, обращается Платформа).
- `/api/admin/*` — внутренний: защищён статическим bearer-токеном (`sbp-router.admin.token`),
  рассчитан на сетевую изоляцию. Платформа до admin-плоскости не дотягивается.
- Настоящая авторизация пользователей и роли — на уровне BFF (SP2), не здесь.
- Отсутствие/неверный токен → `401`.

## Компоненты

### Новые

| Компонент | Пакет | Назначение |
|---|---|---|
| `RouterConfigSnapshot` | `config` | Иммутабельный record — полный слепок управляемого конфига |
| `ConfigStore` | `management` | `AtomicReference<RouterConfigSnapshot>`; `current()` + атомарный `update()` |
| `ConfigOverrideRepository` | `management` | Сериализация снапшота в override-файл и загрузка при старте |
| `ConfigValidator` | `management` | Валидация изменений до применения (упор на extraction-rules) |
| `RequestHistoryStore` | `history` | Потокобезопасный кольцевой буфер последних N записей |
| `RequestRecord` | `history` | Запись об одном обработанном запросе |
| `AdminConfigController` | `management` | REST `/api/admin/config*` |
| `AdminMonitoringController` | `management` | REST `/api/admin/requests`, `/api/admin/status` |
| `AdminTokenFilter` (или security-конфиг) | `management` | Проверка bearer-токена на `/api/admin/*` |

### Изменяемые (рефакторинг чтения конфига)

| Компонент | Было | Стало |
|---|---|---|
| `XmlFieldExtractor` | кэширует `properties.getExtractionRules()` в конструкторе | читает `configStore.current().extractionRules()` по факту |
| `RoutingDecisionEngine` | кэширует `Routing` в конструкторе | читает `configStore.current().routing()` по факту |
| `ProxyClient` | кэширует `getUpstreams()` в конструкторе | читает `configStore.current().upstreams()` по факту |
| `GcsvcHandler` | логирует запрос | дополнительно пишет `RequestRecord` в `RequestHistoryStore` |

`SbpRouterProperties` остаётся как механизм чтения yml (baseline), но потребители больше не
инжектят его напрямую — они работают через `ConfigStore`.

## ConfigStore и снапшот

`RouterConfigSnapshot` — Java record, полный слепок управляемого конфига:

- `routing` (флаг `tkbPayEnabled`)
- `terminals` (c2b/b2c field-name, b2c prefix, c2b `tkbPayList`)
- `upstreams` (`Map<String, UpstreamConfig>`)
- `extractionRules` (`Map<String, ExtractionRuleSet>`)
- `version` (монотонный `long`)
- `updatedAt` (timestamp)

Все вложенные коллекции — неизменяемые копии (defensive copy при сборке).

**Чтение (боевой путь):** `configStore.current()` — один `AtomicReference.get()`, без блокировок.
Горячий путь не замедляется; замена атомарна — частичных состояний в середине обработки запроса
не возникает.

## Применение и персистентность

### Старт сервиса (слияние baseline + override)

1. Spring поднимает `SbpRouterProperties` из `application.yml` — это baseline («заводские»).
2. `ConfigOverrideRepository` читает `runtime-overrides.json` с config-volume, если файл есть.
3. baseline + override → начальный `RouterConfigSnapshot` → `ConfigStore`.

Удаление override-файла возвращает сервис к yml-настройкам при следующем старте.

### Обновление (через admin-API)

Строгий порядок, чтобы файл и память не разъехались:

1. Собрать новый снапшот = текущий снапшот + патч одного домена.
2. **Провалидировать** (`ConfigValidator`) → при ошибке `400`, состояние не меняется.
3. **Записать override-файл атомарно** (temp-файл + rename) → при I/O-ошибке `500`, память не трогаем.
4. Только после успешной записи — `atomicRef.set(newSnapshot)` с `version + 1`.

Файл — источник истины для перезапуска; «файл → память» исключает дрейф.

### Расположение override-файла

Файл лежит на уже смонтированном config-volume (в `docker-compose.yml` он есть). Путь
конфигурируем: `sbp-router.config.override-path` (по умолчанию рядом с `application.yml`).
Формат — JSON.

## Валидация (`ConfigValidator`)

| Домен | Правила |
|---|---|
| `routing` | boolean — тривиально |
| `terminals` | имена полей не пустые; элементы списков не пустые/не дублируются |
| `upstreams` | корректный URL; `timeout > 0`; `retry.maxAttempts >= 0`, `backoff >= 0`; присутствуют обязательные ключи, на которые ссылается `RoutingDecisionEngine`: `infosrv`, `stub-verification`, `stub-connector`, `stub-c2bqrd-verification` |
| `extraction-rules` | у каждого `FieldRule` задано ровно одно из `parent+key` либо `path` (не пусто и не оба); имена полей уникальны в наборе; ключи request-type из известного множества (`ReqAuthPay`, `ReqNoticePay`) |

extraction-rules — самый рисковый домен: ошибка ломает маршрутизацию, поэтому валидируется жёстко.

## API-поверхность

База: `/api/admin`. Все ответы/тела описаны в OpenAPI-контракте (см. ниже).

### Конфигурация

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/api/admin/config` | Весь снапшот (4 домена + `version` + `updatedAt`) |
| `PUT` | `/api/admin/config/routing` | Фиче-флаг |
| `PUT` | `/api/admin/config/terminals` | Списки терминалов / prefix / field-name |
| `PUT` | `/api/admin/config/upstreams` | Upstream'ы |
| `PUT` | `/api/admin/config/extraction-rules` | Правила извлечения |

Гранулярность по доменам — удобнее для UI и снижает конфликты параллельных правок.

### Оптимистичная блокировка

`GET` отдаёт `version`. Каждый `PUT` обязан прислать `expectedVersion`. Если он устарел →
`409 Conflict` (кто-то уже изменил конфиг). Дёшево, снимает класс «слепых» затираний правок
двумя операторами/вкладками. Истории/аудита при этом не вводим.

### Мониторинг

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/api/admin/requests?limit=N` | Последние N записей, свежие первыми |
| `GET` | `/api/admin/status` | up/uptime, текущий флаг `tkbPayEnabled`, `version` конфига, размер/ёмкость буфера |

Агрегатные метрики (счётчики, длительности, ошибки) НЕ дублируем в admin-API — их тянет BFF из
Prometheus (`/actuator/prometheus`) в SP2.

## Лента запросов (`RequestHistoryStore`)

- Ограниченный кольцевой буфер на N записей; ёмкость конфигурируема
  (`sbp-router.history.capacity`, по умолчанию 1000).
- Потокобезопасный (WebFlux — конкурентная запись).
- Память ограничена сверху; старые записи вытесняются.
- Наполняется из `GcsvcHandler` в той же точке, где уже идёт логирование запроса.

`RequestRecord`: `timestamp`, `correlationId`, `requestType`, `terminal`, `terminalOwner`,
`sbpOperType`, `routeDecision`, `upstream`, `upstreamStatusCode`, `durationMs`, `error?`.

## Контракт (`contract/`)

**Contract-first.** Руками пишем `contract/back-mgmt-api.openapi.yaml` как единственный источник
правды для admin-API. Из него генерим:

- для `back` — серверные интерфейсы + DTO (openapi-generator, Maven-плагин);
- для `front-bff` (SP2) — Java-клиент + модели из того же файла.

Ручная yaml-спека остаётся ревьюибельной; back и bff гарантированно говорят на одном языке.

## Обработка ошибок

Единый формат тела ошибки: `{ "code": ..., "message": ..., "field": ... }` (`field` опционален).

| Код | Ситуация |
|---|---|
| `400` | Провал валидации; в теле — какое поле и почему (особенно extraction-rules) |
| `401` | Нет/неверный admin-токен |
| `409` | Устаревший `expectedVersion` |
| `500` | Сбой записи override-файла; **конфиг в памяти не меняется** (порядок «файл → память») |

Боевой `/api/gcsvc` от admin-операций не страдает: чтение `current()` без блокировок; ошибки
admin-плоскости в боевой путь не протекают.

## Наблюдаемость

- Каждое изменение конфига логируется: домен, `version` (старая → новая), источник. «Кто» —
  только если BFF передаст идентичность; в SP1 фиксируем факт и источник.
- Метрики (Micrometer): gauge текущего состояния флага `tkbPayEnabled`, counter перезагрузок
  конфига, gauge заполненности буфера истории. Существующие метрики роутера не трогаем.

## Стратегия тестирования

- **Unit:** сборка/слияние снапшота (yml + override); все правила `ConfigValidator` (упор на
  extraction-rules); round-trip `ConfigOverrideRepository` (запись → чтение); поведение
  `RequestHistoryStore` (ёмкость, порядок, конкурентная запись).
- **Integration:** admin-эндпоинты (GET/PUT, `400` на валидации, `409` на конфликте версий,
  `401` без токена); сквозной сценарий «`PUT` меняет флаг → следующий `/api/gcsvc` маршрутизируется
  по-новому» (WireMock вместо upstream); «рестарт» — снапшот восстанавливается из override-файла.
- **Регрессия:** существующие тесты extraction/routing/proxy зелёные после рефакторинга на `ConfigStore`.

## Точки расширения (для SP2/SP3)

- `back-mgmt-api.openapi.yaml` — готовый контракт, который BFF переиспользует для генерации клиента.
- Поле «кто изменил» в логах изменений — задел под идентичность, приходящую от BFF.
- При необходимости истории/аудита (отвергнуто в SP1) — точка ввода персистентного хранилища
  поверх `ConfigStore.update()`.
