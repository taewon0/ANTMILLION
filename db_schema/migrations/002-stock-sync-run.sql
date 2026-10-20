-- 일일 종목 동기화를 활성화하기 전에 한 번 적용합니다. 기존 거래 데이터는 변경하지 않습니다.
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
