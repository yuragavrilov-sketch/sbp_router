# SBP Router — Management/Monitoring API (SP1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать SBP-роутеру управляющую плоскость: рантайм-изменение всей конфигурации без рестарта (write-through в override-файл) и мониторинг (лента последних запросов + статус), не затрагивая боевой путь `POST /api/gcsvc`.

**Architecture:** Иммутабельный `RouterConfigSnapshot` хранится в `ConfigStore` (`AtomicReference`); четыре потребителя (`XmlFieldExtractor`, `TerminalDetector`, `RoutingDecisionEngine`, `ProxyClient`) читают `configStore.current()` на каждый запрос вместо кэша в конструкторе. Admin REST под `/api/admin/*` (annotated `@RestController`) с bearer-токеном и оптимистичной блокировкой по `version`; изменение идёт строго «валидация → запись файла → атомарная подмена в памяти». Лента запросов — потокобезопасный кольцевой буфер, наполняемый из `GcsvcHandler`.

**Tech Stack:** Java 17, Spring Boot 3.4.3 (WebFlux), Jackson (через Spring `ObjectMapper`), JUnit 5 + AssertJ + WebTestClient + Reactor Test + WireMock. Сборка — Maven (`mvn`, запускать из каталога `back/`).

---

## Соглашения

- Все команды запускать из `/mnt/c/work/sbp_router/back`.
- Прогон всех тестов: `mvn -q test`. Один класс: `mvn -q -Dtest=ClassName test`. Один метод: `mvn -q -Dtest=ClassName#method test`.
- При TDD «красный» для нового класса = ошибка компиляции теста (symbol not found) — это ожидаемо.
- Коммитим часто; сообщения — conventional commits; каждое сообщение коммита заканчивать трейлером `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` (требование CLAUDE.md). В шагах ниже трейлер для краткости опущен — добавляйте его.
- Новые пакеты: `ru.tkbbank.sbprouter.management`, `ru.tkbbank.sbprouter.management.dto`, `ru.tkbbank.sbprouter.history`. `RouterConfigSnapshot` кладём в существующий `config` (рядом с `SbpRouterProperties`, чтобы переиспользовать его вложенные типы без циклов).

## Решения, отступающие от спеки (подтверждены при планировании)

1. **DTO пишем руками в `back`**, а `contract/back-mgmt-api.openapi.yaml` ведём как ревьюибельный источник правды. Кодогенерацию из контракта подключаем в SP2 (BFF-консьюмер), где она снижает риск; в боевой сервис кросс-репо-кодоген не тащим.
2. **Оптимистичная блокировка** передаётся query-параметром `?expectedVersion=N` на каждый `PUT` (просто потреблять из React/BFF), а не через `If-Match`.
3. **Обязательные upstream'ы при валидации:** `infosrv`, `stub-verification`, `stub-connector` (то, что реально есть в `application.yml`). `stub-c2bqrd-verification` валидируется, только если присутствует — текущий yml его не определяет, и закрытие этого пробела вне scope SP1.
4. **Override-файл хранит полный снапшот** и, будучи записанным, полностью замещает yml до своего удаления (а не сливается по полям). Удалил файл → при старте вернулись к yml.

## Карта файлов

**Создаём:**
- `src/main/java/ru/tkbbank/sbprouter/config/RouterConfigSnapshot.java` — иммутабельный снапшот + Builder + `fromProperties`.
- `src/main/java/ru/tkbbank/sbprouter/management/ConfigStore.java` — `AtomicReference<RouterConfigSnapshot>`: `current()`, `replace()`.
- `src/main/java/ru/tkbbank/sbprouter/management/ConfigOverrideRepository.java` — JSON load/save override-файла (атомарная запись).
- `src/main/java/ru/tkbbank/sbprouter/management/ConfigValidator.java` — валидация снапшота.
- `src/main/java/ru/tkbbank/sbprouter/management/ConfigService.java` — оркестрация патч→версия→валидация→файл→память.
- `src/main/java/ru/tkbbank/sbprouter/management/ManagementConfig.java` — `@Bean ConfigStore` (baseline + override на старте).
- `src/main/java/ru/tkbbank/sbprouter/management/ConfigValidationException.java`, `VersionConflictException.java`.
- `src/main/java/ru/tkbbank/sbprouter/management/ConfigDtoMapper.java` — мэппинг DTO ↔ внутренние типы.
- `src/main/java/ru/tkbbank/sbprouter/management/AdminConfigController.java`, `AdminMonitoringController.java`.
- `src/main/java/ru/tkbbank/sbprouter/management/AdminTokenFilter.java` — `WebFilter` для `/api/admin/**`.
- `src/main/java/ru/tkbbank/sbprouter/management/AdminExceptionHandler.java` — `@RestControllerAdvice`.
- `src/main/java/ru/tkbbank/sbprouter/management/dto/*.java` — DTO-records.
- `src/main/java/ru/tkbbank/sbprouter/history/RequestRecord.java`, `RequestHistoryStore.java`.
- `contract/back-mgmt-api.openapi.yaml` (в каталоге `contract/`, отдельный от back).
- Тест-классы под каждый компонент в `src/test/java/...`.

**Изменяем:**
- `src/main/java/ru/tkbbank/sbprouter/config/SbpRouterProperties.java` — добавить `admin`, `history`, `config` (override-path).
- `config/application.yml` — значения для новых свойств.
- `extraction/XmlFieldExtractor.java`, `routing/TerminalDetector.java`, `routing/RoutingDecisionEngine.java`, `proxy/ProxyClient.java` — читать из `ConfigStore`.
- `proxy/GcsvcHandler.java` — писать `RequestRecord` в `RequestHistoryStore`.

---

## Task 1: Новые свойства конфигурации (admin-токен, ёмкость истории, путь override-файла)

**Files:**
- Modify: `src/main/java/ru/tkbbank/sbprouter/config/SbpRouterProperties.java`
- Modify: `config/application.yml`
- Test: `src/test/java/ru/tkbbank/sbprouter/config/SbpRouterPropertiesTest.java` (существует — добавить метод)

- [ ] **Step 1: Добавить вложенные классы свойств в `SbpRouterProperties`**

В `SbpRouterProperties` добавить три поля и геттеры/сеттеры (рядом с существующими `terminals`/`routing`):

```java
    private Admin admin = new Admin();
    private History history = new History();
    private ConfigFile config = new ConfigFile();

    public Admin getAdmin() { return admin; }
    public void setAdmin(Admin admin) { this.admin = admin; }
    public History getHistory() { return history; }
    public void setHistory(History history) { this.history = history; }
    public ConfigFile getConfig() { return config; }
    public void setConfig(ConfigFile config) { this.config = config; }
```

И добавить вложенные статические классы (внутри `SbpRouterProperties`):

```java
    public static class Admin {
        private String token = "";
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class History {
        private int capacity = 1000;
        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
    }

    public static class ConfigFile {
        private String overridePath = "config/runtime-overrides.json";
        public String getOverridePath() { return overridePath; }
        public void setOverridePath(String overridePath) { this.overridePath = overridePath; }
    }
```

- [ ] **Step 2: Прописать значения в `application.yml`**

В `config/application.yml` в блок `sbp-router:` добавить (после `routing:`):

```yaml
  admin:
    token: ${SBP_ADMIN_TOKEN:change-me-in-prod}
  history:
    capacity: 1000
  config:
    override-path: config/runtime-overrides.json
```

- [ ] **Step 3: Написать падающий тест на биндинг свойств**

Добавить метод в `SbpRouterPropertiesTest`:

```java
    @org.junit.jupiter.api.Test
    void bindsAdminHistoryAndConfigDefaults() {
        var props = new SbpRouterProperties();
        assertThat(props.getAdmin().getToken()).isEqualTo("");
        assertThat(props.getHistory().getCapacity()).isEqualTo(1000);
        assertThat(props.getConfig().getOverridePath()).isEqualTo("config/runtime-overrides.json");
    }
```

(используется `import static org.assertj.core.api.Assertions.assertThat;` — добавить, если в файле его ещё нет.)

- [ ] **Step 4: Прогнать тест**

Run: `mvn -q -Dtest=SbpRouterPropertiesTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/config/SbpRouterProperties.java config/application.yml src/test/java/ru/tkbbank/sbprouter/config/SbpRouterPropertiesTest.java
git commit -m "feat(config): add admin token, history capacity and override-path properties"
```

---

