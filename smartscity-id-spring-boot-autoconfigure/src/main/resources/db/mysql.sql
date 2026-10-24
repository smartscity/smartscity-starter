CREATE TABLE smartscity_id_group (
    group_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    layout VARCHAR(32) NOT NULL,
    epoch_second BIGINT NOT NULL
) ENGINE=InnoDB;
CREATE TABLE smartscity_id_worker (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    stopped_at TIMESTAMP(3) NULL,
    INDEX idx_id_worker_group (group_name)
) ENGINE=InnoDB;
