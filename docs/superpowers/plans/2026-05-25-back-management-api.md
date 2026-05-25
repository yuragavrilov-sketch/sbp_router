# SBP Router — Management/Monitoring API (SP1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (рекомендуется для укрупнённых задач) или superpowers:subagent-driven-development. Шаги внутри задач помечены чекбоксами (`- [ ]`).

**Goal:** Дать SBP-роутеру управляющую плоскость: рантайм-изменение всей конфигурации без рестарта (write-through в override-файл) и мониторинг (лента последних запросов + статус), не затрагивая боевой путь `POST /api/gcsvc`.

**Architecture:** Иммутабельный `RouterConfigSnapshot` в `ConfigStore` (`AtomicReference`); четыре потребителя (`XmlFieldExtractor`, `TerminalDetector`, `RoutingDecisionEngine`, `ProxyClient`) читают `configStore.current()` по факту вместо кэша в конструкторе. Admin REST под `/api/admin/*` (annotated `@RestController`) с bearer-токеном и оптимистичной блокировкой по `version`; изменение идёт строго «валидация → запись файла → атомарная подмена в памяти». Лента запросов — потокобезопасный кольцевой буфер, наполняемый из `GcsvcHandler`.

**Tech Stack:** Java 17, Spring Boot 3.4.3 (WebFlux), Jackson, JUnit 5 + AssertJ + WebTestClient + Reactor Test + WireMock + Mockito. Сборка — Maven (`mvn`, из каталога `back/`).

---

## Структура: 3 укрупнённые задачи

1. **Ядро рантайм-конфигурации** — снапшот, store, персистентность, валидация, оркестрация, рефакторинг 4 потребителей. Результат: конфиг меняется в памяти + переживает рестарт; боевой путь зелёный.
2. **Admin REST API + лента запросов + наблюдаемость** — DTO/мэппер, токен-фильтр, обработчик ошибок, контроллеры config/monitoring, история запросов и её наполнение, метрики/логи.
3. **OpenAPI-контракт + финальная проверка** — `contract/back-mgmt-api.openapi.yaml` как источник правды, полный прогон и дымовая проверка.

## Соглашения

- Все команды — из `/mnt/c/work/sbp_router/back`. Полный прогон: `mvn -q test`. Один класс: `mvn -q -Dtest=ClassName test`.
- Внутри задачи держим TDD-ритм (сначала тесты «красные», затем код «зелёный»), но чек-поинт/ревью — на границе задачи, а не каждого класса.
- Коммитим по логическим блокам (предложенные сообщения — в конце каждой задачи); каждое сообщение коммита заканчивать трейлером `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` (CLAUDE.md).
- Новые пакеты: `ru.tkbbank.sbprouter.management`, `…management.dto`, `…history`. `RouterConfigSnapshot` — в `config` (рядом с `SbpRouterProperties`).

## Решения, отступающие от спеки (подтверждены)

1. **DTO пишем руками в `back`**, OpenAPI ведём как источник правды; кодоген — в SP2 (BFF).
2. Оптимистичная блокировка — query-параметром `?expectedVersion=N` на каждом `PUT`.
3. Обязательные при валидации upstream'ы — `infosrv`, `stub-verification`, `stub-connector` (что реально в yml); `stub-c2bqrd-verification` валидируется только если присутствует.
4. Override-файл хранит **полный** снапшот и, будучи записан, полностью замещает yml до удаления.

---

# Task 1: Ядро рантайм-конфигурации

**Что делаем:** новые свойства; иммутабельный снапшот; `ConfigStore`; персистентность в override-файл; валидатор; оркестратор обновления; сборка `ConfigStore`-бина на старте (yml + override); рефакторинг 4 потребителей на чтение из store. По завершении `mvn test` зелёный, боевой путь не сломан.

**Files — создать:**
- `config/RouterConfigSnapshot.java`, `management/ConfigStore.java`, `management/ConfigOverrideRepository.java`,
  `management/ConfigValidationException.java`, `management/VersionConflictException.java`, `management/ConfigValidator.java`,
  `management/ManagementConfig.java`, `management/ConfigService.java`
- тесты: `config/RouterConfigSnapshotTest.java`, `management/ConfigStoreTest.java`, `management/ConfigOverrideRepositoryTest.java`,
  `management/ConfigValidatorTest.java`, `management/ManagementConfigTest.java`, `management/ConfigServiceTest.java`

**Files — изменить:**
- `config/SbpRouterProperties.java`, `config/application.yml`,
  `extraction/XmlFieldExtractor.java`, `routing/TerminalDetector.java`, `routing/RoutingDecisionEngine.java`, `proxy/ProxyClient.java`
- дополнить `config/SbpRouterPropertiesTest.java`

### Шаги

- [ ] **1.1 — Свойства.** В `SbpRouterProperties` добавить поля/геттеры и вложенные классы:

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

В `config/application.yml` в блок `sbp-router:` (после `routing:`):

```yaml
  admin:
    token: ${SBP_ADMIN_TOKEN:change-me-in-prod}
  history:
    capacity: 1000
  config:
    override-path: config/runtime-overrides.json
```

Тест в `SbpRouterPropertiesTest` (добавить; `import static org.assertj.core.api.Assertions.assertThat;`):

```java
    @org.junit.jupiter.api.Test
    void bindsAdminHistoryAndConfigDefaults() {
        var props = new SbpRouterProperties();
        assertThat(props.getAdmin().getToken()).isEqualTo("");
        assertThat(props.getHistory().getCapacity()).isEqualTo(1000);
        assertThat(props.getConfig().getOverridePath()).isEqualTo("config/runtime-overrides.json");
    }
```

- [ ] **1.2 — Снапшот `RouterConfigSnapshot`** (`config/RouterConfigSnapshot.java`):

```java
package ru.tkbbank.sbprouter.config;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable slice of the managed sbp-router configuration. Domain objects are
 * treated as effectively immutable: ConfigService always supplies fresh instances.
 */
public record RouterConfigSnapshot(
        SbpRouterProperties.Routing routing,
        SbpRouterProperties.Terminals terminals,
        Map<String, SbpRouterProperties.UpstreamConfig> upstreams,
        Map<String, SbpRouterProperties.ExtractionRuleSet> extractionRules,
        long version,
        Instant updatedAt
) {
    public static Builder builder() { return new Builder(); }

    public static Builder builder(RouterConfigSnapshot base) {
        return new Builder()
                .routing(base.routing()).terminals(base.terminals())
                .upstreams(base.upstreams()).extractionRules(base.extractionRules())
                .version(base.version());
    }

    public static RouterConfigSnapshot fromProperties(SbpRouterProperties props) {
        return builder()
                .routing(props.getRouting() != null ? props.getRouting() : new SbpRouterProperties.Routing())
                .terminals(props.getTerminals() != null ? props.getTerminals() : new SbpRouterProperties.Terminals())
                .upstreams(props.getUpstreams() != null ? props.getUpstreams() : Map.of())
                .extractionRules(props.getExtractionRules() != null ? props.getExtractionRules() : Map.of())
                .version(0).build();
    }

    public static final class Builder {
        private SbpRouterProperties.Routing routing = new SbpRouterProperties.Routing();
        private SbpRouterProperties.Terminals terminals = new SbpRouterProperties.Terminals();
        private Map<String, SbpRouterProperties.UpstreamConfig> upstreams = Map.of();
        private Map<String, SbpRouterProperties.ExtractionRuleSet> extractionRules = Map.of();
        private long version = 0;

        public Builder routing(SbpRouterProperties.Routing v) { this.routing = v; return this; }
        public Builder terminals(SbpRouterProperties.Terminals v) { this.terminals = v; return this; }
        public Builder upstreams(Map<String, SbpRouterProperties.UpstreamConfig> v) { this.upstreams = v != null ? Map.copyOf(v) : Map.of(); return this; }
        public Builder extractionRules(Map<String, SbpRouterProperties.ExtractionRuleSet> v) { this.extractionRules = v != null ? Map.copyOf(v) : Map.of(); return this; }
        public Builder version(long v) { this.version = v; return this; }

        public RouterConfigSnapshot build() {
            return new RouterConfigSnapshot(routing, terminals, upstreams, extractionRules, version, Instant.now());
        }
    }
}
```