## Task 2: `RouterConfigSnapshot` — иммутабельный снапшот + Builder

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/config/RouterConfigSnapshot.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/config/RouterConfigSnapshotTest.java`

- [ ] **Step 1: Написать падающий тест**

```java
package ru.tkbbank.sbprouter.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RouterConfigSnapshotTest {

    @Test
    void builderFillsSaneDefaults() {
        var snap = RouterConfigSnapshot.builder().build();
        assertThat(snap.routing()).isNotNull();
        assertThat(snap.routing().isTkbPayEnabled()).isFalse();
        assertThat(snap.terminals()).isNotNull();
        assertThat(snap.upstreams()).isEmpty();
        assertThat(snap.extractionRules()).isEmpty();
        assertThat(snap.version()).isZero();
        assertThat(snap.updatedAt()).isNotNull();
    }

    @Test
    void fromPropertiesCopiesManagedDomains() {
        var props = new SbpRouterProperties();
        var routing = new SbpRouterProperties.Routing();
        routing.setTkbPayEnabled(true);
        props.setRouting(routing);
        props.setUpstreams(Map.of("infosrv", new SbpRouterProperties.UpstreamConfig()));

        var snap = RouterConfigSnapshot.fromProperties(props);
        assertThat(snap.routing().isTkbPayEnabled()).isTrue();
        assertThat(snap.upstreams()).containsKey("infosrv");
        assertThat(snap.version()).isZero();
    }

    @Test
    void builderCopyKeepsUnchangedDomainsAndBumpsVersion() {
        var base = RouterConfigSnapshot.builder().version(5).build();
        var routing = new SbpRouterProperties.Routing();
        routing.setTkbPayEnabled(true);
        var next = RouterConfigSnapshot.builder(base).routing(routing).version(6).build();
        assertThat(next.version()).isEqualTo(6);
        assertThat(next.routing().isTkbPayEnabled()).isTrue();
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что не компилируется/падает**

Run: `mvn -q -Dtest=RouterConfigSnapshotTest test`
Expected: FAIL (RouterConfigSnapshot не существует).

- [ ] **Step 3: Реализовать `RouterConfigSnapshot`**

```java
package ru.tkbbank.sbprouter.config;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable slice of the managed sbp-router configuration. Domain objects
 * ({@link SbpRouterProperties.Routing} etc.) are treated as effectively
 * immutable: ConfigService always supplies freshly-built instances.
 */
public record RouterConfigSnapshot(
        SbpRouterProperties.Routing routing,
        SbpRouterProperties.Terminals terminals,
        Map<String, SbpRouterProperties.UpstreamConfig> upstreams,
        Map<String, SbpRouterProperties.ExtractionRuleSet> extractionRules,
        long version,
        Instant updatedAt
) {
    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(RouterConfigSnapshot base) {
        return new Builder()
                .routing(base.routing())
                .terminals(base.terminals())
                .upstreams(base.upstreams())
                .extractionRules(base.extractionRules())
                .version(base.version());
    }

    public static RouterConfigSnapshot fromProperties(SbpRouterProperties props) {
        return builder()
                .routing(props.getRouting() != null ? props.getRouting() : new SbpRouterProperties.Routing())
                .terminals(props.getTerminals() != null ? props.getTerminals() : new SbpRouterProperties.Terminals())
                .upstreams(props.getUpstreams() != null ? props.getUpstreams() : Map.of())
                .extractionRules(props.getExtractionRules() != null ? props.getExtractionRules() : Map.of())
                .version(0)
                .build();
    }

    public static final class Builder {
        private SbpRouterProperties.Routing routing = new SbpRouterProperties.Routing();
        private SbpRouterProperties.Terminals terminals = new SbpRouterProperties.Terminals();
        private Map<String, SbpRouterProperties.UpstreamConfig> upstreams = Map.of();
        private Map<String, SbpRouterProperties.ExtractionRuleSet> extractionRules = Map.of();
        private long version = 0;

        public Builder routing(SbpRouterProperties.Routing v) { this.routing = v; return this; }
        public Builder terminals(SbpRouterProperties.Terminals v) { this.terminals = v; return this; }
        public Builder upstreams(Map<String, SbpRouterProperties.UpstreamConfig> v) {
            this.upstreams = v != null ? Map.copyOf(v) : Map.of(); return this;
        }
        public Builder extractionRules(Map<String, SbpRouterProperties.ExtractionRuleSet> v) {
            this.extractionRules = v != null ? Map.copyOf(v) : Map.of(); return this;
        }
        public Builder version(long v) { this.version = v; return this; }

        public RouterConfigSnapshot build() {
            return new RouterConfigSnapshot(routing, terminals, upstreams, extractionRules, version, Instant.now());
        }
    }
}
```

- [ ] **Step 4: Прогнать тест**

Run: `mvn -q -Dtest=RouterConfigSnapshotTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/config/RouterConfigSnapshot.java src/test/java/ru/tkbbank/sbprouter/config/RouterConfigSnapshotTest.java
git commit -m "feat(config): add immutable RouterConfigSnapshot with builder"
```

---

## Task 3: `ConfigStore` — потокобезопасный держатель снапшота

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/management/ConfigStore.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/management/ConfigStoreTest.java`

- [ ] **Step 1: Написать падающий тест**

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigStoreTest {

    @Test
    void currentReturnsInitialSnapshot() {
        var initial = RouterConfigSnapshot.builder().version(1).build();
        var store = new ConfigStore(initial);
        assertThat(store.current()).isSameAs(initial);
    }

    @Test
    void replaceSwapsSnapshotAtomically() {
        var store = new ConfigStore(RouterConfigSnapshot.builder().version(1).build());
        var next = RouterConfigSnapshot.builder().version(2).build();
        store.replace(next);
        assertThat(store.current().version()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что падает**

Run: `mvn -q -Dtest=ConfigStoreTest test`
Expected: FAIL (ConfigStore не существует).

- [ ] **Step 3: Реализовать `ConfigStore`**

```java
package ru.tkbbank.sbprouter.management;

import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the live config snapshot. Reads ({@link #current()}) are lock-free;
 * writes ({@link #replace}) swap the reference atomically. Not a Spring bean
 * by itself — assembled in {@link ManagementConfig}.
 */
public class ConfigStore {

    private final AtomicReference<RouterConfigSnapshot> ref;

    public ConfigStore(RouterConfigSnapshot initial) {
        this.ref = new AtomicReference<>(initial);
    }

    public RouterConfigSnapshot current() {
        return ref.get();
    }

    public void replace(RouterConfigSnapshot snapshot) {
        ref.set(snapshot);
    }
}
```

- [ ] **Step 4: Прогнать тест**

Run: `mvn -q -Dtest=ConfigStoreTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/management/ConfigStore.java src/test/java/ru/tkbbank/sbprouter/management/ConfigStoreTest.java
git commit -m "feat(management): add ConfigStore atomic snapshot holder"
```

---

## Task 4: `ConfigOverrideRepository` — JSON load/save override-файла

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/management/ConfigOverrideRepository.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/management/ConfigOverrideRepositoryTest.java`

- [ ] **Step 1: Написать падающий тест**

```java
package ru.tkbbank.sbprouter.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigOverrideRepositoryTest {

    private ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void loadReturnsEmptyWhenFileMissing(@TempDir Path dir) {
        var repo = new ConfigOverrideRepository(mapper(), dir.resolve("nope.json").toString());
        assertThat(repo.load()).isEmpty();
    }

    @Test
    void saveThenLoadRoundTrips(@TempDir Path dir) {
        var path = dir.resolve("overrides.json").toString();
        var repo = new ConfigOverrideRepository(mapper(), path);

        var routing = new SbpRouterProperties.Routing();
        routing.setTkbPayEnabled(true);
        var upstream = new SbpRouterProperties.UpstreamConfig();
        upstream.setUrl("http://x/api");
        var snap = RouterConfigSnapshot.builder()
                .routing(routing)
                .upstreams(Map.of("infosrv", upstream))
                .version(7)
                .build();

        repo.save(snap);
        var loaded = repo.load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(7);
        assertThat(loaded.get().routing().isTkbPayEnabled()).isTrue();
        assertThat(loaded.get().upstreams().get("infosrv").getUrl()).isEqualTo("http://x/api");
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что падает**

Run: `mvn -q -Dtest=ConfigOverrideRepositoryTest test`
Expected: FAIL (ConfigOverrideRepository не существует).

- [ ] **Step 3: Реализовать `ConfigOverrideRepository`**

```java
package ru.tkbbank.sbprouter.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Persists the full config snapshot as JSON on the mounted config volume. */
@Component
public class ConfigOverrideRepository {

    private static final Logger log = LoggerFactory.getLogger(ConfigOverrideRepository.class);

    private final ObjectMapper objectMapper;
    private final Path path;

    public ConfigOverrideRepository(ObjectMapper objectMapper,
                                    @Value("${sbp-router.config.override-path:config/runtime-overrides.json}") String overridePath) {
        this.objectMapper = objectMapper;
        this.path = Path.of(overridePath);
    }

    public Optional<RouterConfigSnapshot> load() {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(Files.readAllBytes(path), RouterConfigSnapshot.class));
        } catch (IOException e) {
            log.error("Failed to read config override file {}: {}", path, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /** Atomic write: temp file + move, so readers never see a half-written file. */
    public void save(RouterConfigSnapshot snapshot) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, "override-", ".tmp");
            Files.write(tmp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot));
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to write config override file {}: {}", path, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }
}
```

> Примечание по сериализации: `RouterConfigSnapshot` — record с компонентами-POJO (`SbpRouterProperties.*`), `Instant` и `long`. Jackson десериализует record по именам компонентов через геттеры/конструктор; `JavaTimeModule` (есть в Spring Boot и в тестовом mapper'е выше) покрывает `Instant` и `Duration`. Если Jackson не подберёт конструктор record автоматически — добавить зависимость `jackson-module-parameter-names` отсутствует не нужно: Spring Boot подключает `parameter-names` модуль; в юнит-тесте mapper создаётся вручную с `JavaTimeModule`, а record Jackson 2.12+ поддерживает из коробки.

- [ ] **Step 4: Прогнать тест**

Run: `mvn -q -Dtest=ConfigOverrideRepositoryTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/management/ConfigOverrideRepository.java src/test/java/ru/tkbbank/sbprouter/management/ConfigOverrideRepositoryTest.java
git commit -m "feat(management): add ConfigOverrideRepository with atomic JSON persistence"
```

---

## Task 5: исключения + `ConfigValidator`

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/management/ConfigValidationException.java`
- Create: `src/main/java/ru/tkbbank/sbprouter/management/VersionConflictException.java`
- Create: `src/main/java/ru/tkbbank/sbprouter/management/ConfigValidator.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/management/ConfigValidatorTest.java`

- [ ] **Step 1: Создать классы исключений**

`ConfigValidationException.java`:

```java
package ru.tkbbank.sbprouter.management;

/** Thrown when a proposed config snapshot fails validation. Carries the offending field. */
public class ConfigValidationException extends RuntimeException {
    private final String field;

    public ConfigValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() { return field; }
}
```

`VersionConflictException.java`:

```java
package ru.tkbbank.sbprouter.management;

/** Thrown when a PUT's expectedVersion does not match the current snapshot version. */
public class VersionConflictException extends RuntimeException {
    public VersionConflictException(long expected, long actual) {
        super("Config version conflict: expected " + expected + " but current is " + actual);
    }
}
```

- [ ] **Step 2: Написать падающий тест валидатора**

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.FieldRule;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ConfigValidatorTest {

    private final ConfigValidator validator = new ConfigValidator();

    private SbpRouterProperties.UpstreamConfig upstream(String url) {
        var u = new SbpRouterProperties.UpstreamConfig();
        u.setUrl(url);
        return u;
    }

    private Map<String, SbpRouterProperties.UpstreamConfig> validUpstreams() {
        return Map.of(
                "infosrv", upstream("http://infosrv/api"),
                "stub-verification", upstream("http://localhost:8080/stub/verification"),
                "stub-connector", upstream("http://localhost:8080/stub/connector"));
    }

    private RouterConfigSnapshot.Builder validBase() {
        var terminals = new SbpRouterProperties.Terminals();
        terminals.setTkbPayList(List.of("MB0000700543"));
        return RouterConfigSnapshot.builder()
                .terminals(terminals)
                .upstreams(validUpstreams());
    }

    @Test
    void acceptsValidSnapshot() {
        validator.validate(validBase().build()); // no exception
    }

    @Test
    void rejectsMissingInfosrv() {
        var snap = validBase().upstreams(Map.of("stub-verification", upstream("http://x/y"))).build();
        var ex = catchThrowableOfType(() -> validator.validate(snap), ConfigValidationException.class);
        assertThat(ex.getField()).isEqualTo("upstreams.infosrv");
    }

    @Test
    void rejectsMalformedUpstreamUrl() {
        var snap = validBase().upstreams(Map.of(
                "infosrv", upstream("not a url"),
                "stub-verification", upstream("http://x/y"),
                "stub-connector", upstream("http://x/z"))).build();
        assertThatThrownBy(() -> validator.validate(snap)).isInstanceOf(ConfigValidationException.class);
    }

    @Test
    void rejectsBlankB2cPrefix() {
        var terminals = new SbpRouterProperties.Terminals();
        terminals.getB2cTerminal().setTkbPayPrefix("  ");
        var snap = validBase().terminals(terminals).build();
        var ex = catchThrowableOfType(() -> validator.validate(snap), ConfigValidationException.class);
        assertThat(ex.getField()).isEqualTo("terminals.b2cTerminal.tkbPayPrefix");
    }

    @Test
    void rejectsUnknownRequestType() {
        var rule = new FieldRule();
        rule.setName("x");
        rule.setPath("/a/b");
        var ruleSet = new SbpRouterProperties.ExtractionRuleSet();
        ruleSet.setRoutingFields(List.of(rule));
        var snap = validBase().extractionRules(Map.of("BogusType", ruleSet)).build();
        var ex = catchThrowableOfType(() -> validator.validate(snap), ConfigValidationException.class);
        assertThat(ex.getField()).startsWith("extractionRules.BogusType");
    }

    @Test
    void rejectsFieldRuleWithNeitherPathNorParentKey() {
        var rule = new FieldRule();
        rule.setName("x");
        var ruleSet = new SbpRouterProperties.ExtractionRuleSet();
        ruleSet.setRoutingFields(List.of(rule));
        var snap = validBase().extractionRules(Map.of("ReqAuthPay", ruleSet)).build();
        assertThatThrownBy(() -> validator.validate(snap)).isInstanceOf(ConfigValidationException.class);
    }

    @Test
    void rejectsDuplicateFieldNamesInRuleSet() {
        var r1 = new FieldRule(); r1.setName("dup"); r1.setPath("/a");
        var r2 = new FieldRule(); r2.setName("dup"); r2.setPath("/b");
        var ruleSet = new SbpRouterProperties.ExtractionRuleSet();
        ruleSet.setRoutingFields(List.of(r1));
        ruleSet.setExtraFields(List.of(r2));
        var snap = validBase().extractionRules(Map.of("ReqAuthPay", ruleSet)).build();
        assertThatThrownBy(() -> validator.validate(snap)).isInstanceOf(ConfigValidationException.class);
    }
}
```

- [ ] **Step 3: Прогнать — убедиться, что падает**

Run: `mvn -q -Dtest=ConfigValidatorTest test`
Expected: FAIL (ConfigValidator не существует).

- [ ] **Step 4: Реализовать `ConfigValidator`**

```java
package ru.tkbbank.sbprouter.management;

import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.FieldRule;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates a proposed config snapshot before it is persisted/applied. */
@Component
public class ConfigValidator {

    private static final Set<String> REQUIRED_UPSTREAMS = Set.of("infosrv", "stub-verification", "stub-connector");
    private static final Set<String> KNOWN_REQUEST_TYPES = Set.of("ReqAuthPay", "ReqNoticePay");

    public void validate(RouterConfigSnapshot snapshot) {
        validateTerminals(snapshot.terminals());
        validateUpstreams(snapshot.upstreams());
        validateExtractionRules(snapshot.extractionRules());
    }

    private void validateTerminals(SbpRouterProperties.Terminals t) {
        if (t == null) throw new ConfigValidationException("terminals", "terminals must not be null");
        requireText("terminals.c2bTerminal.fieldName", t.getC2bTerminal().getFieldName());
        requireText("terminals.b2cTerminal.fieldName", t.getB2cTerminal().getFieldName());
        requireText("terminals.b2cTerminal.tkbPayPrefix", t.getB2cTerminal().getTkbPayPrefix());
        if (t.getTkbPayList() != null) {
            for (String entry : t.getTkbPayList()) {
                if (entry == null || entry.isBlank()) {
                    throw new ConfigValidationException("terminals.tkbPayList", "tkb-pay-list entries must not be blank");
                }
            }
        }
    }

    private void validateUpstreams(java.util.Map<String, SbpRouterProperties.UpstreamConfig> upstreams) {
        if (upstreams == null) throw new ConfigValidationException("upstreams", "upstreams must not be null");
        for (String required : REQUIRED_UPSTREAMS) {
            if (!upstreams.containsKey(required)) {
                throw new ConfigValidationException("upstreams." + required, "required upstream '" + required + "' is missing");
            }
        }
        upstreams.forEach((name, cfg) -> {
            String field = "upstreams." + name;
            if (cfg == null) throw new ConfigValidationException(field, "upstream config must not be null");
            requireText(field + ".url", cfg.getUrl());
            try {
                URI uri = new URI(cfg.getUrl());
                if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                    throw new ConfigValidationException(field + ".url", "url must be http(s)");
                }
            } catch (java.net.URISyntaxException e) {
                throw new ConfigValidationException(field + ".url", "malformed url: " + cfg.getUrl());
            }
            if (cfg.getTimeout() != null && cfg.getTimeout().isNegative() || cfg.getTimeout() != null && cfg.getTimeout().isZero()) {
                throw new ConfigValidationException(field + ".timeout", "timeout must be > 0");
            }
            if (cfg.getRetry() != null) {
                if (cfg.getRetry().getMaxAttempts() < 0) {
                    throw new ConfigValidationException(field + ".retry.maxAttempts", "maxAttempts must be >= 0");
                }
                if (cfg.getRetry().getBackoff() != null && cfg.getRetry().getBackoff().isNegative()) {
                    throw new ConfigValidationException(field + ".retry.backoff", "backoff must be >= 0");
                }
            }
        });
    }

    private void validateExtractionRules(java.util.Map<String, SbpRouterProperties.ExtractionRuleSet> rules) {
        if (rules == null) throw new ConfigValidationException("extractionRules", "extractionRules must not be null");
        rules.forEach((requestType, ruleSet) -> {
            String base = "extractionRules." + requestType;
            if (!KNOWN_REQUEST_TYPES.contains(requestType)) {
                throw new ConfigValidationException(base, "unknown request type '" + requestType + "'");
            }
            Set<String> names = new HashSet<>();
            for (FieldRule rule : allRules(ruleSet)) {
                requireText(base + ".name", rule.getName());
                if (!names.add(rule.getName())) {
                    throw new ConfigValidationException(base + "." + rule.getName(), "duplicate field name");
                }
                boolean hasParentKey = rule.getParent() != null && rule.getKey() != null;
                boolean hasPath = rule.getPath() != null && !rule.getPath().isBlank();
                if (hasParentKey == hasPath) {
                    throw new ConfigValidationException(base + "." + rule.getName(),
                            "field rule must have exactly one of (parent+key) or (path)");
                }
            }
        });
    }

    private List<FieldRule> allRules(SbpRouterProperties.ExtractionRuleSet ruleSet) {
        var all = new java.util.ArrayList<FieldRule>();
        if (ruleSet.getRoutingFields() != null) all.addAll(ruleSet.getRoutingFields());
        if (ruleSet.getExtraFields() != null) all.addAll(ruleSet.getExtraFields());
        return all;
    }

    private void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new ConfigValidationException(field, field + " must not be blank");
        }
    }
}
```

- [ ] **Step 5: Прогнать тест**

Run: `mvn -q -Dtest=ConfigValidatorTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/management/ConfigValidationException.java src/main/java/ru/tkbbank/sbprouter/management/VersionConflictException.java src/main/java/ru/tkbbank/sbprouter/management/ConfigValidator.java src/test/java/ru/tkbbank/sbprouter/management/ConfigValidatorTest.java
git commit -m "feat(management): add ConfigValidator and config exceptions"
```

---

## Task 6: Рефакторинг потребителей на `ConfigStore` (регрессия существующих тестов)

> Цель: четыре потребителя читают `configStore.current()` по факту. Пакетные конструкторы-удобства сохраняем, делегируя в `ConfigStore` с фиксированным снапшотом — поэтому существующие unit-тесты не меняются.

**Files:**
- Modify: `extraction/XmlFieldExtractor.java`, `routing/TerminalDetector.java`, `routing/RoutingDecisionEngine.java`, `proxy/ProxyClient.java`

- [ ] **Step 1: `XmlFieldExtractor`**

Заменить поле и конструкторы (строки 35-44) на:

```java
    private final ConfigStore configStore;

    @Autowired
    public XmlFieldExtractor(ConfigStore configStore) {
        this.configStore = configStore;
    }

    XmlFieldExtractor(Map<String, SbpRouterProperties.ExtractionRuleSet> rules) {
        this(new ConfigStore(RouterConfigSnapshot.builder().extractionRules(rules).build()));
    }
```

Добавить импорты:

```java
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.management.ConfigStore;
```

В `doParse(...)` первой строкой (перед `XMLStreamReader reader = ...`) добавить чтение по факту:

```java
        Map<String, SbpRouterProperties.ExtractionRuleSet> rules = configStore.current().extractionRules();
```

Так все существующие обращения к `rules` внутри `doParse` (например, `rules.containsKey(candidate)`, `rules.get(candidate)`) станут ссылками на локальную переменную.

- [ ] **Step 2: `TerminalDetector`**

Заменить тело класса (поля + конструкторы) на чтение по факту:

```java
    private final ConfigStore configStore;

    @Autowired
    public TerminalDetector(ConfigStore configStore) {
        this.configStore = configStore;
    }

    TerminalDetector(SbpRouterProperties.Terminals terminals) {
        this(new ConfigStore(RouterConfigSnapshot.builder().terminals(terminals).build()));
    }

    public TerminalOwner detect(Map<String, String> fields) {
        SbpRouterProperties.Terminals terminals = configStore.current().terminals();
        Set<String> tkbPayList = Set.copyOf(terminals.getTkbPayList());
        String c2bFieldName = terminals.getC2bTerminal().getFieldName();
        String b2cFieldName = terminals.getB2cTerminal().getFieldName();
        String b2cPrefix = terminals.getB2cTerminal().getTkbPayPrefix();

        String operType = fields.get("sbpOperType");
        if (operType == null) return TerminalOwner.EXTERNAL;
        if (operType.toUpperCase().startsWith("C2B")) {
            String tspId = fields.get(c2bFieldName);
            return (tspId != null && tkbPayList.contains(tspId)) ? TerminalOwner.TKB_PAY : TerminalOwner.EXTERNAL;
        }
        if (operType.toUpperCase().startsWith("B2C")) {
            String termName = fields.get(b2cFieldName);
            return (termName != null && termName.startsWith(b2cPrefix)) ? TerminalOwner.TKB_PAY : TerminalOwner.EXTERNAL;
        }
        return TerminalOwner.EXTERNAL;
    }
```

Добавить импорты `RouterConfigSnapshot`, `ConfigStore` (как выше).

- [ ] **Step 3: `RoutingDecisionEngine`**

Заменить поле и конструкторы (строки 16-25) на:

```java
    private final ConfigStore configStore;

    @Autowired
    public RoutingDecisionEngine(ConfigStore configStore) {
        this.configStore = configStore;
    }

    RoutingDecisionEngine(SbpRouterProperties.Routing routing) {
        this(new ConfigStore(RouterConfigSnapshot.builder().routing(routing).build()));
    }
```

В начале `decide(...)` добавить:

```java
        SbpRouterProperties.Routing routing = configStore.current().routing();
```

Существующее `routing.isTkbPayEnabled()` теперь читает локальную переменную. Добавить импорты `RouterConfigSnapshot`, `ConfigStore`.

- [ ] **Step 4: `ProxyClient`**

Заменить поле `upstreams` и конструктор (строки 19-24) на:

```java
    private final ConfigStore configStore;

    public ProxyClient(WebClient proxyWebClient, ConfigStore configStore) {
        this.webClient = proxyWebClient;
        this.configStore = configStore;
    }
```

В начале `forward(...)` заменить `SbpRouterProperties.UpstreamConfig config = upstreams.get(upstreamName);` на:

```java
        SbpRouterProperties.UpstreamConfig config = configStore.current().upstreams().get(upstreamName);
```

Добавить импорт `ru.tkbbank.sbprouter.management.ConfigStore;` (`SbpRouterProperties` уже импортирован).

- [ ] **Step 5: Прогнать существующие unit-тесты потребителей (регрессия)**

Run: `mvn -q -Dtest=XmlFieldExtractorTest,TerminalDetectorTest,RoutingDecisionEngineTest test`
Expected: PASS (тесты не менялись; конструкторы-удобства делегируют в ConfigStore).

> Полный прогон отложен: `ProxyClient` теперь требует бин `ConfigStore`, которого в контексте ещё нет — `@SpringBootTest` (GcsvcHandlerIntegrationTest) упадёт до Task 7. Это ожидаемо.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/extraction/XmlFieldExtractor.java src/main/java/ru/tkbbank/sbprouter/routing/TerminalDetector.java src/main/java/ru/tkbbank/sbprouter/routing/RoutingDecisionEngine.java src/main/java/ru/tkbbank/sbprouter/proxy/ProxyClient.java
git commit -m "refactor: read config from ConfigStore per request in 4 consumers"
```

---

## Task 7: Бин `ConfigStore` на старте (baseline yml + override-файл)

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/management/ManagementConfig.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/management/ManagementConfigTest.java`

- [ ] **Step 1: Написать падающий тест сборки бина**

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagementConfigTest {

    @Test
    void usesBaselineWhenNoOverride() {
        var props = new SbpRouterProperties();
        var routing = new SbpRouterProperties.Routing();
        routing.setTkbPayEnabled(false);
        props.setRouting(routing);

        var repo = mock(ConfigOverrideRepository.class);
        when(repo.load()).thenReturn(Optional.empty());

        ConfigStore store = new ManagementConfig().configStore(props, repo);
        assertThat(store.current().routing().isTkbPayEnabled()).isFalse();
        assertThat(store.current().version()).isZero();
    }

    @Test
    void overrideSupersedesBaseline() {
        var props = new SbpRouterProperties();

        var routing = new SbpRouterProperties.Routing();
        routing.setTkbPayEnabled(true);
        var override = RouterConfigSnapshot.builder().routing(routing).version(42).build();

        var repo = mock(ConfigOverrideRepository.class);
        when(repo.load()).thenReturn(Optional.of(override));

        ConfigStore store = new ManagementConfig().configStore(props, repo);
        assertThat(store.current().routing().isTkbPayEnabled()).isTrue();
        assertThat(store.current().version()).isEqualTo(42);
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что падает**

Run: `mvn -q -Dtest=ManagementConfigTest test`
Expected: FAIL (ManagementConfig не существует).

- [ ] **Step 3: Реализовать `ManagementConfig`**

```java
package ru.tkbbank.sbprouter.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

@Configuration
public class ManagementConfig {

    private static final Logger log = LoggerFactory.getLogger(ManagementConfig.class);

    @Bean
    public ConfigStore configStore(SbpRouterProperties properties, ConfigOverrideRepository overrideRepository) {
        RouterConfigSnapshot initial = overrideRepository.load()
                .map(snap -> {
                    log.info("Loaded runtime config override (version={})", snap.version());
                    return snap;
                })
                .orElseGet(() -> {
                    log.info("No runtime config override found, using application.yml baseline");
                    return RouterConfigSnapshot.fromProperties(properties);
                });
        return new ConfigStore(initial);
    }
}
```

- [ ] **Step 4: Прогнать тест бина + полный контекст**

Run: `mvn -q -Dtest=ManagementConfigTest test`
Expected: PASS.

Run: `mvn -q test`
Expected: PASS — теперь бин `ConfigStore` есть в контексте, и `GcsvcHandlerIntegrationTest` снова зелёный. Это подтверждает, что рефакторинг Task 6 не сломал боевой путь.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/management/ManagementConfig.java src/test/java/ru/tkbbank/sbprouter/management/ManagementConfigTest.java
git commit -m "feat(management): assemble ConfigStore bean from yml baseline + override file"
```

---

## Task 8: `ConfigService` — оркестрация обновления конфига

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/management/ConfigService.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/management/ConfigServiceTest.java`

> `ConfigService` принимает уже собранные доменные объекты (`Routing`/`Terminals`/`Map<...>`); преобразование из DTO — в `ConfigDtoMapper` (Task 9). Порядок: проверка версии → собрать снапшот → валидация → запись файла → подмена в памяти.

- [ ] **Step 1: Написать падающий тест**

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ConfigServiceTest {

    private Map<String, SbpRouterProperties.UpstreamConfig> upstreams() {
        var u = new SbpRouterProperties.UpstreamConfig();
        u.setUrl("http://infosrv/api");
        var sv = new SbpRouterProperties.UpstreamConfig(); sv.setUrl("http://x/v");
        var sc = new SbpRouterProperties.UpstreamConfig(); sc.setUrl("http://x/c");
        return Map.of("infosrv", u, "stub-verification", sv, "stub-connector", sc);
    }

    private ConfigStore storeAtVersion(long v) {
        return new ConfigStore(RouterConfigSnapshot.builder().upstreams(upstreams()).version(v).build());
    }

    @Test
    void updateRoutingBumpsVersionValidatesPersistsAndApplies() {
        var store = storeAtVersion(3);
        var repo = mock(ConfigOverrideRepository.class);
        var service = new ConfigService(store, new ConfigValidator(), repo);

        var routing = new SbpRouterProperties.Routing();
        routing.setTkbPayEnabled(true);

        var result = service.updateRouting(routing, 3);

        assertThat(result.version()).isEqualTo(4);
        assertThat(store.current().routing().isTkbPayEnabled()).isTrue();
        verify(repo).save(argThat(s -> s.version() == 4));
    }

    @Test
    void rejectsStaleVersion() {
        var store = storeAtVersion(3);
        var repo = mock(ConfigOverrideRepository.class);
        var service = new ConfigService(store, new ConfigValidator(), repo);

        assertThatThrownBy(() -> service.updateRouting(new SbpRouterProperties.Routing(), 2))
                .isInstanceOf(VersionConflictException.class);
        verifyNoInteractions(repo);
    }

    @Test
    void invalidUpdateIsNotPersistedNorApplied() {
        var store = storeAtVersion(3);
        var repo = mock(ConfigOverrideRepository.class);
        var service = new ConfigService(store, new ConfigValidator(), repo);

        // drop required upstreams -> validation fails
        assertThatThrownBy(() -> service.updateUpstreams(Map.of(), 3))
                .isInstanceOf(ConfigValidationException.class);
        verifyNoInteractions(repo);
        assertThat(store.current().version()).isEqualTo(3); // unchanged
    }

    @Test
    void persistFailureLeavesMemoryUnchanged() {
        var store = storeAtVersion(3);
        var repo = mock(ConfigOverrideRepository.class);
        doThrow(new RuntimeException("disk full")).when(repo).save(any());
        var service = new ConfigService(store, new ConfigValidator(), repo);

        var routing = new SbpRouterProperties.Routing();
        routing.setTkbPayEnabled(true);
        assertThatThrownBy(() -> service.updateRouting(routing, 3)).isInstanceOf(RuntimeException.class);
        assertThat(store.current().version()).isEqualTo(3);
        assertThat(store.current().routing().isTkbPayEnabled()).isFalse();
    }
}
```

(Mockito входит в `spring-boot-starter-test`.)

- [ ] **Step 2: Прогнать — убедиться, что падает**

Run: `mvn -q -Dtest=ConfigServiceTest test`
Expected: FAIL (ConfigService не существует).

- [ ] **Step 3: Реализовать `ConfigService`**

```java
package ru.tkbbank.sbprouter.management;

import org.springframework.stereotype.Service;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import java.util.Map;
import java.util.function.Function;

/** Orchestrates a config change: version check -> build -> validate -> persist -> apply. */
@Service
public class ConfigService {

    private final ConfigStore store;
    private final ConfigValidator validator;
    private final ConfigOverrideRepository overrideRepository;

    public ConfigService(ConfigStore store, ConfigValidator validator, ConfigOverrideRepository overrideRepository) {
        this.store = store;
        this.validator = validator;
        this.overrideRepository = overrideRepository;
    }

    public synchronized RouterConfigSnapshot updateRouting(SbpRouterProperties.Routing routing, long expectedVersion) {
        return apply(expectedVersion, b -> b.routing(routing));
    }

    public synchronized RouterConfigSnapshot updateTerminals(SbpRouterProperties.Terminals terminals, long expectedVersion) {
        return apply(expectedVersion, b -> b.terminals(terminals));
    }

    public synchronized RouterConfigSnapshot updateUpstreams(Map<String, SbpRouterProperties.UpstreamConfig> upstreams, long expectedVersion) {
        return apply(expectedVersion, b -> b.upstreams(upstreams));
    }

    public synchronized RouterConfigSnapshot updateExtractionRules(Map<String, SbpRouterProperties.ExtractionRuleSet> rules, long expectedVersion) {
        return apply(expectedVersion, b -> b.extractionRules(rules));
    }

    private RouterConfigSnapshot apply(long expectedVersion, Function<RouterConfigSnapshot.Builder, RouterConfigSnapshot.Builder> patch) {
        RouterConfigSnapshot current = store.current();
        if (current.version() != expectedVersion) {
            throw new VersionConflictException(expectedVersion, current.version());
        }
        RouterConfigSnapshot next = patch.apply(RouterConfigSnapshot.builder(current))
                .version(current.version() + 1)
                .build();
        validator.validate(next);          // throws ConfigValidationException -> 400
        overrideRepository.save(next);     // throws -> 500, memory untouched
        store.replace(next);               // apply only after successful persist
        return next;
    }
}
```

- [ ] **Step 4: Прогнать тест**

Run: `mvn -q -Dtest=ConfigServiceTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/management/ConfigService.java src/test/java/ru/tkbbank/sbprouter/management/ConfigServiceTest.java
git commit -m "feat(management): add ConfigService orchestrating validated, persisted updates"
```

---

## Task 9: DTO + `ConfigDtoMapper`

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/management/dto/` (records ниже)
- Create: `src/main/java/ru/tkbbank/sbprouter/management/ConfigDtoMapper.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/management/ConfigDtoMapperTest.java`

- [ ] **Step 1: Создать DTO-records (один файл на тип, пакет `ru.tkbbank.sbprouter.management.dto`)**

```java
// RoutingConfigDto.java
package ru.tkbbank.sbprouter.management.dto;
public record RoutingConfigDto(boolean tkbPayEnabled) {}
```
```java
// TerminalsConfigDto.java
package ru.tkbbank.sbprouter.management.dto;
import java.util.List;
public record TerminalsConfigDto(String c2bFieldName, String b2cFieldName, String b2cPrefix, List<String> tkbPayList) {}
```
```java
// UpstreamDto.java
package ru.tkbbank.sbprouter.management.dto;
public record UpstreamDto(String url, Long timeoutMillis, Integer maxAttempts, Long backoffMillis) {}
```
```java
// UpstreamsConfigDto.java
package ru.tkbbank.sbprouter.management.dto;
import java.util.Map;
public record UpstreamsConfigDto(Map<String, UpstreamDto> upstreams) {}
```
```java
// FieldRuleDto.java
package ru.tkbbank.sbprouter.management.dto;
public record FieldRuleDto(String name, String parent, String key, String path) {}
```
```java
// ExtractionRuleSetDto.java
package ru.tkbbank.sbprouter.management.dto;
import java.util.List;
public record ExtractionRuleSetDto(List<FieldRuleDto> routingFields, List<FieldRuleDto> extraFields) {}
```
```java
// ExtractionRulesConfigDto.java
package ru.tkbbank.sbprouter.management.dto;
import java.util.Map;
public record ExtractionRulesConfigDto(Map<String, ExtractionRuleSetDto> extractionRules) {}
```
```java
// ConfigSnapshotDto.java
package ru.tkbbank.sbprouter.management.dto;
import java.util.Map;
public record ConfigSnapshotDto(
        long version,
        String updatedAt,
        RoutingConfigDto routing,
        TerminalsConfigDto terminals,
        Map<String, UpstreamDto> upstreams,
        Map<String, ExtractionRuleSetDto> extractionRules) {}
```
```java
// RequestRecordDto.java
package ru.tkbbank.sbprouter.management.dto;
public record RequestRecordDto(
        String timestamp, String correlationId, String requestType, String terminal,
        String terminalOwner, String sbpOperType, String routeDecision,
        Integer upstreamStatusCode, long durationMs, String error) {}
```
```java
// StatusDto.java
package ru.tkbbank.sbprouter.management.dto;
public record StatusDto(boolean up, long uptimeSeconds, boolean tkbPayEnabled,
                        long configVersion, int historySize, int historyCapacity) {}
```
```java
// ErrorBody.java
package ru.tkbbank.sbprouter.management.dto;
public record ErrorBody(String code, String message, String field) {}
```

- [ ] **Step 2: Написать падающий тест мэппера**

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.management.dto.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigDtoMapperTest {

    private final ConfigDtoMapper mapper = new ConfigDtoMapper();

    @Test
    void mapsUpstreamDtoToConfigWithDurations() {
        var dto = new UpstreamsConfigDto(Map.of("infosrv", new UpstreamDto("http://x/api", 30000L, 2, 500L)));
        Map<String, SbpRouterProperties.UpstreamConfig> result = mapper.toUpstreams(dto);
        var cfg = result.get("infosrv");
        assertThat(cfg.getUrl()).isEqualTo("http://x/api");
        assertThat(cfg.getTimeout()).isEqualTo(Duration.ofMillis(30000));
        assertThat(cfg.getRetry().getMaxAttempts()).isEqualTo(2);
        assertThat(cfg.getRetry().getBackoff()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void mapsExtractionRulesDtoToInternal() {
        var dto = new ExtractionRulesConfigDto(Map.of("ReqAuthPay",
                new ExtractionRuleSetDto(
                        List.of(new FieldRuleDto("terminalName", "PayProfile", "Tran.TermName", null)),
                        List.of(new FieldRuleDto("amount", null, null, "/a/b")))));
        var result = mapper.toExtractionRules(dto);
        var rule = result.get("ReqAuthPay").getRoutingFields().get(0);
        assertThat(rule.getName()).isEqualTo("terminalName");
        assertThat(rule.getParent()).isEqualTo("PayProfile");
        assertThat(rule.isNamedBlock()).isTrue();
    }

    @Test
    void mapsSnapshotToDto() {
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        var snap = RouterConfigSnapshot.builder().routing(routing).version(9).build();
        ConfigSnapshotDto dto = mapper.toSnapshotDto(snap);
        assertThat(dto.version()).isEqualTo(9);
        assertThat(dto.routing().tkbPayEnabled()).isTrue();
        assertThat(dto.updatedAt()).isNotBlank();
    }
}
```

- [ ] **Step 3: Прогнать — убедиться, что падает**

Run: `mvn -q -Dtest=ConfigDtoMapperTest test`
Expected: FAIL (ConfigDtoMapper не существует).

- [ ] **Step 4: Реализовать `ConfigDtoMapper`**

```java
package ru.tkbbank.sbprouter.management;

import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.FieldRule;
import ru.tkbbank.sbprouter.management.dto.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ConfigDtoMapper {

    // ---- DTO -> internal ----

    public SbpRouterProperties.Routing toRouting(RoutingConfigDto dto) {
        var r = new SbpRouterProperties.Routing();
        r.setTkbPayEnabled(dto.tkbPayEnabled());
        return r;
    }

    public SbpRouterProperties.Terminals toTerminals(TerminalsConfigDto dto) {
        var t = new SbpRouterProperties.Terminals();
        var c2b = new SbpRouterProperties.C2bTerminal();
        c2b.setFieldName(dto.c2bFieldName());
        t.setC2bTerminal(c2b);
        var b2c = new SbpRouterProperties.B2cTerminal();
        b2c.setFieldName(dto.b2cFieldName());
        b2c.setTkbPayPrefix(dto.b2cPrefix());
        t.setB2cTerminal(b2c);
        t.setTkbPayList(dto.tkbPayList() != null ? List.copyOf(dto.tkbPayList()) : List.of());
        return t;
    }

    public Map<String, SbpRouterProperties.UpstreamConfig> toUpstreams(UpstreamsConfigDto dto) {
        Map<String, SbpRouterProperties.UpstreamConfig> out = new LinkedHashMap<>();
        dto.upstreams().forEach((name, u) -> {
            var cfg = new SbpRouterProperties.UpstreamConfig();
            cfg.setUrl(u.url());
            if (u.timeoutMillis() != null) cfg.setTimeout(Duration.ofMillis(u.timeoutMillis()));
            var retry = new SbpRouterProperties.RetryConfig();
            if (u.maxAttempts() != null) retry.setMaxAttempts(u.maxAttempts());
            if (u.backoffMillis() != null) retry.setBackoff(Duration.ofMillis(u.backoffMillis()));
            cfg.setRetry(retry);
            out.put(name, cfg);
        });
        return out;
    }

    public Map<String, SbpRouterProperties.ExtractionRuleSet> toExtractionRules(ExtractionRulesConfigDto dto) {
        Map<String, SbpRouterProperties.ExtractionRuleSet> out = new LinkedHashMap<>();
        dto.extractionRules().forEach((type, set) -> {
            var ruleSet = new SbpRouterProperties.ExtractionRuleSet();
            ruleSet.setRoutingFields(toFieldRules(set.routingFields()));
            ruleSet.setExtraFields(toFieldRules(set.extraFields()));
            out.put(type, ruleSet);
        });
        return out;
    }

    private List<FieldRule> toFieldRules(List<FieldRuleDto> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream().map(d -> {
            var r = new FieldRule();
            r.setName(d.name());
            r.setParent(d.parent());
            r.setKey(d.key());
            r.setPath(d.path());
            return r;
        }).collect(Collectors.toList());
    }

    // ---- internal -> DTO ----

    public ConfigSnapshotDto toSnapshotDto(RouterConfigSnapshot snap) {
        return new ConfigSnapshotDto(
                snap.version(),
                snap.updatedAt().toString(),
                new RoutingConfigDto(snap.routing().isTkbPayEnabled()),
                toTerminalsDto(snap.terminals()),
                toUpstreamsDto(snap.upstreams()),
                toExtractionRulesDto(snap.extractionRules()));
    }

    private TerminalsConfigDto toTerminalsDto(SbpRouterProperties.Terminals t) {
        return new TerminalsConfigDto(
                t.getC2bTerminal().getFieldName(),
                t.getB2cTerminal().getFieldName(),
                t.getB2cTerminal().getTkbPayPrefix(),
                t.getTkbPayList() != null ? List.copyOf(t.getTkbPayList()) : List.of());
    }

    private Map<String, UpstreamDto> toUpstreamsDto(Map<String, SbpRouterProperties.UpstreamConfig> ups) {
        Map<String, UpstreamDto> out = new LinkedHashMap<>();
        ups.forEach((name, cfg) -> out.put(name, new UpstreamDto(
                cfg.getUrl(),
                cfg.getTimeout() != null ? cfg.getTimeout().toMillis() : null,
                cfg.getRetry() != null ? cfg.getRetry().getMaxAttempts() : null,
                cfg.getRetry() != null && cfg.getRetry().getBackoff() != null ? cfg.getRetry().getBackoff().toMillis() : null)));
        return out;
    }

    private Map<String, ExtractionRuleSetDto> toExtractionRulesDto(Map<String, SbpRouterProperties.ExtractionRuleSet> rules) {
        Map<String, ExtractionRuleSetDto> out = new LinkedHashMap<>();
        rules.forEach((type, set) -> out.put(type, new ExtractionRuleSetDto(
                toFieldRuleDtos(set.getRoutingFields()),
                toFieldRuleDtos(set.getExtraFields()))));
        return out;
    }

    private List<FieldRuleDto> toFieldRuleDtos(List<FieldRule> rules) {
        if (rules == null) return List.of();
        return rules.stream()
                .map(r -> new FieldRuleDto(r.getName(), r.getParent(), r.getKey(), r.getPath()))
                .collect(Collectors.toList());
    }
}
```

- [ ] **Step 5: Прогнать тест**

Run: `mvn -q -Dtest=ConfigDtoMapperTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/management/dto src/main/java/ru/tkbbank/sbprouter/management/ConfigDtoMapper.java src/test/java/ru/tkbbank/sbprouter/management/ConfigDtoMapperTest.java
git commit -m "feat(management): add admin DTOs and ConfigDtoMapper"
```

---

## Task 10: `RequestRecord` + `RequestHistoryStore`

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/history/RequestRecord.java`
- Create: `src/main/java/ru/tkbbank/sbprouter/history/RequestHistoryStore.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/history/RequestHistoryStoreTest.java`

- [ ] **Step 1: Создать `RequestRecord`**

```java
package ru.tkbbank.sbprouter.history;

import java.time.Instant;

public record RequestRecord(
        Instant timestamp,
        String correlationId,
        String requestType,
        String terminal,
        String terminalOwner,
        String sbpOperType,
        String routeDecision,
        Integer upstreamStatusCode,
        long durationMs,
        String error) {
}
```

- [ ] **Step 2: Написать падающий тест буфера**

```java
package ru.tkbbank.sbprouter.history;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHistoryStoreTest {

    private RequestRecord rec(String id) {
        return new RequestRecord(Instant.now(), id, "ReqAuthPay", "T", "EXTERNAL", "B2C", "infosrv", 200, 5, null);
    }

    @Test
    void recentReturnsMostRecentFirst() {
        var store = new RequestHistoryStore(10);
        store.add(rec("a"));
        store.add(rec("b"));
        var recent = store.recent(10);
        assertThat(recent).extracting(RequestRecord::correlationId).containsExactly("b", "a");
    }

    @Test
    void evictsOldestBeyondCapacity() {
        var store = new RequestHistoryStore(3);
        IntStream.rangeClosed(1, 5).forEach(i -> store.add(rec("r" + i)));
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.recent(10)).extracting(RequestRecord::correlationId).containsExactly("r5", "r4", "r3");
    }

    @Test
    void recentRespectsLimit() {
        var store = new RequestHistoryStore(10);
        IntStream.rangeClosed(1, 5).forEach(i -> store.add(rec("r" + i)));
        assertThat(store.recent(2)).hasSize(2);
    }

    @Test
    void concurrentAddsDoNotLoseCapacityInvariant() throws InterruptedException {
        var store = new RequestHistoryStore(100);
        var threads = IntStream.range(0, 8).mapToObj(t -> new Thread(() -> {
            for (int i = 0; i < 1000; i++) store.add(rec("t" + t + "-" + i));
        })).toList();
        threads.forEach(Thread::start);
        for (var th : threads) th.join();
        assertThat(store.size()).isEqualTo(100);
        assertThat(store.capacity()).isEqualTo(100);
    }
}
```

- [ ] **Step 3: Прогнать — убедиться, что падает**

Run: `mvn -q -Dtest=RequestHistoryStoreTest test`
Expected: FAIL (RequestHistoryStore не существует).

- [ ] **Step 4: Реализовать `RequestHistoryStore`**

```java
package ru.tkbbank.sbprouter.history;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/** Thread-safe bounded ring buffer of the most recent request records. */
@Component
public class RequestHistoryStore {

    private final int capacity;
    private final Deque<RequestRecord> buffer;

    public RequestHistoryStore(@Value("${sbp-router.history.capacity:1000}") int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    public synchronized void add(RequestRecord record) {
        if (buffer.size() >= capacity) {
            buffer.pollFirst(); // evict oldest
        }
        buffer.addLast(record);
    }

    /** Most recent first, up to {@code limit}. */
    public synchronized List<RequestRecord> recent(int limit) {
        List<RequestRecord> out = new ArrayList<>(Math.min(limit, buffer.size()));
        Iterator<RequestRecord> it = buffer.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return out;
    }

    public synchronized int size() {
        return buffer.size();
    }

    public int capacity() {
        return capacity;
    }
}
```

- [ ] **Step 5: Прогнать тест**

Run: `mvn -q -Dtest=RequestHistoryStoreTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/history src/test/java/ru/tkbbank/sbprouter/history
git commit -m "feat(history): add RequestRecord and bounded thread-safe RequestHistoryStore"
```

---

## Task 11: Наполнение истории из `GcsvcHandler`

**Files:**
- Modify: `src/main/java/ru/tkbbank/sbprouter/proxy/GcsvcHandler.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/proxy/GcsvcHandlerHistoryIntegrationTest.java`

- [ ] **Step 1: Написать падающий интеграционный тест**

```java
package ru.tkbbank.sbprouter.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.tkbbank.sbprouter.history.RequestHistoryStore;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GcsvcHandlerHistoryIntegrationTest {

    static WireMockServer wireMock = new WireMockServer(0);

    @Autowired WebTestClient webClient;
    @Autowired RequestHistoryStore history;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        wireMock.start();
        registry.add("sbp-router.upstreams.infosrv.url", () -> wireMock.baseUrl() + "/api/gcsvc");
    }

    @AfterAll static void stop() { wireMock.stop(); }
    @BeforeEach void reset() { wireMock.resetAll(); }

    @Test
    void recordsSuccessfulRequestInHistory() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/xml")
                .withBody("<Document><GCSvc><Payment><AnsAuthPay><Status><Code>0</Code></Status></AnsAuthPay></Payment></GCSvc></Document>")));

        int before = history.size();
        byte[] xml = getClass().getClassLoader().getResourceAsStream("test-xml/req-auth-pay-b2c.xml").readAllBytes();

        webClient.post().uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML).accept(MediaType.APPLICATION_XML)
                .bodyValue(xml).exchange().expectStatus().isOk();

        assertThat(history.size()).isGreaterThan(before);
        var latest = history.recent(1).get(0);
        assertThat(latest.requestType()).isEqualTo("ReqAuthPay");
        assertThat(latest.routeDecision()).isEqualTo("infosrv");
        assertThat(latest.upstreamStatusCode()).isEqualTo(200);
        assertThat(latest.durationMs()).isGreaterThanOrEqualTo(0);
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что падает**

