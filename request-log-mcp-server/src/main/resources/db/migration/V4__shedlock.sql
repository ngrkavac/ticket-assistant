-- ShedLock (RetentionPurgeJob): one row per named lock, so only one replica runs a scheduled job at
-- a time. The application user gets DML on it through MigrationUserConfig's grants callback.
-- Plain TIMESTAMP as in the ShedLock docs: with usingDbTime() the database itself writes and
-- compares the lock times in UTC.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