Тест `RouterConfigSnapshotTest`:

```java
package ru.tkbbank.sbprouter.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RouterConfigSnapshotTest {
    @Test void builderFillsSaneDefaults() {
        var snap = RouterConfigSnapshot.builder().build();
        assertThat(snap.routing()).isNotNull();
        assertThat(snap.routing().isTkbPayEnabled()).isFalse();
        assertThat(snap.terminals()).isNotNull();
        assertThat(snap.upstreams()).isEmpty();
        assertThat(snap.extractionRules()).isEmpty();
        assertThat(snap.version()).isZero();
        assertThat(snap.updatedAt()).isNotNull();
    }
    @Test void fromPropertiesCopiesManagedDomains() {
        var props = new SbpRouterProperties();
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        props.setRouting(routing);
        props.setUpstreams(Map.of("infosrv", new SbpRouterProperties.UpstreamConfig()));
        var snap = RouterConfigSnapshot.fromProperties(props);
        assertThat(snap.routing().isTkbPayEnabled()).isTrue();
        assertThat(snap.upstreams()).containsKey("infosrv");
        assertThat(snap.version()).isZero();
    }
    @Test void builderCopyKeepsUnchangedDomainsAndBumpsVersion() {
        var base = RouterConfigSnapshot.builder().version(5).build();
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        var next = RouterConfigSnapshot.builder(base).routing(routing).version(6).build();
        assertThat(next.version()).isEqualTo(6);
        assertThat(next.routing().isTkbPayEnabled()).isTrue();
    }
}
```

- [ ] **1.3 — `ConfigStore`** (`management/ConfigStore.java`):

```java
package ru.tkbbank.sbprouter.management;

import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import java.util.concurrent.atomic.AtomicReference;

/** Holds the live config snapshot. Reads are lock-free; writes swap atomically. */
public class ConfigStore {
    private final AtomicReference<RouterConfigSnapshot> ref;
    public ConfigStore(RouterConfigSnapshot initial) { this.ref = new AtomicReference<>(initial); }
    public RouterConfigSnapshot current() { return ref.get(); }
    public void replace(RouterConfigSnapshot snapshot) { ref.set(snapshot); }
}
```

Тест `ConfigStoreTest`:

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigStoreTest {
    @Test void currentReturnsInitialSnapshot() {
        var initial = RouterConfigSnapshot.builder().version(1).build();
        assertThat(new ConfigStore(initial).current()).isSameAs(initial);
    }
    @Test void replaceSwapsSnapshotAtomically() {
        var store = new ConfigStore(RouterConfigSnapshot.builder().version(1).build());
        store.replace(RouterConfigSnapshot.builder().version(2).build());
        assertThat(store.current().version()).isEqualTo(2);
    }
}
```

- [ ] **1.4 — `ConfigOverrideRepository`** (`management/ConfigOverrideRepository.java`):

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
import java.nio.file.*;
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
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(Files.readAllBytes(path), RouterConfigSnapshot.class));
        } catch (IOException e) {
            log.error("Failed to read config override {}: {}", path, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /** Atomic write: temp file + move. */
    public void save(RouterConfigSnapshot snapshot) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "override-", ".tmp");
            Files.write(tmp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot));
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to write config override {}: {}", path, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }
}
```

Тест `ConfigOverrideRepositoryTest` (Jackson record + `JavaTimeModule` для `Instant`/`Duration`):

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
    private ObjectMapper mapper() { return new ObjectMapper().registerModule(new JavaTimeModule()); }

    @Test void loadReturnsEmptyWhenFileMissing(@TempDir Path dir) {
        assertThat(new ConfigOverrideRepository(mapper(), dir.resolve("nope.json").toString()).load()).isEmpty();
    }
    @Test void saveThenLoadRoundTrips(@TempDir Path dir) {
        var repo = new ConfigOverrideRepository(mapper(), dir.resolve("overrides.json").toString());
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        var upstream = new SbpRouterProperties.UpstreamConfig(); upstream.setUrl("http://x/api");
        var snap = RouterConfigSnapshot.builder().routing(routing).upstreams(Map.of("infosrv", upstream)).version(7).build();
        repo.save(snap);
        var loaded = repo.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(7);
        assertThat(loaded.get().routing().isTkbPayEnabled()).isTrue();
        assertThat(loaded.get().upstreams().get("infosrv").getUrl()).isEqualTo("http://x/api");
    }
}
```

- [ ] **1.5 — Исключения + `ConfigValidator`.**

`ConfigValidationException.java`:

```java
package ru.tkbbank.sbprouter.management;
public class ConfigValidationException extends RuntimeException {
    private final String field;
    public ConfigValidationException(String field, String message) { super(message); this.field = field; }
    public String getField() { return field; }
}
```

`VersionConflictException.java`:

```java
package ru.tkbbank.sbprouter.management;
public class VersionConflictException extends RuntimeException {
    public VersionConflictException(long expected, long actual) {
        super("Config version conflict: expected " + expected + " but current is " + actual);
    }
}
```

`ConfigValidator.java`:

```java
package ru.tkbbank.sbprouter.management;

