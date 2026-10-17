-- Apply once before enabling daily stock sync. No existing trade data is modified.
CREATE TABLE stock_sync_run (
    run_date DATE NOT NULL,
    run_id CHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) DEFAULT NULL,
    failure_type VARCHAR(128) DEFAULT NULL,
    downloaded_count INT DEFAULT NULL,
    inserted_count INT DEFAULT NULL,
    deactivated_count INT DEFAULT NULL,
    cancelled_order_count INT DEFAULT NULL,
    PRIMARY KEY (run_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
