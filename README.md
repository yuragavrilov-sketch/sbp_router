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
| `CONFIG_SERVER_URL` | Config Server base URL | `http://pay-config:8080` |
| `CONFIG_SERVER_ENABLED` | Enable Config Server client | `false` |
| `CONFIG_SERVER_LABEL` | Config Server label | `${pay.environment}` |
| `VAULT_ENABLED` | Enable Vault config | `false` |
| `INFOSRV_URL` | `infosrv` upstream URL (docker override) | baked-in default |

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
$env:CONFIG_SERVER_URL='http://pay-config:8080'
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