Run: `mvn -q -Dtest=GcsvcHandlerHistoryIntegrationTest test`
Expected: FAIL (нет бина-зависимости в хендлере / `history` пуст — компиляция пройдёт, но assertion на requestType/route упадёт, т.к. запись ещё не пишется).

- [ ] **Step 3: Внедрить запись истории в `GcsvcHandler`**

Добавить зависимость и импорт:

```java
import ru.tkbbank.sbprouter.history.RequestHistoryStore;
import ru.tkbbank.sbprouter.history.RequestRecord;
import java.time.Instant;
```

В поля и конструктор добавить `RequestHistoryStore history`:

```java
    private final RequestHistoryStore history;

    public GcsvcHandler(XmlFieldExtractor extractor,
                        TerminalDetector terminalDetector,
                        RoutingDecisionEngine routingEngine,
                        ProxyClient proxyClient,
                        ErrorResponseBuilder errorResponseBuilder,
                        MetricsService metrics,
                        RequestHistoryStore history) {
        this.extractor = extractor;
        this.terminalDetector = terminalDetector;
        this.routingEngine = routingEngine;
        this.proxyClient = proxyClient;
        this.errorResponseBuilder = errorResponseBuilder;
        this.metrics = metrics;
        this.history = history;
    }
```

В начале `handle(...)` (сразу после `metrics.incrementActiveRequests();`) зафиксировать старт:

