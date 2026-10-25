CREATE TABLE smartscity_id_group (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_name VARCHAR(128) COLLATE "C" NOT NULL UNIQUE,
    layout VARCHAR(32) NOT NULL,
    epoch_second BIGINT NOT NULL,
    config_version BIGINT NOT NULL,
    worker_slots BIGINT NOT NULL,
    next_worker_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (config_version = 1),
    CHECK (worker_slots > 0),
    CHECK (next_worker_id >= 0 AND next_worker_id <= worker_slots)
);
CREATE TABLE smartscity_id_worker (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    instance_id VARCHAR(128) COLLATE "C" NOT NULL,
    registration_token VARCHAR(128) COLLATE "C" NOT NULL UNIQUE,
    application_name VARCHAR(128),
    host_ip VARCHAR(64),
    host_port INTEGER,
    hostname VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    shutdown_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_id_worker_group FOREIGN KEY (group_id) REFERENCES smartscity_id_group(id),
    CONSTRAINT uq_id_worker_number UNIQUE (group_id, worker_id),
    CONSTRAINT uq_id_worker_instance UNIQUE (group_id, instance_id),
    CHECK (worker_id >= 0),
    CHECK (status IN ('REGISTERED', 'ACTIVE', 'STOPPED', 'FAILED')),
    CHECK (host_port IS NULL OR (host_port >= 1 AND host_port <= 65535))
);
CREATE INDEX idx_id_worker_status ON smartscity_id_worker (group_id, status);
