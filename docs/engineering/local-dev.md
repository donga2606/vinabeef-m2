# Local runtime: research notes and decisions (DONGA-01, DONGA-02)

Owner: Đông A · Status: bootstrap, 17/11/2026

## Compose profiles and healthchecks
- Core services (`central-db`, `keycloak`) have no profile, so they start with every profile. App services use `[apps, full]` and gate services use `[gate, full]`.
- `dev.ps1` maps the script profiles: `local` → `--profile apps`, `gate` → `--profile apps --profile gate`, `full` → `--profile full`. Every `depends_on` target is inside the enabled set, so `up` never needs a service from a disabled profile.
- `up` uses `--wait`, so it returns only after every started service is healthy. A health failure exits non-zero.
- Source: https://docs.docker.com/compose/how-tos/profiles/

## PostgreSQL init
- `/docker-entrypoint-initdb.d/*.sh` runs **only when the data volume is empty**. Changing `01-init.sh` does not affect an existing volume. Use `dev reset -Force` deliberately; nothing resets automatically.
- central-db: database `vinabeef` with schemas `health`, `iam`, `movement`. Each schema is owned by its own login role (`health_app`, `iam_app`, `movement_app`) with `search_path` set, and `public` is revoked. There is also a separate `keycloak` database and role.
- edge-db is a separate server (farm failure domain): database `farm_edge`, schema `edge`, role `edge_app`.
- Schema evolution after bootstrap belongs to each app's Flyway migrations (`create-schemas: false`).
- Source: https://hub.docker.com/_/postgres (Initialization scripts)

## Keycloak in a container
- `start-dev --import-realm`. The realm JSON in `infra/oidc/realm/` is imported once, if the realm does not exist yet (it is persisted in the `keycloak` DB).
- `KC_HOSTNAME=http://localhost:8081` fixes the public issuer, and `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true` lets containers call `http://keycloak:8080`. Resource servers should validate `iss = OIDC_PUBLIC_ISSUER` and fetch keys from `OIDC_INTERNAL_JWK_SET_URI`. The Android emulator uses `adb reverse`, so it sees the same issuer.
- The health endpoint is on management port 9000 (`KC_HEALTH_ENABLED=true`). The image has no curl, so the healthcheck uses bash `/dev/tcp`.
- start-dev is a development configuration, not production.
- Source: https://www.keycloak.org/server/containers

## Network and fault boundary (gate profile)
- Networks: `central` (central-db, keycloak, herd-health, rabbitmq) and `farm-lan` (edge-db, and farm-edge later).
- `wan-proxy` (Toxiproxy) is the **only** service on both networks. Farm-side clients reach the broker at `wan-proxy:5673`, which proxies to `rabbitmq:5672`. Disabling the proxy cuts the WAN while reader→edge and edge→edge-db stay up.
- Cut and restore:
  ```bash
  curl -s -X POST localhost:8474/proxies/wan-rabbitmq -d '{"enabled":false}'
  curl -s -X POST localhost:8474/proxies/wan-rabbitmq -d '{"enabled":true}'
  ```
- One RabbitMQ node: this does not demonstrate quorum/HA.
- Source: https://github.com/Shopify/toxiproxy

## Mac ARM check (17/11)
Every pinned image publishes `linux/arm64` (checked with `docker manifest inspect`): postgres 17.7, keycloak 26.4.5, rabbitmq 4.1.4-management, toxiproxy 2.12.0, maven 3.9.11-eclipse-temurin-21, cirruslabs/flutter 3.35.7, docker 28.5.1-cli, debian 12.12-slim.
The `mcr.microsoft.com/powershell` images are no longer published, so the devshell installs the official pwsh 7.5.4 release tarball.

## Reader (DONGA-01)
- No real reader or SDK is confirmed yet, so the contract is designed around the emulator. The emulator verifies **software behaviour only**: IDs, seq gaps, retransmits, direction semantics. It says nothing about range, interference, frequency or physical ≤2 s timing.
- Contract: `contracts/events/tag-observation.v1.schema.json`. Tests check both directions (valid examples pass, invalid ones fail) and validate the emulator's output.
- `direction` exists only for GATE readers. A mobile scan never implies a passage.

## Known limits
- On Linux hosts, files the containers create under `backend/target` are owned by root. Docker Desktop on Mac/Windows maps ownership to your user.
- First build is slow: it pulls images and Maven dependencies (about 15 min on a slow link). After that the `m2-cache` volume is reused.