```java
        long startNanos = System.nanoTime();
```

В блоке успешного ответа (внутри первого `.flatMap(responseBody -> {`), перед `return ServerResponse.ok()...`, добавить запись:

```java
                                history.add(new RequestRecord(
                                        Instant.now(), extraction.correlationId(), extraction.requestType(),
                                        extraction.field("terminalName"), owner.name(), extraction.field("sbpOperType"),
                                        decision.upstreamName(), 200, durationMs(startNanos), null));
```

В блоке `.onErrorResume(ex -> {`, перед `String errorXml = ...`, добавить:

```java
                                history.add(new RequestRecord(
                                        Instant.now(), extraction.correlationId(), extraction.requestType(),
                                        extraction.field("terminalName"), owner.name(), extraction.field("sbpOperType"),
                                        decision.upstreamName(), null, durationMs(startNanos), ex.getMessage()));
```

В ветке невалидного XML (внутри `catch (Exception e) {`, перед `return ServerResponse.badRequest()`), добавить:

```java
                        history.add(new RequestRecord(
                                Instant.now(), null, null, null, null, null, null, null,
                                durationMs(startNanos), "Invalid XML: " + e.getMessage()));
```

Добавить приватный помощник в конец класса:

```java
    private static long durationMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
```

- [ ] **Step 4: Прогнать новый тест + регрессию хендлера**

