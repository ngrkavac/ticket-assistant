-- What a DBA sets up once per database (see LeastPrivilegeTests): a migration user that may create
-- tables in the schema, and an application user with no rights of its own.
CREATE ROLE requestlog_migrator LOGIN PASSWORD 'migrator-secret';
CREATE ROLE requestlog_app LOGIN PASSWORD 'app-secret';
GRANT CREATE, USAGE ON SCHEMA public TO requestlog_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
