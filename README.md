# sbp-router

SBP proxy router for C2B and B2C operations. Reactive service that parses GCSvc
XML, decides the target upstream, and forwards the request. Ships with
Prometheus/Grafana monitoring.

## Stack

- Java 21
- Spring Boot 4 (WebFlux, reactive)
- Spring Cloud Config client
- Spring Cloud Vault Config
- Maven

Base package: `ru.copperside.sbprouter`.

## Configuration

`sbp-router` follows the workspace-wide [Java microservice configuration standard](../docs/standards/java-microservice-configuration-standard.md).

- `application.yml` — common defaults, `spring.config.import`, and the
  non-secret routing/extraction/upstream configuration.
- `application-local.yml` — local profile (default); disables Config Server and
  Vault unless explicitly enabled.
- `application-test.yml` — selects the shared test environment and enables
  Config Server/Vault.
- `application-prod.yml` — selects the production environment and enables
  Config Server/Vault.
- `src/test/resources/application-test.yml` — routing rules and disabled
  external sources used by automated tests.

Config Server provides the non-secret routing/extraction/upstream settings. The
service currently defines no secrets; the `optional:vault://` import wiring is
kept per the standard template so secrets can be added later without rework.

For test/prod deployments, imports should be mandatory:

```text
PAY_ENVIRONMENT=test
CONFIG_SERVER_ENABLED=true
VAULT_ENABLED=true
SPRING_CONFIG_IMPORT=configserver:${CONFIG_SERVER_URL},vault://
```

No `bootstrap.yml` is used.

### Environment variables

| Variable | Purpose | Local default |
| --- | --- | --- |
| `SERVER_PORT` | HTTP port | `8080` |
| `PAY_ENVIRONMENT` | Environment id (Config Server label, Vault path segment) | `local` |
| `CONFIG_SERVER_URL` | Config Server base URL | `http://pay-payconfig-server:8080` |
| `CONFIG_SERVER_ENABLED` | Enable Config Server client | `false` |
| `CONFIG_SERVER_LABEL` | Config Server label | `${pay.environment}` |
| `VAULT_ENABLED` | Enable Vault config | `false` |
| `INFOSRV_URL` | `infosrv` upstream URL (docker override) | baked-in default |

## Traffic publishing

When `sbp-router.kafka.enabled=true` (env `KAFKA_ENABLED`), the router publishes
the raw request and response of every proxied transaction to the configured
Kafka topic (`sbp-router.kafka.topic`, default `sbp-router-traffic`) as two
fire-and-forget events keyed by `correlationId`/`txId`, with metadata in
headers. Publishing never blocks or fails the proxied response; if the broker is
unavailable the event is dropped. Metrics: `sbp_router_kafka_published_total`
and `sbp_router_kafka_publish_errors_total` (both tagged `direction`).

## Run

### Local

The local profile starts without Config Server and Vault:

```powershell
mvn spring-boot:run
```

To exercise external configuration locally:

```powershell
$env:CONFIG_SERVER_ENABLED='true'
$env:VAULT_ENABLED='true'
$env:CONFIG_SERVER_URL='http://pay-payconfig-server:8080'
$env:SPRING_CLOUD_VAULT_TOKEN='dev-vault-token'
mvn spring-boot:run
```

### Standalone container + monitoring

`docker-compose.yml` runs the service with Prometheus and Grafana. The mounted
`config/application.yml` is a thin runtime-override layer merged on top of the
baked-in defaults.

```powershell
docker compose up -d --build
```

### Shared infra contour

The service is also part of the workspace stack in `infra/docker-compose.yaml`
(container `pay-sbp-router`, host port `8086`, profile `compose`, Config Server
enabled, Vault disabled). Start the full contour from `infra/`:

```powershell
cd ../infra
docker compose up -d --build
```

## Endpoints

- `POST /api/gcsvc` — main proxy entry point (GCSvc XML in, upstream XML out).
- `POST /stub/*` — local upstream stubs for verification/connector flows.

## Health / Observability

- Health (with liveness/readiness probes): `http://localhost:8080/actuator/health`
- Metrics: `http://localhost:8080/actuator/metrics`
- Prometheus: `http://localhost:8080/actuator/prometheus`

## Build and test

```bash
mvn clean verify
```

## Dynamic routing config (manifest consumption)

When `DYNAMIC_ROUTING_ENABLED=true`, sbp-router periodically polls
`sbp-router-management` for the latest compiled routing manifest and applies it to live
routing without a restart. The static `sbp-router:` YAML remains the bootstrap baseline and
the last-known-good fallback.

| Env var | Default | Meaning |
| --- | --- | --- |
| `DYNAMIC_ROUTING_ENABLED` | `false` | Enable manifest polling. |
| `SBP_ROUTER_MANAGEMENT_URL` | `http://sbp-router-management:8087` | Management base URL. |
| `INTERNAL_ADMIN_API_KEY` | (empty) | Shared key sent as `X-Internal-Admin-Key`. |
| `MANIFEST_POLL_INTERVAL` | `30s` | Poll interval. |

Config source of truth: the manifest when enabled and reachable; otherwise the static YAML.
On any fetch/validation failure the current snapshot is kept (no traffic interruption).