Run: `mvn -q -Dtest=GcsvcHandlerHistoryIntegrationTest,GcsvcHandlerIntegrationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/proxy/GcsvcHandler.java src/test/java/ru/tkbbank/sbprouter/proxy/GcsvcHandlerHistoryIntegrationTest.java
git commit -m "feat(history): record each request into RequestHistoryStore from GcsvcHandler"
```

---

## Task 12: `AdminTokenFilter` — bearer-токен на `/api/admin/**`

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/management/AdminTokenFilter.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/management/AdminTokenFilterTest.java`

> Используем `WebFilter` (без Spring Security). Фильтр срабатывает только на путях `/api/admin/`. На `/api/gcsvc` и actuator не влияет.

- [ ] **Step 1: Написать падающий unit-тест фильтра**

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenFilterTest {

    private final AdminTokenFilter filter = new AdminTokenFilter("secret");
    private final WebFilterChain passthrough = exchange -> Mono.empty();

    @Test
    void allowsNonAdminPathWithoutToken() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/gcsvc"));
        filter.filter(exchange, passthrough).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull(); // chain handled it
    }

    @Test
    void rejectsAdminPathWithoutToken() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/config"));
        filter.filter(exchange, passthrough).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAdminPathWithWrongToken() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer nope"));
        filter.filter(exchange, passthrough).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsAdminPathWithCorrectToken() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer secret"));
        filter.filter(exchange, passthrough).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что падает**

Run: `mvn -q -Dtest=AdminTokenFilterTest test`
Expected: FAIL (AdminTokenFilter не существует).

- [ ] **Step 3: Реализовать `AdminTokenFilter`**

```java
package ru.tkbbank.sbprouter.management;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class AdminTokenFilter implements WebFilter {

    private static final String PREFIX = "Bearer ";
    private final String token;

    public AdminTokenFilter(@Value("${sbp-router.admin.token:}") String token) {
        this.token = token;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/admin/")) {
            return chain.filter(exchange);
        }
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith(PREFIX) || !auth.substring(PREFIX.length()).equals(token) || token.isBlank()) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
```

> Замечание: в unit-тесте `GET /api/admin/config` используется как путь под защитой; реальные эндпоинты — Task 13/15. Путь-матч `/api/admin/` совпадает с базой контроллеров.

- [ ] **Step 4: Прогнать тест**

Run: `mvn -q -Dtest=AdminTokenFilterTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/management/AdminTokenFilter.java src/test/java/ru/tkbbank/sbprouter/management/AdminTokenFilterTest.java
git commit -m "feat(management): add AdminTokenFilter guarding /api/admin/**"
```

---

## Task 13: `AdminExceptionHandler` + `AdminConfigController`

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/management/AdminExceptionHandler.java`
- Create: `src/main/java/ru/tkbbank/sbprouter/management/AdminConfigController.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/management/AdminConfigControllerIntegrationTest.java`