import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.FieldRule;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        if (t.getTkbPayList() != null)
            for (String e : t.getTkbPayList())
                if (e == null || e.isBlank())
                    throw new ConfigValidationException("terminals.tkbPayList", "tkb-pay-list entries must not be blank");
    }

    private void validateUpstreams(Map<String, SbpRouterProperties.UpstreamConfig> upstreams) {
        if (upstreams == null) throw new ConfigValidationException("upstreams", "upstreams must not be null");
        for (String req : REQUIRED_UPSTREAMS)
            if (!upstreams.containsKey(req))
                throw new ConfigValidationException("upstreams." + req, "required upstream '" + req + "' is missing");
        upstreams.forEach((name, cfg) -> {
            String f = "upstreams." + name;
            if (cfg == null) throw new ConfigValidationException(f, "upstream config must not be null");
            requireText(f + ".url", cfg.getUrl());
            try {
                URI uri = new URI(cfg.getUrl());
                if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")))
                    throw new ConfigValidationException(f + ".url", "url must be http(s)");
            } catch (java.net.URISyntaxException e) {
                throw new ConfigValidationException(f + ".url", "malformed url: " + cfg.getUrl());
            }
            if (cfg.getTimeout() != null && (cfg.getTimeout().isNegative() || cfg.getTimeout().isZero()))
                throw new ConfigValidationException(f + ".timeout", "timeout must be > 0");
            if (cfg.getRetry() != null) {
                if (cfg.getRetry().getMaxAttempts() < 0)
                    throw new ConfigValidationException(f + ".retry.maxAttempts", "maxAttempts must be >= 0");
                if (cfg.getRetry().getBackoff() != null && cfg.getRetry().getBackoff().isNegative())
                    throw new ConfigValidationException(f + ".retry.backoff", "backoff must be >= 0");
            }
        });
    }

    private void validateExtractionRules(Map<String, SbpRouterProperties.ExtractionRuleSet> rules) {
        if (rules == null) throw new ConfigValidationException("extractionRules", "extractionRules must not be null");
        rules.forEach((type, ruleSet) -> {
            String base = "extractionRules." + type;
            if (!KNOWN_REQUEST_TYPES.contains(type))
                throw new ConfigValidationException(base, "unknown request type '" + type + "'");
            Set<String> names = new HashSet<>();
            for (FieldRule r : allRules(ruleSet)) {
                requireText(base + ".name", r.getName());
                if (!names.add(r.getName()))
                    throw new ConfigValidationException(base + "." + r.getName(), "duplicate field name");
                boolean hasParentKey = r.getParent() != null && r.getKey() != null;
                boolean hasPath = r.getPath() != null && !r.getPath().isBlank();
                if (hasParentKey == hasPath)
                    throw new ConfigValidationException(base + "." + r.getName(),
                            "field rule must have exactly one of (parent+key) or (path)");
            }
        });
    }

    private List<FieldRule> allRules(SbpRouterProperties.ExtractionRuleSet rs) {
        var all = new java.util.ArrayList<FieldRule>();
        if (rs.getRoutingFields() != null) all.addAll(rs.getRoutingFields());
        if (rs.getExtraFields() != null) all.addAll(rs.getExtraFields());
        return all;
    }

    private void requireText(String field, String value) {
        if (value == null || value.isBlank()) throw new ConfigValidationException(field, field + " must not be blank");
    }
}
```

Тест `ConfigValidatorTest`:

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.FieldRule;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ConfigValidatorTest {
    private final ConfigValidator validator = new ConfigValidator();

    private SbpRouterProperties.UpstreamConfig upstream(String url) {
        var u = new SbpRouterProperties.UpstreamConfig(); u.setUrl(url); return u;
    }
    private Map<String, SbpRouterProperties.UpstreamConfig> validUpstreams() {
        return Map.of("infosrv", upstream("http://infosrv/api"),
                "stub-verification", upstream("http://localhost:8080/stub/verification"),
                "stub-connector", upstream("http://localhost:8080/stub/connector"));
    }
    private RouterConfigSnapshot.Builder validBase() {
        var t = new SbpRouterProperties.Terminals(); t.setTkbPayList(List.of("MB0000700543"));
        return RouterConfigSnapshot.builder().terminals(t).upstreams(validUpstreams());
    }

    @Test void acceptsValidSnapshot() { validator.validate(validBase().build()); }

    @Test void rejectsMissingInfosrv() {
        var snap = validBase().upstreams(Map.of("stub-verification", upstream("http://x/y"))).build();
        var ex = catchThrowableOfType(() -> validator.validate(snap), ConfigValidationException.class);
        assertThat(ex.getField()).isEqualTo("upstreams.infosrv");
    }
    @Test void rejectsMalformedUpstreamUrl() {
        var snap = validBase().upstreams(Map.of("infosrv", upstream("not a url"),
                "stub-verification", upstream("http://x/y"), "stub-connector", upstream("http://x/z"))).build();
        assertThatThrownBy(() -> validator.validate(snap)).isInstanceOf(ConfigValidationException.class);
    }
    @Test void rejectsBlankB2cPrefix() {
        var t = new SbpRouterProperties.Terminals(); t.getB2cTerminal().setTkbPayPrefix("  ");
        var ex = catchThrowableOfType(() -> validator.validate(validBase().terminals(t).build()), ConfigValidationException.class);
        assertThat(ex.getField()).isEqualTo("terminals.b2cTerminal.tkbPayPrefix");
    }
    @Test void rejectsUnknownRequestType() {
        var rule = new FieldRule(); rule.setName("x"); rule.setPath("/a/b");
        var rs = new SbpRouterProperties.ExtractionRuleSet(); rs.setRoutingFields(List.of(rule));
        var ex = catchThrowableOfType(() -> validator.validate(validBase().extractionRules(Map.of("BogusType", rs)).build()), ConfigValidationException.class);
        assertThat(ex.getField()).startsWith("extractionRules.BogusType");
    }
    @Test void rejectsFieldRuleWithNeitherPathNorParentKey() {
        var rule = new FieldRule(); rule.setName("x");
        var rs = new SbpRouterProperties.ExtractionRuleSet(); rs.setRoutingFields(List.of(rule));
        assertThatThrownBy(() -> validator.validate(validBase().extractionRules(Map.of("ReqAuthPay", rs)).build()))
                .isInstanceOf(ConfigValidationException.class);
    }
    @Test void rejectsDuplicateFieldNamesInRuleSet() {
        var r1 = new FieldRule(); r1.setName("dup"); r1.setPath("/a");
        var r2 = new FieldRule(); r2.setName("dup"); r2.setPath("/b");
        var rs = new SbpRouterProperties.ExtractionRuleSet(); rs.setRoutingFields(List.of(r1)); rs.setExtraFields(List.of(r2));
        assertThatThrownBy(() -> validator.validate(validBase().extractionRules(Map.of("ReqAuthPay", rs)).build()))
                .isInstanceOf(ConfigValidationException.class);
    }
}
```

- [ ] **1.6 — Рефакторинг 4 потребителей на `ConfigStore`** (конструкторы-удобства сохраняем → существующие unit-тесты не меняются).

`XmlFieldExtractor`: заменить поле/конструкторы (строки 35-44) на:

```java
    private final ConfigStore configStore;

    @Autowired
    public XmlFieldExtractor(ConfigStore configStore) { this.configStore = configStore; }

    XmlFieldExtractor(Map<String, SbpRouterProperties.ExtractionRuleSet> rules) {
        this(new ConfigStore(RouterConfigSnapshot.builder().extractionRules(rules).build()));
    }
```
импорты `ru.tkbbank.sbprouter.config.RouterConfigSnapshot`, `ru.tkbbank.sbprouter.management.ConfigStore`; первой строкой в `doParse(...)`:
```java
        Map<String, SbpRouterProperties.ExtractionRuleSet> rules = configStore.current().extractionRules();
```

`TerminalDetector`: заменить поля/конструкторы и читать из store в `detect`:

```java
    private final ConfigStore configStore;

    @Autowired
    public TerminalDetector(ConfigStore configStore) { this.configStore = configStore; }

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
(добавить импорты `RouterConfigSnapshot`, `ConfigStore`.)

`RoutingDecisionEngine`: заменить поле/конструкторы (строки 16-25):

```java
    private final ConfigStore configStore;

    @Autowired
    public RoutingDecisionEngine(ConfigStore configStore) { this.configStore = configStore; }

    RoutingDecisionEngine(SbpRouterProperties.Routing routing) {
        this(new ConfigStore(RouterConfigSnapshot.builder().routing(routing).build()));
    }
```
в начале `decide(...)`: `SbpRouterProperties.Routing routing = configStore.current().routing();` (добавить импорты).

`ProxyClient`: конструктор и чтение upstream:

```java
    private final ConfigStore configStore;
    public ProxyClient(WebClient proxyWebClient, ConfigStore configStore) {
        this.webClient = proxyWebClient; this.configStore = configStore;
    }
