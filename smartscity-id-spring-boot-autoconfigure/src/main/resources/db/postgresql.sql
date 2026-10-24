CREATE TABLE smartscity_id_group (
    group_name VARCHAR(64) PRIMARY KEY,
    layout VARCHAR(32) NOT NULL,
    epoch_second BIGINT NOT NULL
);
CREATE TABLE smartscity_id_worker (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_name VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    stopped_at TIMESTAMPTZ NULL
);
CREATE INDEX idx_id_worker_group ON smartscity_id_worker (group_name);
