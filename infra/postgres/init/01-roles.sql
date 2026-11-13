-- Creates the two roles of the Hestia database. The postgres image runs this once, on the first
-- start with an empty data directory (/docker-entrypoint-initdb.d), as the superuser, in the
-- database POSTGRES_DB. The passwords come from the environment of the postgres container.
--
-- hestia_owner owns the database and the module schemas and is used only by Flyway.
-- hestia_app is the application's login: it may read and write rows (grants in V1__core_baseline.sql).

\getenv owner_password HESTIA_OWNER_PASSWORD
\getenv app_password HESTIA_APP_PASSWORD

create role hestia_owner login password :'owner_password';
create role hestia_app login password :'app_password';

-- As the database owner, hestia_owner may create the module schemas and the Flyway history in public.
alter database :"DBNAME" owner to hestia_owner;