```
в `forward(...)`: `SbpRouterProperties.UpstreamConfig config = configStore.current().upstreams().get(upstreamName);` (импорт `ru.tkbbank.sbprouter.management.ConfigStore`).

> После этого шага `@SpringBootTest` упадёт, пока нет бина `ConfigStore` (шаг 1.7). Unit-тесты потребителей — зелёные.

- [ ] **1.7 — Бин `ConfigStore` на старте** (`management/ManagementConfig.java`):

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
                .map(s -> { log.info("Loaded runtime config override (version={})", s.version()); return s; })
                .orElseGet(() -> { log.info("No runtime override, using application.yml baseline"); return RouterConfigSnapshot.fromProperties(properties); });
        return new ConfigStore(initial);
    }
}
```

Тест `ManagementConfigTest` (Mockito):

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ManagementConfigTest {
    @Test void usesBaselineWhenNoOverride() {
        var props = new SbpRouterProperties();
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(false); props.setRouting(routing);
        var repo = mock(ConfigOverrideRepository.class); when(repo.load()).thenReturn(Optional.empty());
        var store = new ManagementConfig().configStore(props, repo);
        assertThat(store.current().routing().isTkbPayEnabled()).isFalse();
        assertThat(store.current().version()).isZero();
    }
    @Test void overrideSupersedesBaseline() {
        var props = new SbpRouterProperties();
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        var override = RouterConfigSnapshot.builder().routing(routing).version(42).build();
        var repo = mock(ConfigOverrideRepository.class); when(repo.load()).thenReturn(Optional.of(override));
        var store = new ManagementConfig().configStore(props, repo);
        assertThat(store.current().routing().isTkbPayEnabled()).isTrue();
        assertThat(store.current().version()).isEqualTo(42);
    }
}
```

- [ ] **1.8 — `ConfigService`** (`management/ConfigService.java`). *(Поле/параметр `MetricsService` добавим в Task 2 при подключении метрик — здесь конструктор без него.)*

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
        this.store = store; this.validator = validator; this.overrideRepository = overrideRepository;
    }

    public synchronized RouterConfigSnapshot updateRouting(SbpRouterProperties.Routing r, long expectedVersion) {
        return apply(expectedVersion, b -> b.routing(r));
    }
    public synchronized RouterConfigSnapshot updateTerminals(SbpRouterProperties.Terminals t, long expectedVersion) {
        return apply(expectedVersion, b -> b.terminals(t));
    }
    public synchronized RouterConfigSnapshot updateUpstreams(Map<String, SbpRouterProperties.UpstreamConfig> u, long expectedVersion) {
        return apply(expectedVersion, b -> b.upstreams(u));
    }
    public synchronized RouterConfigSnapshot updateExtractionRules(Map<String, SbpRouterProperties.ExtractionRuleSet> rules, long expectedVersion) {
        return apply(expectedVersion, b -> b.extractionRules(rules));
    }

    private RouterConfigSnapshot apply(long expectedVersion, Function<RouterConfigSnapshot.Builder, RouterConfigSnapshot.Builder> patch) {
        RouterConfigSnapshot current = store.current();
        if (current.version() != expectedVersion) throw new VersionConflictException(expectedVersion, current.version());
        RouterConfigSnapshot next = patch.apply(RouterConfigSnapshot.builder(current)).version(current.version() + 1).build();
        validator.validate(next);       // -> 400
        overrideRepository.save(next);   // -> 500, память не трогаем
        store.replace(next);             // применяем только после успешной записи
        return next;
    }
}
```

Тест `ConfigServiceTest`:

```java
package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigServiceTest {
    private Map<String, SbpRouterProperties.UpstreamConfig> upstreams() {
        var u = new SbpRouterProperties.UpstreamConfig(); u.setUrl("http://infosrv/api");
        var sv = new SbpRouterProperties.UpstreamConfig(); sv.setUrl("http://x/v");
        var sc = new SbpRouterProperties.UpstreamConfig(); sc.setUrl("http://x/c");
        return Map.of("infosrv", u, "stub-verification", sv, "stub-connector", sc);
    }
    private ConfigStore storeAtVersion(long v) {
        return new ConfigStore(RouterConfigSnapshot.builder().upstreams(upstreams()).version(v).build());
    }

    @Test void updateRoutingBumpsVersionValidatesPersistsAndApplies() {
        var store = storeAtVersion(3); var repo = mock(ConfigOverrideRepository.class);
        var service = new ConfigService(store, new ConfigValidator(), repo);
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        var result = service.updateRouting(routing, 3);
        assertThat(result.version()).isEqualTo(4);
        assertThat(store.current().routing().isTkbPayEnabled()).isTrue();
        verify(repo).save(argThat(s -> s.version() == 4));
    }
    @Test void rejectsStaleVersion() {
        var store = storeAtVersion(3); var repo = mock(ConfigOverrideRepository.class);
        var service = new ConfigService(store, new ConfigValidator(), repo);
        assertThatThrownBy(() -> service.updateRouting(new SbpRouterProperties.Routing(), 2)).isInstanceOf(VersionConflictException.class);
        verifyNoInteractions(repo);
    }
    @Test void invalidUpdateIsNotPersistedNorApplied() {
        var store = storeAtVersion(3); var repo = mock(ConfigOverrideRepository.class);
        var service = new ConfigService(store, new ConfigValidator(), repo);
        assertThatThrownBy(() -> service.updateUpstreams(Map.of(), 3)).isInstanceOf(ConfigValidationException.class);
        verifyNoInteractions(repo);
        assertThat(store.current().version()).isEqualTo(3);
    }
    @Test void persistFailureLeavesMemoryUnchanged() {
        var store = storeAtVersion(3); var repo = mock(ConfigOverrideRepository.class);
        doThrow(new RuntimeException("disk full")).when(repo).save(any());
        var service = new ConfigService(store, new ConfigValidator(), repo);
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        assertThatThrownBy(() -> service.updateRouting(routing, 3)).isInstanceOf(RuntimeException.class);
        assertThat(store.current().version()).isEqualTo(3);
        assertThat(store.current().routing().isTkbPayEnabled()).isFalse();
    }
}
```

- [ ] **1.9 — Проверка задачи.**

Run: `mvn -q test`
Expected: BUILD SUCCESS — все новые тесты зелёные, существующие unit-тесты потребителей и `GcsvcHandlerIntegrationTest` (боевой путь) тоже зелёные.

- [ ] **1.10 — Коммиты задачи 1** (логическими блоками):

```bash
git add src/main/java/ru/tkbbank/sbprouter/config/SbpRouterProperties.java config/application.yml src/test/java/ru/tkbbank/sbprouter/config/SbpRouterPropertiesTest.java
git commit -m "feat(config): add admin/history/override-path properties"
git add src/main/java/ru/tkbbank/sbprouter/config/RouterConfigSnapshot.java src/main/java/ru/tkbbank/sbprouter/management/ConfigStore.java src/main/java/ru/tkbbank/sbprouter/management/ConfigOverrideRepository.java src/test/java/ru/tkbbank/sbprouter/config/RouterConfigSnapshotTest.java src/test/java/ru/tkbbank/sbprouter/management/ConfigStoreTest.java src/test/java/ru/tkbbank/sbprouter/management/ConfigOverrideRepositoryTest.java
git commit -m "feat(management): add immutable snapshot, ConfigStore and override persistence"
git add src/main/java/ru/tkbbank/sbprouter/management/ConfigValidator.java src/main/java/ru/tkbbank/sbprouter/management/ConfigValidationException.java src/main/java/ru/tkbbank/sbprouter/management/VersionConflictException.java src/main/java/ru/tkbbank/sbprouter/management/ConfigService.java src/main/java/ru/tkbbank/sbprouter/management/ManagementConfig.java src/test/java/ru/tkbbank/sbprouter/management/ConfigValidatorTest.java src/test/java/ru/tkbbank/sbprouter/management/ConfigServiceTest.java src/test/java/ru/tkbbank/sbprouter/management/ManagementConfigTest.java
git commit -m "feat(management): add validator, ConfigService orchestration and startup wiring"
git add src/main/java/ru/tkbbank/sbprouter/extraction/XmlFieldExtractor.java src/main/java/ru/tkbbank/sbprouter/routing/TerminalDetector.java src/main/java/ru/tkbbank/sbprouter/routing/RoutingDecisionEngine.java src/main/java/ru/tkbbank/sbprouter/proxy/ProxyClient.java
git commit -m "refactor: read config from ConfigStore per request in 4 consumers"
```