- [ ] **Step 1: Реализовать `AdminExceptionHandler`**

```java
package ru.tkbbank.sbprouter.management;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.tkbbank.sbprouter.management.dto.ErrorBody;

@RestControllerAdvice(basePackages = "ru.tkbbank.sbprouter.management")
public class AdminExceptionHandler {

    @ExceptionHandler(ConfigValidationException.class)
    public ResponseEntity<ErrorBody> onValidation(ConfigValidationException e) {
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR", e.getMessage(), e.getField()));
    }

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<ErrorBody> onConflict(VersionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("VERSION_CONFLICT", e.getMessage(), null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorBody> onOther(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorBody("INTERNAL_ERROR", e.getMessage(), null));
    }
}
```

- [ ] **Step 2: Реализовать `AdminConfigController`**

```java
package ru.tkbbank.sbprouter.management;

import org.springframework.web.bind.annotation.*;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.management.dto.*;

@RestController
@RequestMapping("/api/admin/config")
public class AdminConfigController {

    private final ConfigStore store;
    private final ConfigService service;
    private final ConfigDtoMapper mapper;

    public AdminConfigController(ConfigStore store, ConfigService service, ConfigDtoMapper mapper) {
        this.store = store;
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public ConfigSnapshotDto get() {
        return mapper.toSnapshotDto(store.current());
    }

    @PutMapping("/routing")
    public ConfigSnapshotDto putRouting(@RequestParam long expectedVersion, @RequestBody RoutingConfigDto dto) {
        return mapper.toSnapshotDto(service.updateRouting(mapper.toRouting(dto), expectedVersion));
    }

    @PutMapping("/terminals")
    public ConfigSnapshotDto putTerminals(@RequestParam long expectedVersion, @RequestBody TerminalsConfigDto dto) {
        return mapper.toSnapshotDto(service.updateTerminals(mapper.toTerminals(dto), expectedVersion));
    }

    @PutMapping("/upstreams")
    public ConfigSnapshotDto putUpstreams(@RequestParam long expectedVersion, @RequestBody UpstreamsConfigDto dto) {
        return mapper.toSnapshotDto(service.updateUpstreams(mapper.toUpstreams(dto), expectedVersion));
    }

    @PutMapping("/extraction-rules")
    public ConfigSnapshotDto putExtractionRules(@RequestParam long expectedVersion, @RequestBody ExtractionRulesConfigDto dto) {
        return mapper.toSnapshotDto(service.updateExtractionRules(mapper.toExtractionRules(dto), expectedVersion));
    }
}
```

