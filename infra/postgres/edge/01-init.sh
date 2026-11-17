#!/usr/bin/env bash
# Runs once, only when the edge-db volume is empty. Farm-local store for farm-edge.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE "$POSTGRES_DB" FROM PUBLIC;
CREATE ROLE ${EDGE_DB_USER} LOGIN PASSWORD '${EDGE_DB_PASSWORD}';
GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO ${EDGE_DB_USER};
CREATE SCHEMA edge AUTHORIZATION ${EDGE_DB_USER};
ALTER ROLE ${EDGE_DB_USER} SET search_path = edge;
SQL
