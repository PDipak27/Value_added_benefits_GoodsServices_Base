-- Keycloak database on the LOCAL Postgres (DD-29 / §A-1). One-time, run as a
-- superuser (CREATE DATABASE cannot run inside a transaction block):
--   psql -U postgres -f deploy/postgres-init/03-keycloak-db.sql
-- Keycloak (vab-keycloak container) connects via host.docker.internal:5432/keycloak
-- as the existing 'eventuate' user and creates its own tables on first start.
CREATE DATABASE keycloak;
GRANT ALL PRIVILEGES ON DATABASE keycloak TO eventuate;