- [ ] **Step 3: Написать интеграционный тест (запускаем последним — он опирается на весь стек)**

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.tkbbank.sbprouter.management.dto.ConfigSnapshotDto;
import ru.tkbbank.sbprouter.management.dto.RoutingConfigDto;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "sbp-router.admin.token=test-token",
        "sbp-router.config.override-path=target/test-overrides.json"
})
class AdminConfigControllerIntegrationTest {

    @Autowired WebTestClient client;

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(Path.of("target/test-overrides.json"));
    }

    private WebTestClient.RequestHeadersSpec<?> authedGet() {
        return client.get().uri("/api/admin/config").header(HttpHeaders.AUTHORIZATION, "Bearer test-token");
    }

    @Test
    void getRequiresToken() {
        client.get().uri("/api/admin/config").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void getReturnsSnapshotWithVersion() {
        authedGet().exchange().expectStatus().isOk()
                .expectBody(ConfigSnapshotDto.class)
                .value(s -> assertThat(s.version()).isGreaterThanOrEqualTo(0));
    }

    @Test
    void putRoutingTogglesFlagAndBumpsVersion() {
        ConfigSnapshotDto before = authedGet().exchange().expectStatus().isOk()
                .expectBody(ConfigSnapshotDto.class).returnResult().getResponseBody();

        ConfigSnapshotDto after = client.put()
                .uri(uri -> uri.path("/api/admin/config/routing").queryParam("expectedVersion", before.version()).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .bodyValue(new RoutingConfigDto(!before.routing().tkbPayEnabled()))
                .exchange().expectStatus().isOk()
                .expectBody(ConfigSnapshotDto.class).returnResult().getResponseBody();

        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.routing().tkbPayEnabled()).isEqualTo(!before.routing().tkbPayEnabled());
    }

    @Test
    void putWithStaleVersionReturns409() {
        client.put()
                .uri(uri -> uri.path("/api/admin/config/routing").queryParam("expectedVersion", 99999).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .bodyValue(new RoutingConfigDto(true))
                .exchange().expectStatus().isEqualTo(409);
    }
}
```

- [ ] **Step 4: Прогнать**

Run: `mvn -q -Dtest=AdminConfigControllerIntegrationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/management/AdminExceptionHandler.java src/main/java/ru/tkbbank/sbprouter/management/AdminConfigController.java src/test/java/ru/tkbbank/sbprouter/management/AdminConfigControllerIntegrationTest.java
git commit -m "feat(management): add admin config REST API with validation and optimistic locking"
```

---

## Task 14: `AdminMonitoringController` — лента запросов и статус

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/management/AdminMonitoringController.java`
- Test: `src/test/java/ru/tkbbank/sbprouter/management/AdminMonitoringControllerIntegrationTest.java`

- [ ] **Step 1: Реализовать контроллер**

```java
package ru.tkbbank.sbprouter.management;

import org.springframework.web.bind.annotation.*;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.history.RequestHistoryStore;
import ru.tkbbank.sbprouter.management.dto.RequestRecordDto;
import ru.tkbbank.sbprouter.management.dto.StatusDto;

import java.lang.management.ManagementFactory;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminMonitoringController {

    private final RequestHistoryStore history;
    private final ConfigStore configStore;

    public AdminMonitoringController(RequestHistoryStore history, ConfigStore configStore) {
        this.history = history;
        this.configStore = configStore;
    }

    @GetMapping("/requests")
    public List<RequestRecordDto> requests(@RequestParam(defaultValue = "100") int limit) {
        return history.recent(limit).stream()
                .map(r -> new RequestRecordDto(
                        r.timestamp() != null ? r.timestamp().toString() : null,
                        r.correlationId(), r.requestType(), r.terminal(), r.terminalOwner(),
                        r.sbpOperType(), r.routeDecision(), r.upstreamStatusCode(), r.durationMs(), r.error()))
                .toList();
    }

    @GetMapping("/status")
    public StatusDto status() {
        RouterConfigSnapshot snap = configStore.current();
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        return new StatusDto(true, uptimeSeconds, snap.routing().isTkbPayEnabled(),
                snap.version(), history.size(), history.capacity());
    }
}
```

- [ ] **Step 2: Написать интеграционный тест**

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.tkbbank.sbprouter.management.dto.StatusDto;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = "sbp-router.admin.token=test-token")
class AdminMonitoringControllerIntegrationTest {

    @Autowired WebTestClient client;

    @Test
    void requestsRequireToken() {
        client.get().uri("/api/admin/requests").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void statusReturnsSnapshotAndHistoryInfo() {
        client.get().uri("/api/admin/status").header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(StatusDto.class)
                .value(s -> {
                    assertThat(s.up()).isTrue();
                    assertThat(s.historyCapacity()).isGreaterThan(0);
                    assertThat(s.configVersion()).isGreaterThanOrEqualTo(0);
                });
    }

    @Test
    void requestsReturnsListWithToken() {
        client.get().uri("/api/admin/requests?limit=5").header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(Object.class);
    }
}
```

- [ ] **Step 3: Прогнать**

Run: `mvn -q -Dtest=AdminMonitoringControllerIntegrationTest test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/management/AdminMonitoringController.java src/test/java/ru/tkbbank/sbprouter/management/AdminMonitoringControllerIntegrationTest.java
git commit -m "feat(management): add admin monitoring API (recent requests + status)"
```

---

## Task 15: Наблюдаемость — лог изменений + метрики

**Files:**
- Modify: `src/main/java/ru/tkbbank/sbprouter/management/ConfigService.java`
- Modify: `src/main/java/ru/tkbbank/sbprouter/observability/MetricsService.java` (добавить метрики)
- Test: дополнить `ConfigServiceTest` (опционально — проверка инкремента счётчика)

- [ ] **Step 1: Добавить метрики в `MetricsService`**

Открыть `MetricsService.java`, посмотреть как регистрируются существующие метрики (`MeterRegistry`). Добавить:

```java
    public void recordConfigReload() {
        meterRegistry.counter("sbp_router_config_reloads_total").increment();
    }

