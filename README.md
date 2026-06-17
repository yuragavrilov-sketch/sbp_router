# sbp-router

SBP reactive proxy for GCSvc traffic. Accepts a GCSvc request, publishes it to
Kafka, forwards it to the active backend group using round-robin load-balancing
with per-request failover and per-backend circuit-breaking, publishes the
backend response to Kafka, and relays the response back to the caller. The
upstream status, headers, and body are relayed verbatim; hop-by-hop and caller
credentials are stripped. Ships with Prometheus/Grafana monitoring.

## Stack

- Java 21
- Spring Boot 4 (WebFlux, reactive)
- Spring Cloud Config client
- Spring Cloud Vault Config
- Maven

Base package: `ru.copperside.sbprouter`.

## Backend groups, round-robin, and circuit-breaker

Traffic is routed to the **active group** — a named, ordered list of backend
URLs. All requests go to the active group; no content-based selection is done.

**Round-robin:** within the active group each new request starts at the next
backend (rotating cursor), so load is spread evenly under normal conditions.

**Per-request failover:** on a transport error (timeout or connection failure)
the router immediately tries the next backend in the candidate list, up to
`failover.max-attempts` distinct backends per request. A backend that returns
any HTTP status (including 4xx/5xx) is a success — only transport errors
trigger failover.

**Circuit-breaker:** each backend has an independent failure counter. After
`circuit-breaker.failure-threshold` consecutive transport errors the backend is
banned for `circuit-breaker.ban-duration` and excluded from the candidate list.
After the ban expires the backend re-enters rotation automatically. If every
backend in the active group is banned, one half-open probe is sent to the
backend whose ban expires soonest, so the group never becomes a permanent
black-hole.

**All-fail outcome:** when all K attempts fail, the router returns
504 Gateway Timeout (if the last failure was a response timeout) or
502 Bad Gateway, with a GCSvc error XML body.

**Managed routing-config:** groups, backends, and the active group are managed
**centrally** by `sbp-router-management`; the router does not switch them itself.
When `SBP_ROUTING_CONFIG_ENABLED=true` the router consumes the managed
routing-config from Kafka and atomically rebuilds its backend registry (see
*Managed routing-config consumption* below).

**State:** load-balancing and ban state are per-instance, in-memory. Each pod
balances and bans independently; there is no cross-replica coordination.

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

### Routing configuration

```yaml
sbp-router:
  active-group: ${SBP_ACTIVE_GROUP:default}   # active group at startup
  groups:
    default:
      backends:
        - ${SBP_BACKEND_URL:http://infosrv.bank.local/api/gcsvc}
    # secondary:                               # optional DR / blue-green group
    #   backends:
    #     - http://dr-infosrv.bank.local/api/gcsvc
  timeout: ${SBP_BACKEND_TIMEOUT:30s}         # per-attempt response timeout
  failover:
    max-attempts: ${SBP_FAILOVER_MAX:2}       # K: distinct backends tried per request
  circuit-breaker:
    failure-threshold: ${SBP_CB_THRESHOLD:3}  # N consecutive transport errors → ban
    ban-duration: ${SBP_CB_BAN:30s}           # cooldown before re-entering rotation
```

The default config ships a single group named `default` whose backend is
`${SBP_BACKEND_URL}` — fully backward-compatible with existing compose and
corporate deployments. Add more groups and backends via Config Server.

This bootstrap/static config (`sbp-router.groups`, `sbp-router.active-group`,
`SBP_BACKEND_URL`) is used until the first managed routing-config arrives, or
whenever `SBP_ROUTING_CONFIG_ENABLED=false`. Backward compatibility is preserved.

### Environment variables

| Variable | Purpose | Local default |
| --- | --- | --- |
| `SERVER_PORT` | HTTP port | `8080` |
| `PAY_ENVIRONMENT` | Environment id (Config Server label, Vault path segment) | `local` |
| `CONFIG_SERVER_URL` | Config Server base URL | `http://pay-payconfig-server:8080` |
| `CONFIG_SERVER_ENABLED` | Enable Config Server client | `false` |
| `CONFIG_SERVER_LABEL` | Config Server label | `${pay.environment}` |
| `VAULT_ENABLED` | Enable Vault config | `false` |
| `SBP_BACKEND_URL` | URL of the single backend in the default group | `http://infosrv.bank.local/api/gcsvc` |
| `SBP_BACKEND_TIMEOUT` | Per-attempt response timeout | `30s` |
| `SBP_ACTIVE_GROUP` | Active backend group at startup | `default` |
| `SBP_FAILOVER_MAX` | Max distinct backends tried per request (K) | `2` |
| `SBP_CB_THRESHOLD` | Consecutive transport errors before ban (N) | `3` |
| `SBP_CB_BAN` | Ban duration (cooldown) | `30s` |
| `SBP_ROUTING_CONFIG_ENABLED` | Consume managed routing-config from Kafka and rebuild the backend registry | `false` |
| `SBP_ROUTING_CONFIG_TOPIC` | Managed routing-config topic (compacted) | `sbp-router-routing-config` |
| `SBP_HEARTBEAT_ENABLED` | Publish a fleet heartbeat (presence + metrics) to Kafka | `false` |
| `SBP_HEARTBEAT_TOPIC` | Heartbeat topic | `sbp-router-heartbeat` |
| `SBP_HEARTBEAT_INTERVAL` | Heartbeat interval | `15s` |

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
  Accepts a trailing slash (`/api/gcsvc/`) and any request content type.

Observability endpoints (health/info/metrics/prometheus) stay open for probes
and scraping.

## Managed routing-config consumption & fleet heartbeat

When `SBP_ROUTING_CONFIG_ENABLED=true`, groups, backends, and the active group
are managed centrally by `sbp-router-management` and delivered on a compacted
Kafka topic (`SBP_ROUTING_CONFIG_TOPIC`, default `sbp-router-routing-config`).
The payload is `{ version, activeGroup, groups: { <name>: { backends: [url] } } }`.
Each pod consumes the topic with a **unique** consumer group (broadcast — every
replica receives every update and a freshly-started pod replays the latest from
earliest), deduplicates by `version` (only `version > applied` is applied), and
atomically rebuilds the `BackendGroupRegistry` via a single-snapshot swap. Each
reconfig **resets per-backend ban state**. Until the first managed config
arrives (or when disabled), the bootstrap/static config is used.

When `SBP_HEARTBEAT_ENABLED=true`, each pod publishes a periodic heartbeat
(`sbp-router-heartbeat`, key = instance id) with `{instanceId, startedAt,
activeGroup, groups[], backends[], routingConfigVersion, metrics{}}` so the
management service can show the running fleet and its metrics. `routingConfigVersion`
is the applied routing-config version of the registry; `sbp-router-management`
consumes the heartbeat for its fleet-view.

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