---

# Task 2: Admin REST API + лента запросов + наблюдаемость

**Что делаем:** DTO + мэппер; кольцевой буфер истории и его наполнение из `GcsvcHandler`; bearer-фильтр; обработчик ошибок; контроллеры config и monitoring; метрики/лог изменений.

**Files — создать:** `management/dto/*.java` (11 records), `management/ConfigDtoMapper.java`, `history/RequestRecord.java`, `history/RequestHistoryStore.java`, `management/AdminTokenFilter.java`, `management/AdminExceptionHandler.java`, `management/AdminConfigController.java`, `management/AdminMonitoringController.java` + тесты: `management/ConfigDtoMapperTest.java`, `history/RequestHistoryStoreTest.java`, `management/AdminTokenFilterTest.java`, `management/AdminConfigControllerIntegrationTest.java`, `management/AdminMonitoringControllerIntegrationTest.java`, `proxy/GcsvcHandlerHistoryIntegrationTest.java`.
**Files — изменить:** `proxy/GcsvcHandler.java`, `observability/MetricsService.java`, `management/ConfigService.java` (+`MetricsService`).

### Шаги

- [ ] **2.1 — DTO** (пакет `ru.tkbbank.sbprouter.management.dto`, по файлу на тип):

```java
public record RoutingConfigDto(boolean tkbPayEnabled) {}
public record TerminalsConfigDto(String c2bFieldName, String b2cFieldName, String b2cPrefix, java.util.List<String> tkbPayList) {}
public record UpstreamDto(String url, Long timeoutMillis, Integer maxAttempts, Long backoffMillis) {}
public record UpstreamsConfigDto(java.util.Map<String, UpstreamDto> upstreams) {}
public record FieldRuleDto(String name, String parent, String key, String path) {}
public record ExtractionRuleSetDto(java.util.List<FieldRuleDto> routingFields, java.util.List<FieldRuleDto> extraFields) {}
public record ExtractionRulesConfigDto(java.util.Map<String, ExtractionRuleSetDto> extractionRules) {}
public record ConfigSnapshotDto(long version, String updatedAt, RoutingConfigDto routing, TerminalsConfigDto terminals,
        java.util.Map<String, UpstreamDto> upstreams, java.util.Map<String, ExtractionRuleSetDto> extractionRules) {}
public record RequestRecordDto(String timestamp, String correlationId, String requestType, String terminal,
        String terminalOwner, String sbpOperType, String routeDecision, Integer upstreamStatusCode, long durationMs, String error) {}
public record StatusDto(boolean up, long uptimeSeconds, boolean tkbPayEnabled, long configVersion, int historySize, int historyCapacity) {}
public record ErrorBody(String code, String message, String field) {}
```
(каждый — с `package ru.tkbbank.sbprouter.management.dto;` первой строкой.)

- [ ] **2.2 — `ConfigDtoMapper`** (`management/ConfigDtoMapper.java`):

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
    public SbpRouterProperties.Routing toRouting(RoutingConfigDto dto) {
        var r = new SbpRouterProperties.Routing(); r.setTkbPayEnabled(dto.tkbPayEnabled()); return r;
    }
    public SbpRouterProperties.Terminals toTerminals(TerminalsConfigDto dto) {
        var t = new SbpRouterProperties.Terminals();
        var c2b = new SbpRouterProperties.C2bTerminal(); c2b.setFieldName(dto.c2bFieldName()); t.setC2bTerminal(c2b);
        var b2c = new SbpRouterProperties.B2cTerminal(); b2c.setFieldName(dto.b2cFieldName()); b2c.setTkbPayPrefix(dto.b2cPrefix()); t.setB2cTerminal(b2c);
        t.setTkbPayList(dto.tkbPayList() != null ? List.copyOf(dto.tkbPayList()) : List.of());
        return t;
    }
    public Map<String, SbpRouterProperties.UpstreamConfig> toUpstreams(UpstreamsConfigDto dto) {
        Map<String, SbpRouterProperties.UpstreamConfig> out = new LinkedHashMap<>();
        dto.upstreams().forEach((name, u) -> {
            var cfg = new SbpRouterProperties.UpstreamConfig(); cfg.setUrl(u.url());
            if (u.timeoutMillis() != null) cfg.setTimeout(Duration.ofMillis(u.timeoutMillis()));
            var retry = new SbpRouterProperties.RetryConfig();
            if (u.maxAttempts() != null) retry.setMaxAttempts(u.maxAttempts());
            if (u.backoffMillis() != null) retry.setBackoff(Duration.ofMillis(u.backoffMillis()));
            cfg.setRetry(retry); out.put(name, cfg);
        });
        return out;
    }
    public Map<String, SbpRouterProperties.ExtractionRuleSet> toExtractionRules(ExtractionRulesConfigDto dto) {
        Map<String, SbpRouterProperties.ExtractionRuleSet> out = new LinkedHashMap<>();
        dto.extractionRules().forEach((type, set) -> {
            var rs = new SbpRouterProperties.ExtractionRuleSet();
            rs.setRoutingFields(toFieldRules(set.routingFields())); rs.setExtraFields(toFieldRules(set.extraFields()));
            out.put(type, rs);
        });
        return out;
    }
    private List<FieldRule> toFieldRules(List<FieldRuleDto> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream().map(d -> { var r = new FieldRule(); r.setName(d.name()); r.setParent(d.parent()); r.setKey(d.key()); r.setPath(d.path()); return r; }).collect(Collectors.toList());
    }

    public ConfigSnapshotDto toSnapshotDto(RouterConfigSnapshot s) {
        return new ConfigSnapshotDto(s.version(), s.updatedAt().toString(),
                new RoutingConfigDto(s.routing().isTkbPayEnabled()), toTerminalsDto(s.terminals()),
                toUpstreamsDto(s.upstreams()), toExtractionRulesDto(s.extractionRules()));
    }
    private TerminalsConfigDto toTerminalsDto(SbpRouterProperties.Terminals t) {
        return new TerminalsConfigDto(t.getC2bTerminal().getFieldName(), t.getB2cTerminal().getFieldName(),
                t.getB2cTerminal().getTkbPayPrefix(), t.getTkbPayList() != null ? List.copyOf(t.getTkbPayList()) : List.of());
    }
    private Map<String, UpstreamDto> toUpstreamsDto(Map<String, SbpRouterProperties.UpstreamConfig> ups) {
        Map<String, UpstreamDto> out = new LinkedHashMap<>();
        ups.forEach((name, cfg) -> out.put(name, new UpstreamDto(cfg.getUrl(),
                cfg.getTimeout() != null ? cfg.getTimeout().toMillis() : null,
                cfg.getRetry() != null ? cfg.getRetry().getMaxAttempts() : null,
                cfg.getRetry() != null && cfg.getRetry().getBackoff() != null ? cfg.getRetry().getBackoff().toMillis() : null)));
        return out;
    }
    private Map<String, ExtractionRuleSetDto> toExtractionRulesDto(Map<String, SbpRouterProperties.ExtractionRuleSet> rules) {
        Map<String, ExtractionRuleSetDto> out = new LinkedHashMap<>();
        rules.forEach((type, set) -> out.put(type, new ExtractionRuleSetDto(toFieldRuleDtos(set.getRoutingFields()), toFieldRuleDtos(set.getExtraFields()))));
        return out;
    }
    private List<FieldRuleDto> toFieldRuleDtos(List<FieldRule> rules) {
        if (rules == null) return List.of();
        return rules.stream().map(r -> new FieldRuleDto(r.getName(), r.getParent(), r.getKey(), r.getPath())).collect(Collectors.toList());
    }
}
```

Тест `ConfigDtoMapperTest`:

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
    @Test void mapsUpstreamDtoToConfigWithDurations() {
        var dto = new UpstreamsConfigDto(Map.of("infosrv", new UpstreamDto("http://x/api", 30000L, 2, 500L)));
        var cfg = mapper.toUpstreams(dto).get("infosrv");
        assertThat(cfg.getUrl()).isEqualTo("http://x/api");
        assertThat(cfg.getTimeout()).isEqualTo(Duration.ofMillis(30000));
        assertThat(cfg.getRetry().getMaxAttempts()).isEqualTo(2);
        assertThat(cfg.getRetry().getBackoff()).isEqualTo(Duration.ofMillis(500));
    }
    @Test void mapsExtractionRulesDtoToInternal() {
        var dto = new ExtractionRulesConfigDto(Map.of("ReqAuthPay", new ExtractionRuleSetDto(
                List.of(new FieldRuleDto("terminalName", "PayProfile", "Tran.TermName", null)),
                List.of(new FieldRuleDto("amount", null, null, "/a/b")))));
        var rule = mapper.toExtractionRules(dto).get("ReqAuthPay").getRoutingFields().get(0);
        assertThat(rule.getName()).isEqualTo("terminalName");
        assertThat(rule.isNamedBlock()).isTrue();
    }
    @Test void mapsSnapshotToDto() {
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        var dto = mapper.toSnapshotDto(RouterConfigSnapshot.builder().routing(routing).version(9).build());
        assertThat(dto.version()).isEqualTo(9);
        assertThat(dto.routing().tkbPayEnabled()).isTrue();
        assertThat(dto.updatedAt()).isNotBlank();
    }
}
```