    public void registerFlagGauge(java.util.function.Supplier<Number> flagSupplier) {
        io.micrometer.core.instrument.Gauge.builder("sbp_router_tkb_pay_enabled", flagSupplier)
                .register(meterRegistry);
    }
```

(Имя поля реестра — взять из существующего кода `MetricsService`; вероятно `meterRegistry`.)

- [ ] **Step 2: Логировать и инкрементировать счётчик в `ConfigService.apply`**

Добавить зависимость `MetricsService metrics` в конструктор `ConfigService` и в конце `apply(...)` после `store.replace(next);`:

```java
        log.info("Config reloaded: version {} -> {}", current.version(), next.version());
        metrics.recordConfigReload();
        return next;
```

Добавить логгер:

```java
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConfigService.class);
```

Обновить конструктор `ConfigService` (добавить параметр `MetricsService metrics`, сохранить в поле). Обновить `ConfigServiceTest` — передавать `mock(MetricsService.class)` в конструктор.

- [ ] **Step 3: Прогнать затронутые тесты**

Run: `mvn -q -Dtest=ConfigServiceTest test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/management/ConfigService.java src/main/java/ru/tkbbank/sbprouter/observability/MetricsService.java src/test/java/ru/tkbbank/sbprouter/management/ConfigServiceTest.java
git commit -m "feat(observability): log config reloads and add config/flag metrics"
```

---

## Task 16: OpenAPI-контракт в `contract/`

**Files:**
- Create: `/mnt/c/work/sbp_router/contract/back-mgmt-api.openapi.yaml`
- Create: `/mnt/c/work/sbp_router/contract/README.md`

> Контракт лежит в каталоге `contract/` (вне git-репозитория `back`). Это ревьюибельный источник правды; BFF (SP2) сгенерирует из него клиент. В SP1 контракт пишется руками, повторяя реализованный admin-API.

- [ ] **Step 1: Создать `contract/back-mgmt-api.openapi.yaml`**

```yaml
openapi: 3.0.3
info:
  title: SBP Router Management API
  version: 0.1.0
  description: Admin/monitoring API of the SBP router (back). Source of truth for back ↔ front-bff.
servers:
  - url: http://localhost:8080
security:
  - bearerAuth: []
paths:
  /api/admin/config:
    get:
      summary: Full config snapshot
      operationId: getConfig
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ConfigSnapshot' }
        '401': { description: Unauthorized }
  /api/admin/config/routing:
    put:
      summary: Update feature flag
      operationId: putRouting
      parameters:
        - { name: expectedVersion, in: query, required: true, schema: { type: integer, format: int64 } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RoutingConfig' }
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ConfigSnapshot' } } } }
        '400': { description: Validation error, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
        '409': { description: Version conflict, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
  /api/admin/config/terminals:
    put:
      summary: Update terminals
      operationId: putTerminals
      parameters:
        - { name: expectedVersion, in: query, required: true, schema: { type: integer, format: int64 } }
      requestBody:
        required: true
        content: { application/json: { schema: { $ref: '#/components/schemas/TerminalsConfig' } } }
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ConfigSnapshot' } } } }
        '400': { description: Validation error, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
        '409': { description: Version conflict, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
  /api/admin/config/upstreams:
    put:
      summary: Update upstreams
      operationId: putUpstreams
      parameters:
        - { name: expectedVersion, in: query, required: true, schema: { type: integer, format: int64 } }
      requestBody:
        required: true
        content: { application/json: { schema: { $ref: '#/components/schemas/UpstreamsConfig' } } }
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ConfigSnapshot' } } } }
        '400': { description: Validation error, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
        '409': { description: Version conflict, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
  /api/admin/config/extraction-rules:
    put:
      summary: Update extraction rules
      operationId: putExtractionRules
      parameters:
        - { name: expectedVersion, in: query, required: true, schema: { type: integer, format: int64 } }
      requestBody:
        required: true
        content: { application/json: { schema: { $ref: '#/components/schemas/ExtractionRulesConfig' } } }
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ConfigSnapshot' } } } }
        '400': { description: Validation error, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
        '409': { description: Version conflict, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
  /api/admin/requests:
    get:
      summary: Recent request records (most recent first)
      operationId: getRequests
      parameters:
        - { name: limit, in: query, required: false, schema: { type: integer, default: 100 } }
      responses:
        '200': { description: OK, content: { application/json: { schema: { type: array, items: { $ref: '#/components/schemas/RequestRecord' } } } } }
        '401': { description: Unauthorized }
  /api/admin/status:
    get:
      summary: Service status summary
      operationId: getStatus
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/Status' } } } }
        '401': { description: Unauthorized }
components:
  securitySchemes:
    bearerAuth: { type: http, scheme: bearer }
  schemas:
    RoutingConfig:
      type: object
      required: [tkbPayEnabled]
      properties:
        tkbPayEnabled: { type: boolean }
    TerminalsConfig:
      type: object
      properties:
        c2bFieldName: { type: string }
        b2cFieldName: { type: string }
        b2cPrefix: { type: string }
        tkbPayList: { type: array, items: { type: string } }
    Upstream:
      type: object
      properties:
        url: { type: string }
        timeoutMillis: { type: integer, format: int64 }
        maxAttempts: { type: integer }
        backoffMillis: { type: integer, format: int64 }
    UpstreamsConfig:
      type: object
      properties:
        upstreams: { type: object, additionalProperties: { $ref: '#/components/schemas/Upstream' } }
    FieldRule:
      type: object
      properties:
        name: { type: string }
        parent: { type: string }
        key: { type: string }
        path: { type: string }
    ExtractionRuleSet:
      type: object
      properties:
        routingFields: { type: array, items: { $ref: '#/components/schemas/FieldRule' } }
        extraFields: { type: array, items: { $ref: '#/components/schemas/FieldRule' } }
    ExtractionRulesConfig:
      type: object
      properties:
        extractionRules: { type: object, additionalProperties: { $ref: '#/components/schemas/ExtractionRuleSet' } }
    ConfigSnapshot:
      type: object
      properties:
        version: { type: integer, format: int64 }
        updatedAt: { type: string }
        routing: { $ref: '#/components/schemas/RoutingConfig' }
        terminals: { $ref: '#/components/schemas/TerminalsConfig' }
        upstreams: { type: object, additionalProperties: { $ref: '#/components/schemas/Upstream' } }
        extractionRules: { type: object, additionalProperties: { $ref: '#/components/schemas/ExtractionRuleSet' } }
    RequestRecord:
      type: object
      properties:
        timestamp: { type: string }
        correlationId: { type: string }
        requestType: { type: string }
        terminal: { type: string }
        terminalOwner: { type: string }
        sbpOperType: { type: string }
        routeDecision: { type: string }
        upstreamStatusCode: { type: integer }
        durationMs: { type: integer, format: int64 }
        error: { type: string }
    Status:
      type: object
      properties:
        up: { type: boolean }
        uptimeSeconds: { type: integer, format: int64 }
        tkbPayEnabled: { type: boolean }
        configVersion: { type: integer, format: int64 }
        historySize: { type: integer }
        historyCapacity: { type: integer }
    ErrorBody:
      type: object
      properties:
        code: { type: string }
        message: { type: string }
        field: { type: string }
```

- [ ] **Step 2: Создать `contract/README.md`**

```markdown
# Contracts

Источник правды для API между сервисами воркспейса.

- `back-mgmt-api.openapi.yaml` — management/monitoring API роутера (`back`).
  Реализуется в `back` (SP1, контроллеры написаны вручную по этому контракту),
  потребляется `front-bff` (SP2, клиент генерируется из этого файла).

При изменении контракта: сперва правим yaml, затем синхронизируем реализацию в back
и (в SP2) перегенерируем клиент BFF.
```

- [ ] **Step 3: Commit (в репозитории `contract/`, если он git-инициализирован; иначе — добавить позже)**

```bash
# contract/ может быть в своём git или общим — закоммитить там, где он версионируется.
# Если contract/ не под git, отметить файлы как готовые; версионирование настраивается отдельно.
ls -la /mnt/c/work/sbp_router/contract/
```

> Примечание: `contract/` — отдельный каталог, не входит в git-репозиторий `back`. Версионирование `contract/` (свой git или общий монореп-репо) — организационное решение вне scope этого плана; файлы создаются здесь и готовы к коммиту в выбранном репозитории.

---

## Task 17: Финальная проверка всего SP1

- [ ] **Step 1: Полный прогон тестов**

Run: `mvn -q test`
Expected: BUILD SUCCESS, все тесты зелёные (включая регрессию боевого пути `GcsvcHandlerIntegrationTest`).

- [ ] **Step 2: Проверка сборки приложения**

Run: `mvn -q -DskipTests package`
Expected: BUILD SUCCESS, собран jar в `target/`.

- [ ] **Step 3: Ручная дымовая проверка (опционально, локально)**

```bash
SBP_ADMIN_TOKEN=test mvn -q spring-boot:run &
sleep 20
curl -s -H "Authorization: Bearer test" http://localhost:8080/api/admin/config | head -c 400
curl -s -H "Authorization: Bearer test" http://localhost:8080/api/admin/status
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/admin/config   # ожидаем 401
# остановить: kill %1
```

Expected: `GET /config` и `/status` возвращают JSON с токеном; без токена — `401`.

- [ ] **Step 4: Финальный commit (если остались несохранённые изменения)**

```bash
git status
git add -A && git commit -m "chore(sp1): finalize management/monitoring API" || echo "nothing to commit"
```

---

## Self-Review (выполнено автором плана)

- **Покрытие спеки:** ConfigStore+снапшот (Tasks 2,3,7) ✓; write-through override (Task 4) ✓; слияние baseline+override на старте (Task 7) ✓; рефакторинг 4 потребителей, включая TerminalDetector (Task 6) ✓; валидация всех доменов (Task 5) ✓; порядок «валидация→файл→память» + откат при сбое (Task 8) ✓; admin-эндпоинты config GET/PUT (Task 13) ✓; оптимистичная блокировка version/409 (Tasks 8,13) ✓; лента запросов + RequestHistoryStore (Tasks 10,11) ✓; /requests и /status (Task 14) ✓; bearer-токен/401 (Task 12) ✓; формат ошибок/400/409/500 (Task 13) ✓; лог изменений + метрики (Task 15) ✓; OpenAPI-контракт (Task 16) ✓; регрессия существующих тестов (Tasks 6,7,17) ✓.
- **Отступления от спеки (помечены вверху):** DTO пишутся руками (кодоген отложен в SP2); обязательные upstream'ы = 3 фактически настроенных (не 4). Оба согласованы при планировании.
- **Плейсхолдеры:** отсутствуют — в каждом шаге показан реальный код/команда.
- **Согласованность типов:** `ConfigStore.current()/replace()`, `RouterConfigSnapshot.builder()/builder(base)/fromProperties()`, `ConfigService.update*(domain, expectedVersion)`, DTO-имена и `ConfigDtoMapper` методы — использованы единообразно во всех задачах.
