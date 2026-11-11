-- Run as a MySQL administrator using the mysql client (not PowerShell input redirection).
-- Edit the LOCAL_ONLY password before use. This script does not drop any data.
CREATE DATABASE IF NOT EXISTS easymall CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'easymall'@'localhost' IDENTIFIED BY 'CHANGE_ME_LOCAL_ONLY';
GRANT ALL PRIVILEGES ON easymall.* TO 'easymall'@'localhost';
-- Existing user passwords are intentionally NOT overwritten.
-- Table initialization is performed by Flyway on application startup using:
-- src/main/resources/db/migration/V1__schema.sql
-- Do not also import V1 manually into the same database: Flyway owns its history.