- [ ] **2.3 — `RequestRecord` + `RequestHistoryStore`.**

`history/RequestRecord.java`:

```java
package ru.tkbbank.sbprouter.history;

import java.time.Instant;

public record RequestRecord(Instant timestamp, String correlationId, String requestType, String terminal,
        String terminalOwner, String sbpOperType, String routeDecision, Integer upstreamStatusCode, long durationMs, String error) {}
```

`history/RequestHistoryStore.java`:

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
        this.capacity = capacity; this.buffer = new ArrayDeque<>(capacity);
    }
    public synchronized void add(RequestRecord record) {
        if (buffer.size() >= capacity) buffer.pollFirst();
        buffer.addLast(record);
    }
    public synchronized List<RequestRecord> recent(int limit) {
        List<RequestRecord> out = new ArrayList<>(Math.min(limit, buffer.size()));
        Iterator<RequestRecord> it = buffer.descendingIterator();
        while (it.hasNext() && out.size() < limit) out.add(it.next());
        return out;
    }
    public synchronized int size() { return buffer.size(); }
    public int capacity() { return capacity; }
}
```

Тест `RequestHistoryStoreTest`:

```java
package ru.tkbbank.sbprouter.history;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class RequestHistoryStoreTest {
    private RequestRecord rec(String id) {
        return new RequestRecord(Instant.now(), id, "ReqAuthPay", "T", "EXTERNAL", "B2C", "infosrv", 200, 5, null);
    }
    @Test void recentReturnsMostRecentFirst() {
        var s = new RequestHistoryStore(10); s.add(rec("a")); s.add(rec("b"));
        assertThat(s.recent(10)).extracting(RequestRecord::correlationId).containsExactly("b", "a");
    }
    @Test void evictsOldestBeyondCapacity() {
        var s = new RequestHistoryStore(3); IntStream.rangeClosed(1, 5).forEach(i -> s.add(rec("r" + i)));
        assertThat(s.size()).isEqualTo(3);
        assertThat(s.recent(10)).extracting(RequestRecord::correlationId).containsExactly("r5", "r4", "r3");
    }
    @Test void recentRespectsLimit() {
        var s = new RequestHistoryStore(10); IntStream.rangeClosed(1, 5).forEach(i -> s.add(rec("r" + i)));
        assertThat(s.recent(2)).hasSize(2);
    }
    @Test void concurrentAddsKeepCapacityInvariant() throws InterruptedException {
        var s = new RequestHistoryStore(100);
        var threads = IntStream.range(0, 8).mapToObj(t -> new Thread(() -> { for (int i = 0; i < 1000; i++) s.add(rec("t" + t + "-" + i)); })).toList();
        threads.forEach(Thread::start); for (var th : threads) th.join();
        assertThat(s.size()).isEqualTo(100);
    }
}
```

- [ ] **2.4 — Наполнение истории из `GcsvcHandler`.** Добавить импорты `ru.tkbbank.sbprouter.history.RequestHistoryStore`, `...RequestRecord`, `java.time.Instant`. Добавить поле `RequestHistoryStore history` и параметр в конструктор:

```java
    private final RequestHistoryStore history;

    public GcsvcHandler(XmlFieldExtractor extractor, TerminalDetector terminalDetector,
                        RoutingDecisionEngine routingEngine, ProxyClient proxyClient,
                        ErrorResponseBuilder errorResponseBuilder, MetricsService metrics,
                        RequestHistoryStore history) {
        this.extractor = extractor; this.terminalDetector = terminalDetector; this.routingEngine = routingEngine;
        this.proxyClient = proxyClient; this.errorResponseBuilder = errorResponseBuilder; this.metrics = metrics;
        this.history = history;
    }
