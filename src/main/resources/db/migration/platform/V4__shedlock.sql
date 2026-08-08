-- ShedLock 7.x distributed-lock table, verbatim from the official Postgres DDL.
-- Deliberately TIMESTAMP (no time zone): usingDbTime() writes timezone('utc', CURRENT_TIMESTAMP),
-- which yields timestamp-without-time-zone values — timestamptz would break lock comparisons.

CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
