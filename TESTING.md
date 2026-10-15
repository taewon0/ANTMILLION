# 체결 테스트

Docker Desktop의 Linux 엔진, Java 11 이상 및 Maven이 필요합니다. 저장소 루트에서 실행합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Trading.ps1
```

실행 정책 우회는 해당 PowerShell 프로세스에만 적용됩니다.
스크립트는 `antmillion-test` Compose 프로젝트에 일회성 MySQL 8.4를 생성합니다.
개발용 Compose와 별개의 컨테이너·네트워크를 사용하며, 포트는 `127.0.0.1:13307`입니다.
DB명과 계정은 `antmillion_test`로 고정합니다. 매 실행 임의 비밀번호를 프로세스 환경변수로만
전달하며 개발용 `.env.local`·`db.properties`는 읽지 않습니다.

테스트 계정에는 거래 관련 8개 테이블의 SELECT/INSERT/UPDATE/DELETE와 환경 표식의 SELECT만
허용합니다. 스키마는 `src/test/resources/db/init/01-schema.sql`에 있는 거래 관련 DDL로
초기화하며 원본 덤프의 데이터·DROP 구문은 사용하지 않습니다. 거래 테이블 변경 시 이 DDL도
함께 갱신해야 합니다.

허용한 JDBC URL·계정 이외의 설정과 비밀번호 누락은 풀 생성 전에 거부합니다.
연결 후에는 읽기 전용 환경 표식도 확인합니다. 주소만으로 임의 DB를 테스트 대상으로
허용하지 않으며, 포트·DB명 변경은 안전장치와 Compose를 함께 검토해야 합니다.

테스트는 합성 회원·계좌·종목을 각 테스트 트랜잭션에 생성하고 롤백합니다.
계좌 ID=1이나 개발 데이터에 의존하지 않습니다. 스크립트는 기존 체결 테스트 6개와
DB 연결 차단·계정 권한 테스트를 실행하고, 실패한 테스트가 있어도 fixture가 남지 않았는지 확인합니다.
종료 시 테스트 컨테이너와 네트워크를 제거하며 MySQL 데이터는 tmpfs와 함께 폐기합니다.
실행 중인 `antmillion-test` 프로젝트가 있으면 기존 실행을 건드리지 않고 중단합니다.
강제 종료로 남았다면 다른 테스트 실행이 없는지 확인하고 해당 테스트 프로젝트를 정리합니다.

이 context는 Mapper와 TradingEngineService만 등록하며 Redis, 외부 HTTP/WS, 스케줄러를
로드하지 않습니다. 운영 Spring context의 트랜잭션 동작이나 서버 기동을 검증하지 않습니다.
기존 체결 로직의 실패가 드러나면 환경 실패와 구분하여 별도 결함으로 기록합니다.

DB 없이 연결 차단 테스트만 실행할 수도 있습니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=IsolatedTestDataSourceTest' test
```
