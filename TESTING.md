# 체결·주문 정정·종목 보존 테스트

Docker Desktop(Linux 엔진)을 실행하고 Java 11 이상과 Maven이 설치된 상태에서,
프로젝트 루트의 터미널에 아래 명령을 입력합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Trading.ps1
```

별도 DB 설정 없이 다음 과정을 자동으로 진행합니다.

1. Docker에 테스트 전용 임시 MySQL을 만들고 테이블을 준비합니다.
2. 실제 마이그레이션 파일로 `stock.active`를 추가하고 합성 회원·계좌·종목·주문으로 체결·주문 정정·종목 보존 및 DB 안전장치 테스트를 실행합니다.
3. 테스트 데이터가 롤백 또는 정리되어 남지 않았는지 확인합니다.
4. 테스트가 실패해도 임시 컨테이너와 네트워크를 제거합니다. 임시 DB 데이터도 함께 사라집니다.

기존 개발용 DB와 별도로 실행합니다. 일반 `mvn test`나 IDE 테스트 버튼은 임시 DB를 자동 생성하지 않습니다.
터미널이나 Docker를 강제 종료하면 테스트 자원이 남을 수 있습니다.

주문 정정 테스트는 인증 대역과 실제 Controller / Service / Mapper를 사용합니다.
비로그인·타 계좌·입력 오류·정정 불가 상태의 변경 차단과 본인 주문의 정상 정정을 확인합니다.
전체 보안 필터·JWT·브라우저 동작이나 동시 체결과의 경쟁을 검증하는 테스트는 아닙니다.

종목 보존 테스트는 실제 거래 테이블과 조회 Mapper를 사용하여 비활성화·주문 취소·재등장,
보유·체결·실현손익 보존, 실패 시 롤백과 캐시 공개 시점을 확인합니다.
합성 ZIP·가격 대역을 사용하며 실제 증권사·Redis·스케줄러는 실행하지 않습니다.
DB 트랜잭션으로 테스트 전체를 감싸지 않고 애플리케이션의 커밋·롤백을 확인한 뒤 합성 데이터를 정리합니다.

관심종목 Controller 테스트는 저장된 등록 정보를 유지하면서 비활성 종목 제외·재등장·페이지 보정·가격 응답 연결을 대역으로 검증합니다.
체결 조회는 취소 시각과 다른 실제 체결 시각을 반환하는지도 확인합니다.

Node.js가 있으면 체결 탭의 실제 렌더 함수를 DOM 대역으로 검증할 수 있습니다.

```powershell
node --test scripts/tests/mypage-executions.test.cjs
```

취소된 부분체결의 체결 탭 노출과 취소 표시, 정상 대기·부분·완료 주문의 필터 동작을 확인합니다.

일일 동기화 테스트는 실제 예약 스레드나 외부 다운로드를 실행하지 않습니다.
예약 등록·시간대·기동 시 미실행·종료 정리는 스케줄러 대역으로 확인하고,
DB 락·날짜별 성공 중복·실패 재시도·기록과 종목 변경의 롤백은 실제 일회성 DB에서 확인합니다.
초기화 단계에서 `002-stock-sync-run.sql`도 적용하며 실행 기록 fixture까지 정리합니다.
자동 실행 활성화 방법과 실행 상태 확인은 `STOCK_SYNC.md`를 확인합니다.

KIS 구독 소유권과 mock 개발 모드는 아래 명령으로 별도 검증합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KisSubscriptionRegistryTest,MockDevelopmentModeTest' test
node --test scripts/tests/kis-subscriptions.test.cjs scripts/tests/mock-mode.test.cjs
```

Java는 실제 Spring STOMP 프레임 처리와 메모리 이벤트·KIS 전송 대역을 사용하고,
JS는 네 화면의 실제 구독 함수를 STOMP/DOM 대역에서 실행합니다.
DB·Redis·증권사·애플리케이션 전체 설정을 로드하지 않으며, mock 자체 예약 스레드는 종료 시 정리합니다.
다중 탭의 참조 유지, 중복 해제·연결 종료, 소유권 위조 차단과 일반/mock 경로를 확인합니다.
실제 Tomcat·브라우저·SockJS 전송·KIS ACK 및 재연결 복원 검증과는 구분합니다.