```

В `handle(...)` после `metrics.incrementActiveRequests();` добавить `long startNanos = System.nanoTime();`.

В блоке успеха (внутри первого `.flatMap(responseBody -> {`), перед `return ServerResponse.ok()`:
```java
                                history.add(new RequestRecord(Instant.now(), extraction.correlationId(), extraction.requestType(),
                                        extraction.field("terminalName"), owner.name(), extraction.field("sbpOperType"),
                                        decision.upstreamName(), 200, durationMs(startNanos), null));
```
В `.onErrorResume(ex -> {`, перед `String errorXml = ...`:
```java
                                history.add(new RequestRecord(Instant.now(), extraction.correlationId(), extraction.requestType(),
                                        extraction.field("terminalName"), owner.name(), extraction.field("sbpOperType"),
                                        decision.upstreamName(), null, durationMs(startNanos), ex.getMessage()));
```
В `catch (Exception e) {` (невалидный XML), перед `return ServerResponse.badRequest()`:
```java
                        history.add(new RequestRecord(Instant.now(), null, null, null, null, null, null, null,
                                durationMs(startNanos), "Invalid XML: " + e.getMessage()));
```
В конец класса:
```java
    private static long durationMs(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }
```

Тест `proxy/GcsvcHandlerHistoryIntegrationTest`:

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

    @DynamicPropertySource static void upstream(DynamicPropertyRegistry r) {
        wireMock.start(); r.add("sbp-router.upstreams.infosrv.url", () -> wireMock.baseUrl() + "/api/gcsvc");
    }
    @AfterAll static void stop() { wireMock.stop(); }
    @BeforeEach void reset() { wireMock.resetAll(); }

    @Test void recordsSuccessfulRequestInHistory() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Document><GCSvc><Payment><AnsAuthPay><Status><Code>0</Code></Status></AnsAuthPay></Payment></GCSvc></Document>")));
        int before = history.size();
        byte[] xml = getClass().getClassLoader().getResourceAsStream("test-xml/req-auth-pay-b2c.xml").readAllBytes();
        webClient.post().uri("/api/gcsvc").contentType(MediaType.APPLICATION_XML).accept(MediaType.APPLICATION_XML)
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

- [ ] **2.5 — `AdminTokenFilter`** (`management/AdminTokenFilter.java`):

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
    public AdminTokenFilter(@Value("${sbp-router.admin.token:}") String token) { this.token = token; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/admin/")) return chain.filter(exchange);
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith(PREFIX) || token.isBlank() || !auth.substring(PREFIX.length()).equals(token)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
```

Тест `AdminTokenFilterTest`:

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

    @Test void allowsNonAdminPathWithoutToken() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/gcsvc"));
        filter.filter(ex, passthrough).block();
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }
    @Test void rejectsAdminPathWithoutToken() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/config"));
        filter.filter(ex, passthrough).block();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void rejectsAdminPathWithWrongToken() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/config").header(HttpHeaders.AUTHORIZATION, "Bearer nope"));
        filter.filter(ex, passthrough).block();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void allowsAdminPathWithCorrectToken() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/config").header(HttpHeaders.AUTHORIZATION, "Bearer secret"));
        filter.filter(ex, passthrough).block();
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }
}
```

- [ ] **2.6 — `AdminExceptionHandler` + контроллеры.**

`management/AdminExceptionHandler.java`:

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

`management/AdminConfigController.java`:

```java
package ru.tkbbank.sbprouter.management;

import org.springframework.web.bind.annotation.*;
import ru.tkbbank.sbprouter.management.dto.*;

@RestController
@RequestMapping("/api/admin/config")
public class AdminConfigController {
    private final ConfigStore store;
    private final ConfigService service;
    private final ConfigDtoMapper mapper;
    public AdminConfigController(ConfigStore store, ConfigService service, ConfigDtoMapper mapper) {
        this.store = store; this.service = service; this.mapper = mapper;
    }
    @GetMapping
    public ConfigSnapshotDto get() { return mapper.toSnapshotDto(store.current()); }
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

`management/AdminMonitoringController.java`:

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
        this.history = history; this.configStore = configStore;
    }
    @GetMapping("/requests")
    public List<RequestRecordDto> requests(@RequestParam(defaultValue = "100") int limit) {
        return history.recent(limit).stream().map(r -> new RequestRecordDto(
                r.timestamp() != null ? r.timestamp().toString() : null, r.correlationId(), r.requestType(),
                r.terminal(), r.terminalOwner(), r.sbpOperType(), r.routeDecision(), r.upstreamStatusCode(), r.durationMs(), r.error())).toList();
    }
    @GetMapping("/status")
    public StatusDto status() {
        RouterConfigSnapshot snap = configStore.current();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        return new StatusDto(true, uptime, snap.routing().isTkbPayEnabled(), snap.version(), history.size(), history.capacity());
    }
}
```

Тесты `AdminConfigControllerIntegrationTest` и `AdminMonitoringControllerIntegrationTest`:

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
@TestPropertySource(properties = {"sbp-router.admin.token=test-token", "sbp-router.config.override-path=target/test-overrides.json"})
class AdminConfigControllerIntegrationTest {
    @Autowired WebTestClient client;
    @AfterEach void cleanup() throws Exception { Files.deleteIfExists(Path.of("target/test-overrides.json")); }

    private WebTestClient.RequestHeadersSpec<?> authedGet() {
        return client.get().uri("/api/admin/config").header(HttpHeaders.AUTHORIZATION, "Bearer test-token");
    }
    @Test void getRequiresToken() { client.get().uri("/api/admin/config").exchange().expectStatus().isUnauthorized(); }
    @Test void getReturnsSnapshotWithVersion() {
        authedGet().exchange().expectStatus().isOk().expectBody(ConfigSnapshotDto.class).value(s -> assertThat(s.version()).isGreaterThanOrEqualTo(0));
    }
    @Test void putRoutingTogglesFlagAndBumpsVersion() {
        ConfigSnapshotDto before = authedGet().exchange().expectStatus().isOk().expectBody(ConfigSnapshotDto.class).returnResult().getResponseBody();
        ConfigSnapshotDto after = client.put()
                .uri(u -> u.path("/api/admin/config/routing").queryParam("expectedVersion", before.version()).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").bodyValue(new RoutingConfigDto(!before.routing().tkbPayEnabled()))
                .exchange().expectStatus().isOk().expectBody(ConfigSnapshotDto.class).returnResult().getResponseBody();
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.routing().tkbPayEnabled()).isEqualTo(!before.routing().tkbPayEnabled());
    }
    @Test void putWithStaleVersionReturns409() {
        client.put().uri(u -> u.path("/api/admin/config/routing").queryParam("expectedVersion", 99999).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").bodyValue(new RoutingConfigDto(true))
                .exchange().expectStatus().isEqualTo(409);
    }
}
```

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
    @Test void requestsRequireToken() { client.get().uri("/api/admin/requests").exchange().expectStatus().isUnauthorized(); }
    @Test void statusReturnsSnapshotAndHistoryInfo() {
        client.get().uri("/api/admin/status").header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk().expectBody(StatusDto.class).value(s -> {
                    assertThat(s.up()).isTrue();
                    assertThat(s.historyCapacity()).isGreaterThan(0);
                    assertThat(s.configVersion()).isGreaterThanOrEqualTo(0);
                });
    }
    @Test void requestsReturnsListWithToken() {
        client.get().uri("/api/admin/requests?limit=5").header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk().expectBodyList(Object.class);
    }
}
```

- [ ] **2.7 — Наблюдаемость.** В `MetricsService` (открыть, взять имя поля `MeterRegistry` — вероятно `meterRegistry`) добавить:

```java
    public void recordConfigReload() { meterRegistry.counter("sbp_router_config_reloads_total").increment(); }
```

В `ConfigService` добавить логгер, параметр `MetricsService metrics` в конструктор (и поле), а в конце `apply(...)` после `store.replace(next);`:

```java
        log.info("Config reloaded: version {} -> {}", current.version(), next.version());
        metrics.recordConfigReload();
        return next;
