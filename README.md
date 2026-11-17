# VinaBeef M2

Monorepo for the VinaBeef M2 prototype (backend, mobile, contracts, infra, docs, evidence).
See `docs/engineering/local-dev.md` for how the runtime is put together.

## Only prerequisite: Docker

Docker Desktop (or Engine) with Compose v2. JDK, Maven, Flutter SDK and PowerShell all run in containers.
The only exception is the **Android emulator / device**, which has to run on the host (Android Studio or SDK tools).

| You type | Runs where |
|---|---|
| `./dev …` (macOS/Linux) | host `pwsh` if installed, otherwise a `vinabeef-devshell` container (built automatically the first time) |
| `pwsh scripts/dev.ps1 …` (Windows) | host PowerShell 7 |

## First run

```bash
cp .env.example .env          # only if .env does not exist yet
./dev doctor                  # checks Docker, .env keys, ports, compose validity
./dev up -Profile local       # central-db + keycloak + herd-health, waits for healthchecks
curl http://localhost:8080/actuator/health
./dev down                    # stops everything, keeps data
```

The first `up`/`mvn` run downloads images and Maven dependencies, which takes a few minutes. Later runs reuse the `m2-cache` volume.

## Profiles

| `-Profile` | Services | Use |
|---|---|---|
| `infra` | central-db, keycloak | infra readiness only |
| `local` (default) | + herd-health | mobile thin slice |
| `gate` | + edge-db, farm-edge, rabbitmq, wan-proxy (Toxiproxy), movement-alert | gate passages, WAN cut and replay |
| `full` | everything | demo |

## Commands

```bash
./dev ps | ./dev logs [service]
./dev mvn -B verify                       # backend build + tests (Java 21 / Maven 3.9 in container)
./dev emulator --kind GATE --count 5      # TagObservation v1 JSON lines; --help for options
./dev flutter --version                   # Flutter SDK in container (mobile/ created by Thông)
./dev reset -Profile local -Force         # DELETES db/broker volumes; build caches kept
```

Hot reload: while `herd-health` runs, saving a file under `backend/apps/herd-health/src/main` triggers a recompile inside the container, and Spring devtools then restarts the app. No image rebuild is needed.

## Gate: cattle in/out (DONGA-03/04)

```
emulator ─POST─▶ farm-edge ─▶ edge-db (observation, passage, presence/count, outbox)   ← one transaction
                     └─ outbox relay ─▶ wan-proxy ─▶ RabbitMQ ─▶ movement-alert ─▶ central-db (movement)
```

```bash
./dev up -Profile gate
./dev emulator --count 6 --tags COW-1,COW-2,COW-3 --post http://farm-edge:8080/edge/v1/observations
curl -s localhost:8090/edge/v1/summary              # farm count, passages, outbox pending/sent
curl -s localhost:8091/movement/v1/farms/FARM-01/summary   # central projection
curl -s -X POST localhost:8474/proxies/wan-rabbitmq -d '{"enabled":false}'   # cut WAN (true = restore)
```

- **farm-edge** decides what a read means (`PassageClassifier`). A repeat read of the same passage within 5 s is merged. A genuine OUT followed by IN is never merged. A direction that contradicts the known presence is kept and flagged as an anomaly. A read older than the latest passage is kept for review. A retransmit with the same `observationId` returns its original receipt.
- The receipt is a **local** outcome. Central is updated later by the relay, which marks an event sent only after the broker confirms it.
- **movement-alert** ACKs only after its transaction commits. It ignores duplicate `eventId`s and older `edgeSeq`s. `missingEdgeSeqs` shows gaps.
- Lab-only fault for T04: `curl -X POST 'localhost:8091/lab/v1/faults/halt-after-commit?commits=30'` crashes movement-alert after the 30th commit and before its ACK. The container restarts by itself.
- **Limits:** no machine authentication on the gate ingress yet (farm LAN plus loopback only). A single broker means no HA. Unknown tags start OUTSIDE, because there is no approved initial-state import yet. Gate processing is serialised per farm (prototype: one gate).

## Ports (host)

| Port | What |
|---|---|
| 8080 | herd-health API (no gateway in P0) |
| 8081 | Keycloak. Issuer `http://localhost:8081/realms/vinabeef`. Admin `admin` / see `.env` |
| 127.0.0.1:15672 | RabbitMQ management (gate) |
| 127.0.0.1:8474 | Toxiproxy admin API (gate) |
| 127.0.0.1:8090 | farm-edge: `POST /edge/v1/observations`, `GET /edge/v1/summary`, `GET /edge/v1/tags/{tagId}` (gate) |
| 127.0.0.1:8091 | movement-alert: `GET /movement/v1/farms/{farmId}/summary`, `/tags/{tagId}` (gate) |

Android emulator: run `adb reverse tcp:8080 tcp:8080 && adb reverse tcp:8081 tcp:8081` so `localhost` and the OIDC issuer are the same as on the host.

## Ownership

`compose.yaml`, `infra/`, `scripts/dev.ps1`, `dev`, `contracts/events/*`, `backend/apps/farm-edge`, `backend/apps/movement-alert`, `backend/tools/reader-emulator`: Đông A (Phú reviews build).
`backend/apps/herd-health` is a skeleton for Khánh: it boots, connects as `health_app` to its own `health` schema, and exposes `/actuator/health`. It has **no business endpoints yet**.
`infra/oidc/realm/vinabeef-realm.json` is a placeholder dev realm (synthetic users `bs.demo` / `fm.demo`) until Phú's realm lands.

## Not done yet

- `seed` command: waits for Khánh's migrations.
- Security wiring (Phú), Flutter app (Thông), CI (Phú).
- Gate: machine identity for readers, initial-count import, multi-farm/HA topology: Design-only.
