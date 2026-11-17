#!/usr/bin/env bash
# Runs once, only when the central-db volume is empty (postgres image initdb hook).
# Creates one schema + one runtime role per service, and a separate database for Keycloak.
# Schema evolution afterwards belongs to each app's Flyway migrations.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE "$POSTGRES_DB" FROM PUBLIC;

CREATE ROLE ${HEALTH_DB_USER}   LOGIN PASSWORD '${HEALTH_DB_PASSWORD}';
CREATE ROLE ${IAM_DB_USER}      LOGIN PASSWORD '${IAM_DB_PASSWORD}';
CREATE ROLE ${MOVEMENT_DB_USER} LOGIN PASSWORD '${MOVEMENT_DB_PASSWORD}';

GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO ${HEALTH_DB_USER}, ${IAM_DB_USER}, ${MOVEMENT_DB_USER};

CREATE SCHEMA health   AUTHORIZATION ${HEALTH_DB_USER};
CREATE SCHEMA iam      AUTHORIZATION ${IAM_DB_USER};
CREATE SCHEMA movement AUTHORIZATION ${MOVEMENT_DB_USER};

ALTER ROLE ${HEALTH_DB_USER}   SET search_path = health;
ALTER ROLE ${IAM_DB_USER}      SET search_path = iam;
ALTER ROLE ${MOVEMENT_DB_USER} SET search_path = movement;

CREATE ROLE ${KEYCLOAK_DB_USER} LOGIN PASSWORD '${KEYCLOAK_DB_PASSWORD}';
CREATE DATABASE keycloak OWNER ${KEYCLOAK_DB_USER};
SQL
