# Contracts

| Contract | File | Owner | Reviewers |
|---|---|---|---|
| Reader observation (TagObservation v1) | `events/tag-observation.v1.schema.json` | Đông A | Thông, Phúc |
| Gate movement event (MovementCommitted v1) | `events/movement-committed.v1.schema.json` | Đông A | Phú, Phúc |
| Farm-edge ingress | `POST /edge/v1/observations` (body: TagObservation v1, GATE only) | Đông A | Phúc |

Examples in `examples/<contract>/` must validate; examples in `examples/<contract>/invalid/` must be rejected.
Both are checked by `./dev mvn -B verify` (reader-emulator and farm-edge contract tests).
Breaking change = new `vN` file + producer and consumer review in the same PR.
