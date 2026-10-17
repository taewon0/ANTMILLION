# 종목 목록 일일 자동 동기화

서버가 실행 중이고 `stock.sync.enabled=true`일 때 매일 **18:00 Asia/Seoul**에 실행합니다.
주말을 포함하며 기동 직후 실행하거나 놓친 예약을 소급 실행하지 않습니다.
기본값은 비활성이며 기존 동기화 화면·실행 HTTP 경로는 제공하지 않습니다.

## 적용

1. 승인된 DB에 필요한 마이그레이션을 적용합니다. PR #12의 `001-stock-active.sql`이 적용돼 있다면 다시 실행하지 않습니다.
2. 새 `db_schema/migrations/002-stock-sync-run.sql`을 한 번 적용합니다. 기존 거래 데이터는 변경하지 않습니다. 이미 `stock_sync_run` 테이블이 있다면 재실행하지 말고 정의를 확인합니다.
3. 애플리케이션 DB 계정에 `stock_sync_run`의 SELECT/INSERT/UPDATE 권한이 있는지 확인합니다.
4. 로컬/배포용 `db.properties`에 다음 설정을 넣고 새 WAR를 배포·재시작합니다. 실제 설정 파일은 Git에 추가하지 않습니다.

```properties
stock.sync.enabled=true
stock.sync.cron=0 0 18 * * *
stock.sync.zone=Asia/Seoul
```

적용 전 백업과 대상 DB를 확인합니다. 기존 DB에 전체 `db_schema/schema.sql`을 재적용하지 않습니다.
개발·테스트에서 자동 호출을 원하지 않으면 `stock.sync.enabled=false`를 유지합니다.
cron·시간대는 시작 시 검증하며 변경 후 재시작해야 합니다. 모든 서버에서 같은 시간대 설정을 사용해야 합니다.
데이터 보존과 001 적용·rollback 주의사항은 `db_schema/migrations/README.md`를 확인합니다.

## 실행과 중복 방지

- 기존 주문/뉴스의 Spring 예약 실행과 별개인 전용 스레드 하나를 사용합니다.
- 같은 MySQL 서버·DB의 세션 락을 얻지 못하면 대기하지 않고 해당 실행을 건너뜁니다. 다운로드도 시작하지 않습니다.
- DB의 날짜별 성공 기록이 있으면 다시 다운로드·반영하지 않습니다. 서버 재시작 뒤에도 유지됩니다.
- 다운로드·파싱은 DB 변경 전에 끝냅니다. 종목 반영·잔여 주문 취소·성공 기록은 하나의 트랜잭션이며 캐시는 커밋 후 교체합니다.
- 같은 날 재시도는 성공 전까지만 허용합니다. 기본 예약은 하루 한 번이므로 실패 후 자동 재시도는 다음 날 18:00입니다. cron을 더 자주 설정해도 같은 날짜의 성공은 한 번입니다.
- 종료는 새 예약을 취소하고 실행 중인 작업 종료를 최대 30초 기다립니다. 프로세스 강제 종료 후 남은 RUNNING 기록은 다음 실행에서 이전 날짜라면 FAILED/InterruptedRun으로 정리하고, 같은 날짜라면 새 실행 ID로 재시도합니다.
- 실행 ID 조건으로 오래된 실행이 재시도의 성공 상태를 덮어쓰지 못하게 합니다. 완료 기록 실패 시 종목·주문 변경과 캐시 공개도 롤백합니다.

## 확인

활성화된 서버 시작 시 `STOCK_SYNC_SCHEDULE` 로그에 cron·시간대가 표시됩니다.
실행 시 START/SUCCESS/FAILURE/SKIP 로그에 날짜·실행 ID·집계 또는 단계·오류 종류를 남깁니다.
오류 원문·외부 응답·인증값은 기록하지 않습니다. DB 자체의 장애로 기록할 수 없으면 콘솔 로그에 남깁니다.

```sql
SELECT run_date, status, attempt_count, started_at, finished_at,
       failure_type, downloaded_count, inserted_count,
       deactivated_count, cancelled_order_count
FROM stock_sync_run
ORDER BY run_date DESC;
```

날짜별 행에는 마지막 시도의 상태·집계를 보관합니다. 재시도 성공 시 failure_type은 비워지며 이전 실패 이벤트는 로그로 확인합니다.
18시 시점에 제공된 파일을 사용하므로 그 파일의 발행 시각이나 최신 거래일을 보장하는 것은 아닙니다.
기존 대량 누락·파싱 검사를 유지하며 한 번의 미등재가 상장폐지 확정을 뜻하지 않습니다.

## 검증 범위

`scripts/Test-Trading.ps1`은 두 마이그레이션을 전용 일회성 DB에만 적용하고 합성 종목 파일 대역으로 실행합니다.
예약 기본값·명시 설정·시간대·기동 시 미실행·종료 정리, 날짜별 성공 중복·락 경합·실패 후 재시도·완료 기록 실패 롤백을 검증합니다.
실제 증권사·개발/운영 DB·전체 서버 기동은 이 테스트에 포함하지 않습니다.

StockCache는 기존 JVM 로컬 캐시입니다. DB 락/실행 기록의 중복 보호가 다중 서버의 캐시 전파까지 구현한 것은 아닙니다.
이 구현의 운영 전제는 단일 애플리케이션 서버이며, 다중 인스턴스 캐시 동기화와 이미 실행 중인 체결과의 경쟁은 별도 과제입니다.
