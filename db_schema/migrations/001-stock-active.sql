-- 새 WAR를 배포하기 전에 명시적으로 승인된 대상 DB에 한 번 적용합니다.
-- 기존 종목 행과 관련 거래 데이터는 모두 그대로 유지합니다.
ALTER TABLE stock ADD COLUMN active TINYINT(1) NOT NULL DEFAULT 1;
