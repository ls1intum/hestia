-- Baseline of the core module. Core tables follow later; for now only the grants.
-- Flyway runs as hestia_owner, which owns every schema; the application connects as hestia_app,
-- which may read and write the tables but not change the schema.

grant usage on schema core, ai, materials, teaching, assessment to hestia_app;

alter default privileges in schema core, ai, materials, teaching, assessment
    grant select, insert, update, delete on tables to hestia_app;

-- Hibernate draws ids from the tables' sequences.
alter default privileges in schema core, ai, materials, teaching, assessment
    grant usage, select on sequences to hestia_app;
