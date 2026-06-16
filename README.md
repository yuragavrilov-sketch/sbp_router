# sbp-router

SBP flat pass-through proxy for GCSvc traffic. Reactive service that accepts a
GCSvc request, publishes it to Kafka, forwards it verbatim to a single configured
backend, publishes the backend response to Kafka, and relays that response back to
the caller. No content routing — every request goes to the same backend. Ships
with Prometheus/Grafana monitoring.

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
  non-secret backend/Kafka configuration.
- `application-local.yml` — local profile (default); disables Config Server and
  Vault unless explicitly enabled.
- `application-test.yml` — selects the shared test environment and enables
  Config Server/Vault.
- `application-prod.yml` — selects the production environment and enables
  Config Server/Vault.
- `src/test/resources/application-test.yml` — disabled external sources used by
  automated tests.

Config Server provides the non-secret backend/Kafka settings. The service
defines no secrets; the `optional:vault://` import wiring is kept per the
standard template so secrets can be added later without rework.

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
| `SBP_BACKEND_URL` | Backend URL every request is proxied to | `http://infosrv.bank.local/api/gcsvc` |
| `SBP_BACKEND_TIMEOUT` | Per-request backend timeout | `30s` |
| `SBP_BACKEND_RETRY_MAX_ATTEMPTS` | Transport-failure retry attempts | `2` |
| `SBP_BACKEND_RETRY_BACKOFF` | Retry backoff | `500ms` |

## Traffic publishing

When `sbp-router.kafka.enabled=true` (env `KAFKA_ENABLED`), the router publishes
the raw request and response of every proxied transaction to the configured
Kafka topic (`sbp-router.kafka.topic`, default `sbp-router-traffic`) as two
fire-and-forget events. Each pair is keyed by the SBP `correlationId` (the `stan`
attribute of `<Document>`), falling back to a generated `txId` when the body has
no parseable correlation id; both events of a transaction share the same key.
Headers carry `direction`, `txId`, `correlationId`, `env`, `timestamp`, and
`outcome` (response only). Publishing never blocks or fails the proxied response;
if the broker is unavailable the event is dropped. Metrics:
`sbp_router_kafka_published_total` and `sbp_router_kafka_publish_errors_total`
(both tagged `direction`).

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

- `POST /api/gcsvc` — proxy entry point (GCSvc XML in, backend response out).

## Health / Observability

- Health (with liveness/readiness probes): `http://localhost:8080/actuator/health`
- Metrics: `http://localhost:8080/actuator/metrics`
- Prometheus: `http://localhost:8080/actuator/prometheus`

## Build and test

```bash
mvn clean verify
```

> Note: content-based routing (per-payload upstream selection) and dynamic
> manifest consumption from `sbp-router-management` were removed in favour of this
> flat single-backend proxy. The richer routing variant is preserved on the
> `feature/sbp-rollout` branch.
