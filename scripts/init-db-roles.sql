-- Creates the three database roles (ADR-0008). Local and tests only: it runs once as the
-- PostgreSQL superuser from /docker-entrypoint-initdb.d. Passwords come from the environment,
-- never from this file.
\set ON_ERROR_STOP on

\getenv owner_password DB_OWNER_PASSWORD
\getenv app_password DB_APP_PASSWORD
\getenv system_password DB_SYSTEM_PASSWORD
\getenv db_name POSTGRES_DB

CREATE ROLE email_owner  LOGIN PASSWORD :'owner_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE email_app    LOGIN PASSWORD :'app_password'   NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
-- BYPASSRLS needs a superuser, so it is granted here and not in a Flyway migration.
-- TODO(owner-decision): if the managed PostgreSQL chosen for production does not allow BYPASSRLS,
-- switch to the ADR-0008 alternative: a policy per table "TO email_system USING (true)".
CREATE ROLE email_system LOGIN PASSWORD :'system_password' NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS;

ALTER DATABASE :"db_name" OWNER TO email_owner;
REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO email_owner, email_app, email_system;

\connect :"db_name"
ALTER SCHEMA public OWNER TO email_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
