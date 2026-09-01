-- ============================================================================
-- SNRM — one-time database bootstrap
--
-- Run this ONCE, as a MySQL administrator (root), before starting the app:
--
--   MySQL Workbench : open this file, connect as root, execute the whole script
--   mysql CLI       : mysql -u root -p < db/bootstrap.sql
--
-- It creates the 'snrm' schema and the dedicated application account used by
-- the 'local' profile. It does NOT create any tables — every table is a Flyway
-- migration under src/main/resources/db/migration/, applied by the
-- application at startup.
--
-- !!! CHANGE THE PASSWORD BELOW before running. Replace both occurrences of
-- !!! 'CHANGE_ME_snrm_app_password' with a password of your own, then use that
-- !!! same value for the SNRM_DB_PASSWORD environment variable (see README,
-- !!! "Running locally"). Never commit the real password to this repository.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Schema
--    utf8mb4 so node/product names can hold any Unicode.
-- ---------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS `snrm`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 2. Application account
--    Two accounts for the same user: on Windows a JDBC connection to
--    localhost:3306 may present as either 'localhost' or '127.0.0.1' depending
--    on how MySQL resolves the client address. Creating both avoids an
--    "Access denied for user 'snrm_app'@'localhost'" surprise at startup.
-- ---------------------------------------------------------------------------
CREATE USER IF NOT EXISTS 'snrm_app'@'localhost'
    IDENTIFIED BY 'CHANGE_ME_snrm_app_password';

CREATE USER IF NOT EXISTS 'snrm_app'@'127.0.0.1'
    IDENTIFIED BY 'CHANGE_ME_snrm_app_password';

-- ---------------------------------------------------------------------------
-- 3. Grants — scoped to the 'snrm' schema only, never global.
--    DML  (SELECT/INSERT/UPDATE/DELETE)              : JPA repositories
--    DDL  (CREATE/ALTER/DROP/INDEX/REFERENCES/VIEW)  : Flyway migrations
--    LOCK TABLES                                     : Flyway's schema-history lock
--    ROUTINE/TRIGGER                                 : headroom for later migrations
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE,
      CREATE, ALTER, DROP, INDEX, REFERENCES,
      CREATE VIEW, SHOW VIEW,
      CREATE TEMPORARY TABLES, LOCK TABLES,
      CREATE ROUTINE, ALTER ROUTINE, EXECUTE, TRIGGER
    ON `snrm`.* TO 'snrm_app'@'localhost';

GRANT SELECT, INSERT, UPDATE, DELETE,
      CREATE, ALTER, DROP, INDEX, REFERENCES,
      CREATE VIEW, SHOW VIEW,
      CREATE TEMPORARY TABLES, LOCK TABLES,
      CREATE ROUTINE, ALTER ROUTINE, EXECUTE, TRIGGER
    ON `snrm`.* TO 'snrm_app'@'127.0.0.1';

FLUSH PRIVILEGES;

-- ---------------------------------------------------------------------------
-- 4. Verification — expected output is described in the README.
-- ---------------------------------------------------------------------------
SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
    FROM INFORMATION_SCHEMA.SCHEMATA
    WHERE SCHEMA_NAME = 'snrm';

SHOW GRANTS FOR 'snrm_app'@'localhost';
