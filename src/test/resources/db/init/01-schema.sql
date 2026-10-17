-- DDL copied from db_schema/schema.sql; no dump data or destructive statements.

-- Changes to trading tables must also be reflected here.

CREATE TABLE `ant_rank` (
  `rank_id` int NOT NULL AUTO_INCREMENT,
  `rank_type` varchar(50) DEFAULT NULL,
  `required_point` int DEFAULT NULL,
  `rank_image` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`rank_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `member` (
  `user_id` bigint NOT NULL AUTO_INCREMENT,
  `rank_id` int DEFAULT '1',
  `email` varchar(100) DEFAULT NULL,
  `password` varchar(100) DEFAULT NULL,
  `nickname` varchar(50) NOT NULL,
  `point` int NOT NULL DEFAULT '0',
  `provider` enum('LOCAL','KAKAO') NOT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_member_nickname` (`nickname`),
  UNIQUE KEY `uk_member_email` (`email`),
  KEY `fk_member_rank` (`rank_id`),
  CONSTRAINT `fk_member_rank` FOREIGN KEY (`rank_id`) REFERENCES `ant_rank` (`rank_id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `account` (
  `account_id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `account_number` varchar(50) NOT NULL,
  `balance` bigint DEFAULT '50000000',
  `account_created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`account_id`),
  UNIQUE KEY `uk_account_number` (`account_number`),
  UNIQUE KEY `uk_account_user` (`user_id`),
  CONSTRAINT `fk_account_user` FOREIGN KEY (`user_id`) REFERENCES `member` (`user_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `stock` (
  `stock_code` varchar(50) NOT NULL,
  `stock_name` varchar(100) DEFAULT NULL,
  `stock_image` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `asset` (
  `asset_id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint NOT NULL,
  `stock_code` varchar(50) NOT NULL,
  `quantity` int DEFAULT '0',
  `purchase_amount` bigint DEFAULT '0',
  `avg_price` decimal(15,2) DEFAULT '0.00',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`asset_id`),
  UNIQUE KEY `unique_account_stock` (`account_id`,`stock_code`),
  KEY `fk_asset_stock` (`stock_code`),
  CONSTRAINT `fk_asset_account` FOREIGN KEY (`account_id`) REFERENCES `account` (`account_id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_asset_stock` FOREIGN KEY (`stock_code`) REFERENCES `stock` (`stock_code`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `stock_order` (
  `order_id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint NOT NULL,
  `stock_code` varchar(50) NOT NULL,
  `transaction_type` varchar(50) DEFAULT NULL,
  `order_type` varchar(50) DEFAULT NULL,
  `quantity` int DEFAULT NULL,
  `order_price` bigint DEFAULT NULL,
  `status` varchar(50) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`order_id`),
  KEY `fk_order_account` (`account_id`),
  KEY `fk_order_stock` (`stock_code`),
  CONSTRAINT `fk_order_account` FOREIGN KEY (`account_id`) REFERENCES `account` (`account_id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_order_stock` FOREIGN KEY (`stock_code`) REFERENCES `stock` (`stock_code`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `trade_log` (
  `trade_id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `trade_date` datetime DEFAULT NULL,
  `trade_price` bigint DEFAULT NULL,
  `trade_quantity` int DEFAULT NULL,
  PRIMARY KEY (`trade_id`),
  KEY `fk_trade_order` (`order_id`),
  CONSTRAINT `fk_trade_order` FOREIGN KEY (`order_id`) REFERENCES `stock_order` (`order_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `realized_pnl` (
  `real_id` bigint NOT NULL AUTO_INCREMENT,
  `stock_code` varchar(50) NOT NULL,
  `trade_id` bigint NOT NULL,
  `account_id` bigint NOT NULL,
  `sell_date` datetime NOT NULL,
  `sell_quantity` int NOT NULL,
  `sell_amount` bigint NOT NULL,
  `cost_basis` bigint NOT NULL,
  `realized_profit` bigint NOT NULL,
  `avg_price` decimal(15,2) NOT NULL,
  PRIMARY KEY (`real_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Apply the production migration to the pre-migration stock DDL in this disposable DB.
SOURCE /opt/antmillion-migrations/001-stock-active.sql;

CREATE TABLE test_environment (marker varchar(64) NOT NULL PRIMARY KEY) ENGINE=InnoDB;

INSERT INTO test_environment (marker) VALUES ('antmillion-isolated-test-v1');

REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'antmillion_test'@'%';

GRANT SELECT, INSERT, UPDATE, DELETE ON antmillion_test.ant_rank TO 'antmillion_test'@'%';

GRANT SELECT, INSERT, UPDATE, DELETE ON antmillion_test.member TO 'antmillion_test'@'%';

GRANT SELECT, INSERT, UPDATE, DELETE ON antmillion_test.account TO 'antmillion_test'@'%';

GRANT SELECT, INSERT, UPDATE, DELETE ON antmillion_test.stock TO 'antmillion_test'@'%';

GRANT SELECT, INSERT, UPDATE, DELETE ON antmillion_test.asset TO 'antmillion_test'@'%';

GRANT SELECT, INSERT, UPDATE, DELETE ON antmillion_test.stock_order TO 'antmillion_test'@'%';

GRANT SELECT, INSERT, UPDATE, DELETE ON antmillion_test.trade_log TO 'antmillion_test'@'%';

GRANT SELECT, INSERT, UPDATE, DELETE ON antmillion_test.realized_pnl TO 'antmillion_test'@'%';

GRANT SELECT ON antmillion_test.test_environment TO 'antmillion_test'@'%';