```
(добавить `private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConfigService.class);`). Обновить `ConfigServiceTest` — передавать `mock(ru.tkbbank.sbprouter.observability.MetricsService.class)` в конструктор `ConfigService` во всех тестах.

- [ ] **2.8 — Проверка задачи.**

Run: `mvn -q test`
Expected: BUILD SUCCESS — все тесты зелёные, включая боевой путь.

- [ ] **2.9 — Коммиты задачи 2:**

```bash
git add src/main/java/ru/tkbbank/sbprouter/management/dto src/main/java/ru/tkbbank/sbprouter/management/ConfigDtoMapper.java src/test/java/ru/tkbbank/sbprouter/management/ConfigDtoMapperTest.java
git commit -m "feat(management): add admin DTOs and ConfigDtoMapper"
git add src/main/java/ru/tkbbank/sbprouter/history src/main/java/ru/tkbbank/sbprouter/proxy/GcsvcHandler.java src/test/java/ru/tkbbank/sbprouter/history src/test/java/ru/tkbbank/sbprouter/proxy/GcsvcHandlerHistoryIntegrationTest.java
git commit -m "feat(history): add request history ring buffer and populate from GcsvcHandler"
git add src/main/java/ru/tkbbank/sbprouter/management/AdminTokenFilter.java src/main/java/ru/tkbbank/sbprouter/management/AdminExceptionHandler.java src/main/java/ru/tkbbank/sbprouter/management/AdminConfigController.java src/main/java/ru/tkbbank/sbprouter/management/AdminMonitoringController.java src/test/java/ru/tkbbank/sbprouter/management/AdminTokenFilterTest.java src/test/java/ru/tkbbank/sbprouter/management/AdminConfigControllerIntegrationTest.java src/test/java/ru/tkbbank/sbprouter/management/AdminMonitoringControllerIntegrationTest.java
git commit -m "feat(management): add admin REST API (config + monitoring) with token guard"
git add src/main/java/ru/tkbbank/sbprouter/management/ConfigService.java src/main/java/ru/tkbbank/sbprouter/observability/MetricsService.java src/test/java/ru/tkbbank/sbprouter/management/ConfigServiceTest.java
git commit -m "feat(observability): log config reloads and add reload counter metric"
```

---

# Task 3: OpenAPI-контракт + финальная проверка

**Что делаем:** фиксируем контракт admin-API в `contract/` (источник правды для SP2/BFF), прогоняем весь набор и делаем дымовую проверку.

**Files — создать:** `/mnt/c/work/sbp_router/contract/back-mgmt-api.openapi.yaml`, `/mnt/c/work/sbp_router/contract/README.md`.

### Шаги

- [ ] **3.1 — `contract/back-mgmt-api.openapi.yaml`:**

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
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ConfigSnapshot' } } } }
        '401': { description: Unauthorized }
  /api/admin/config/routing:
    put:
      operationId: putRouting
      parameters: [{ name: expectedVersion, in: query, required: true, schema: { type: integer, format: int64 } }]
      requestBody: { required: true, content: { application/json: { schema: { $ref: '#/components/schemas/RoutingConfig' } } } }
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ConfigSnapshot' } } } }
        '400': { description: Validation error, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
        '409': { description: Version conflict, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
  /api/admin/config/terminals:
    put:
      operationId: putTerminals
      parameters: [{ name: expectedVersion, in: query, required: true, schema: { type: integer, format: int64 } }]
      requestBody: { required: true, content: { application/json: { schema: { $ref: '#/components/schemas/TerminalsConfig' } } } }
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ConfigSnapshot' } } } }
        '400': { description: Validation error, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
        '409': { description: Version conflict, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
  /api/admin/config/upstreams:
    put:
      operationId: putUpstreams
      parameters: [{ name: expectedVersion, in: query, required: true, schema: { type: integer, format: int64 } }]
      requestBody: { required: true, content: { application/json: { schema: { $ref: '#/components/schemas/UpstreamsConfig' } } } }
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ConfigSnapshot' } } } }
        '400': { description: Validation error, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
        '409': { description: Version conflict, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
  /api/admin/config/extraction-rules:
    put:
      operationId: putExtractionRules
      parameters: [{ name: expectedVersion, in: query, required: true, schema: { type: integer, format: int64 } }]
      requestBody: { required: true, content: { application/json: { schema: { $ref: '#/components/schemas/ExtractionRulesConfig' } } } }
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ConfigSnapshot' } } } }
        '400': { description: Validation error, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
        '409': { description: Version conflict, content: { application/json: { schema: { $ref: '#/components/schemas/ErrorBody' } } } }
  /api/admin/requests:
    get:
      operationId: getRequests
      parameters: [{ name: limit, in: query, required: false, schema: { type: integer, default: 100 } }]
      responses:
        '200': { description: OK, content: { application/json: { schema: { type: array, items: { $ref: '#/components/schemas/RequestRecord' } } } } }
        '401': { description: Unauthorized }
  /api/admin/status:
    get:
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
      properties: { tkbPayEnabled: { type: boolean } }
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
      properties: { upstreams: { type: object, additionalProperties: { $ref: '#/components/schemas/Upstream' } } }
    FieldRule:
      type: object
      properties: { name: { type: string }, parent: { type: string }, key: { type: string }, path: { type: string } }
    ExtractionRuleSet:
      type: object
      properties:
        routingFields: { type: array, items: { $ref: '#/components/schemas/FieldRule' } }
        extraFields: { type: array, items: { $ref: '#/components/schemas/FieldRule' } }
    ExtractionRulesConfig:
      type: object
      properties: { extractionRules: { type: object, additionalProperties: { $ref: '#/components/schemas/ExtractionRuleSet' } } }
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
      properties: { code: { type: string }, message: { type: string }, field: { type: string } }
```

- [ ] **3.2 — `contract/README.md`:**

```markdown
# Contracts

Источник правды для API между сервисами воркспейса.

- `back-mgmt-api.openapi.yaml` — management/monitoring API роутера (`back`).
  Реализуется в `back` (SP1, контроллеры написаны вручную по этому контракту),
  потребляется `front-bff` (SP2, клиент генерируется из этого файла).

При изменении: сперва правим yaml, затем синхронизируем реализацию back и (SP2) перегенерируем клиент BFF.
```

- [ ] **3.3 — Финальная проверка.**

```
mvn -q test                 # все тесты зелёные, боевой путь не сломан
mvn -q -DskipTests package  # jar собирается
```

Дымовая (локально, опционально):
```bash
SBP_ADMIN_TOKEN=test mvn -q spring-boot:run &
sleep 20
curl -s -H "Authorization: Bearer test" http://localhost:8080/api/admin/config | head -c 400
curl -s -H "Authorization: Bearer test" http://localhost:8080/api/admin/status
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/admin/config   # 401
# kill %1
```

- [ ] **3.4 — Коммит.** `contract/` — отдельный каталог (вне git-репо `back`); коммитить там, где он версионируется.

```bash
ls -la /mnt/c/work/sbp_router/contract/
# при необходимости — финальный коммит в back, если остались изменения:
git -C /mnt/c/work/sbp_router/back status
```

---

## Self-Review (выполнено автором плана)

- **Покрытие спеки:** снапшот+store (T1.2-1.3, 1.7); override write-through (T1.4); слияние baseline+override (T1.7); рефакторинг 4 потребителей вкл. TerminalDetector (T1.6); валидация доменов (T1.5); порядок «валидация→файл→память» + откат (T1.8); admin config GET/PUT (T2.6); оптимистичная блокировка/409 (T1.8, T2.6); лента запросов + store (T2.3, T2.4); /requests + /status (T2.6); токен/401 (T2.5); формат ошибок 400/409/500 (T2.6); лог+метрики (T2.7); OpenAPI-контракт (T3.1); регрессия (T1.9, T2.8, T3.3). ✓
- **Отступления от спеки** помечены вверху (ручные DTO; обязательные upstream'ы = 3).
- **Плейсхолдеры:** нет — везде реальный код/команды.
- **Согласованность типов:** `ConfigStore.current()/replace()`, `RouterConfigSnapshot.builder()/builder(base)/fromProperties()`, `ConfigService.update*(domain, expectedVersion)`, DTO и методы `ConfigDtoMapper` — единообразны во всех задачах. Примечание: конструктор `ConfigService` обретает параметр `MetricsService` в шаге 2.7 — тесты `ConfigServiceTest` обновляются там же.
```
