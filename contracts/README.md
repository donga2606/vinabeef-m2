# Contracts

| Contract | File | Owner | Reviewers |
|---|---|---|---|
| Reader observation (TagObservation v1) | `events/tag-observation.v1.schema.json` | Đông A | Thông, Phúc |

Examples in `examples/<contract>/` must validate; examples in `examples/<contract>/invalid/` must be rejected.
Both are checked by `./dev mvn -B verify` (reader-emulator contract tests).
Breaking change = new `vN` file + producer and consumer review in the same PR.
