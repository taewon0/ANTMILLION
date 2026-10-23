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
전체 보안 필터·JWT·브라우저 동작은 검증하지 않습니다. 동시 체결 경쟁은 아래 별도 테스트에서 확인합니다.

체결 원자성 테스트는 트랜잭션 프록시 없이 실제 체결 서비스를 직접 호출하고,
테스트 전체를 트랜잭션으로 감싸지 않습니다. 잔액 부족·매수/매도 반영 후 강제 실패·
한 호출의 후속 주문 실패 시 주문·체결·잔고·자산·손익의 롤백과 기존 트랜잭션 참여를 확인합니다.
여러 계좌의 부분/전량 체결에서 생성된 실제 체결 ID와 실현손익 참조를 검증한 뒤 합성 데이터를 정리합니다.
매도 보유 누락·수량 부족·0/null 수량 및 자산 차감·잔고 입금 0행도 실패 처리하여
같은 호출의 앞선 매도까지 롤백되는지 확인합니다. 0행은 실제 SQL에 합성 상태 변경 또는
없는 계좌 ID를 전달하여 재현하며, 정상 부분매도의 원가 절삭과 전량매도의 잔여 원가 소진도 확인합니다.

호가 방향 회귀는 실제 KIS 핸들러에 KRX·통합 호가 합성 메시지를 전달하고,
매수는 매도호가·잔량, 매도는 매수호가·잔량만 사용하는지 확인합니다.
반대 방향 미체결, 방향별 기존 선입선출·지정가 조건 및 부분/전량 체결을 검증하며,
방송은 메모리 STOMP 채널에서 확인합니다. 실제 증권사나 외부 WebSocket에는 연결하지 않습니다.
시장가 회귀는 주문 당시 가격과 다른 KRX·통합 호가에서 양방향 부분/전량 체결,
지정가와 섞인 선입선출·지정가 가격 조건, 0 이하 가격/잔량에서 DB 변경 없음,
한쪽 호가가 0일 때 반대 방향 정상 체결, 실제 체결가 기준 잔고/보유 부족의 롤백을 확인합니다.
호가 입력 자체의 중복 판별과 잔고·보유 예약은 별도 검증 범위입니다.

동일 주문 동시성 테스트는 독립 DB 연결 두 개와 실제 SQL을 사용합니다.
두 체결이 같은 후보를 읽은 뒤에도 주문 수량을 초과하지 않고 최신 체결량·매도 원가를 사용하는지,
후보 조회 후 먼저 완료된 취소·정정이 체결에 반영되는지 확인합니다.
체결이 먼저 진행되면 취소·정정은 주문 잠금 해제 후 최신 상태로 검증합니다.
서비스를 트랜잭션 프록시 없이 직접 생성하여 취소·정정의 실패 롤백과 외부 트랜잭션 참여도 확인합니다.
여러 주문을 함께 처리할 때의 순서는 생성 시각, 같은 시각이면 주문 ID 순서입니다.
서로 다른 주문 간 자산 경쟁·자금/보유 예약·중복 호가 잔량 재사용은 검증하지 않습니다.

**시장가 모의체결 정책**

- `MARKET`의 저장 `orderPrice`는 주문 당시 참고 가격이며 체결의 가격 제한으로 사용하지 않습니다.
  주문 생성 시 기존 참고 가격 기준 잔고 확인과 HTTP 필드·저장값은 유지합니다.
- 매수는 수신한 최우선 매도호가·잔량, 매도는 최우선 매수호가·잔량으로 체결합니다.
  현재가나 저장 주문가격으로 체결하지 않으며, 시장가도 기존 생성 시각 기준 선입선출에 참여합니다.
  지정가의 매수 상한·매도 하한 조건은 유지합니다.
- 0 이하 가격 또는 잔량은 해당 방향을 건너뛰며 DB를 변경하지 않습니다. 다른 방향의 유효한 호가는 계속 처리합니다.
  유효 호가의 잔량이 부족하면 부분체결 후 잔여 주문은 다음 유효 호가를 기다립니다.
- 매수는 실제 체결금액으로 잔고를 확인합니다. 부족하면 수량을 줄이거나 주문을 건너뛰지 않고
  같은 `processExecution` 호출 전체를 롤백합니다. 매도 보유 부족도 기존 정책대로 호출 전체를 롤백합니다.
  BUY/SELL은 별도 호출이며, 실제 처리 중 첫 호출의 예외가 두 번째 호출을 생략하는 기존 흐름은 유지합니다.
- 최우선 호가 한 단계만 사용하는 내부 모의체결입니다. 여러 호가 단계 소진, 시장가 우선순위,
  IOC/FOK, 자금·보유 예약 및 중복 호가 소비 방지는 추가하지 않습니다.
  동일 주문의 체결·취소·정정은 주문 행 잠금과 최신 상태 재검증으로 보호합니다.

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

KIS 연결 복원·ACK 처리는 실제 manager/handler와 합성 세션·응답·시각으로 확인합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KisWebSocketRecoveryTest' test
```

희망 참조·ACK 대기·승인 상태, 5초 응답 누락의 최대 2회 재시도(1·2초 대기),
명시적 거절·재시도 소진 후 보류, 마지막 참조 해제/재획득·재시작 후 재개를 검증합니다.
공유 참조 유지, 중복/늦은 ACK·해제 경쟁, 해제 실패 및 연결별 복원도 확인합니다.
요청 ID가 없는 응답을 구분하기 위해 timeout이나 같은 TR·종목 재획득 때 연결을 교체하며,
해제 거절/응답 누락도 연결을 교체한 뒤 남은 참조만 복원합니다. 이때 정상 구독도 잠시 중단됩니다.
위 단위 테스트는 DB·외부 API를 사용하지 않습니다. `Test-Trading.ps1`에는 ACK timeout 뒤
주문 전용 호가 참조 복원→부분/전량 체결을 실제 격리 DB로 검증하는 회귀도 포함됩니다.
실제 증권사 응답·한도·Tomcat/브라우저 동작은 별도 검증이 필요합니다.

열린 KIS 연결에서 90초 동안 메시지를 수신하지 못하면 기존 backoff로 연결을 교체하고
남은 활성 참조를 복원합니다. PINGPONG·시세·Pong 수신은 제한 시간을 갱신하며,
송신 성공만으로는 갱신하지 않습니다. 시세가 없어도 heartbeat가 오면 연결을 유지합니다.
`kisApi.properties` 또는 JVM `-Dkis.websocket.receive-timeout-millis=180000`으로
임계값(ms)을 조절할 수 있으며, ACK 제한인 5000ms보다 커야 합니다.
90초는 애플리케이션 기본 정책이며 KIS의 heartbeat 보장 주기가 아닙니다.
연결 전체의 무수신만 감지하므로 개별 종목의 시세 신선도는 별도 검증 대상입니다.
위 단위 테스트는 합성 시각으로 경계·정상 수신·이전 연결·보류/종료 회귀를 확인하고,
`Test-Trading.ps1`은 무응답 복원 후 주문 전용 호가의 부분/전량 체결도 검증합니다.

주문 가격 입력은 아래 명령으로 검증합니다.

```powershell
node --test scripts/tests/order-price.test.cjs
```

실제 주문 UI 코드를 DOM·시세 변경·HTTP 대역에서 실행하여 매수/매도 지정가 유지,
시장가 갱신·유형 전환·늦은 최초 시세와 주문 확인/전송 가격을 확인합니다.
실제 서버·브라우저·DB·증권사에는 연결하지 않습니다.

현재가 Provider 계약과 관심종목 HTTP 응답은 아래 명령으로 검증합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KisCurrentPriceProviderTest,InterestControllerTest' test
```

국내 KRX 단건·목록 조회, 기존 KIS 요청·정수/상세 캐시·10초 TTL·제한 초과 처리,
부분 실패·가격 문자열·관심종목 URL/JSON·비로그인 빈 목록을 확인합니다.
실제 KisApiService와 limiter에 HTTP·메모리 Redis 대역만 연결하며,
실제 설정·Redis·증권사·DB·전체 앱 context는 로드하지 않습니다.
`Test-Trading.ps1`에도 포함되며, 마이페이지의 비활성 종목·평가 불가·정상 평가 회귀는
공통 Provider 대역과 실제 Mapper·일회성 DB로 함께 검증합니다.

기간별 차트 Provider와 기존 차트 HTTP/화면 호환성은 아래 명령으로 검증합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KisPeriodChartProviderTest,KisPeriodChartControllerTest' test
node --test scripts/tests/period-chart.test.cjs
```

일·주·월·년봉의 KRX/수정주가 요청·한국 시각 기준 조회 기간·원본 가격 문자열·누락 값,
기존 URL/JSON·빈 목록·오류/429·캐시 타입/24시간 TTL·공유 호출 제한을 확인합니다.
실제 KisApiService·캐시 repository·limiter에 HTTP·메모리 Redis 대역만 연결합니다.
날짜가 캐시 키에 포함되지 않는 기존 한계는 유지하며, 실제 설정·DB·Redis·증권사·전체 앱 context는 로드하지 않습니다.
Java 회귀는 `Test-Trading.ps1`에도 포함됩니다. JS는 실제 차트 함수를 DOM·fetch·차트 라이브러리 대역에서 실행하며 실제 브라우저 검증과 구분합니다.

분봉 Provider와 두 HTTP 경로는 아래 명령으로 별도 검증합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KisMinuteChartProviderTest,KisMinuteChartControllerTest,KisPeriodChartProviderTest,KisPeriodChartControllerTest,KisCurrentPriceProviderTest,InterestControllerTest' test
node --test scripts/tests/minute-chart.test.cjs scripts/tests/period-chart.test.cjs scripts/tests/kis-subscriptions.test.cjs scripts/tests/mock-mode.test.cjs
```

`/stream`의 날짜·15:30 기준 요청·가격 문자열·원본 역순 반환·1분 캐시와 공유 호출 제한,
`/candle`의 서버 현재 시각·숫자 가격·누락 가격 0·null 목록/행·공유 호출 제한을 확인합니다.
최근 분봉은 요청마다 공유 예산을 소비하며 캐시·자동 재시도 없이 한도 소진 시 즉시 거절합니다.
예산 소진 시 토큰 조회·외부 HTTP 호출 차단과 실제 서비스부터 MVC까지의 429/Retry-After 응답,
다른 시세와의 예산 공유 및 원격 실패 시 예산 소비도 대역으로 검증합니다.
실제 서비스·캐시 repository·limiter에 HTTP·메모리 Redis 대역만 연결하며,
개발 설정·DB·실제 Redis/증권사·전체 앱 context는 로드하지 않습니다.
일별 분봉 캐시는 Provider·실제 요청 환경·전체 6개 조회 조건별 분리와 동일 조건 재사용을 확인합니다.
요청 날짜의 양방향 조회·기존 키/다른 Provider 키 미사용·60초 TTL·구분자/null/빈 문자열 비충돌도 검증합니다.
두 경로의 기존 시간대 정책은 유지합니다.
Java 회귀는 `Test-Trading.ps1`에도 포함되며, JS는 메인·상세 화면의 실제 분봉 소비 함수를 대역에서 검증합니다.

REST 시세 Provider 선택·라우팅은 아래 명령으로 검증합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=MarketDataProviderRegistryTest,MarketDataRoutingTest' test
```

`marketdata.provider`는 기동 시 선택하는 REST Provider 식별자입니다. 미설정 기본값은 `kis`이며,
기존 `db.properties`의 동일 키 또는 JVM 옵션 `-Dmarketdata.provider=kis`로 명시할 수 있습니다.
JVM 옵션은 파일 설정보다 우선하며, 설정 변경은 재시작 후 적용됩니다.
현재 운영 등록은 KIS만 있습니다. 빈 값·잘못된 식별자·중복 등록·필수 계약 누락은 기동 실패로 처리합니다.
선택한 Provider의 예외·null·빈 결과를 그대로 전달하며 다른 Provider로 자동 전환하지 않습니다.

| REST 계약 | KIS 지원 범위 |
|---|---|
| `CurrentPriceProvider` | 국내 KRX 현재가 단건·목록 |
| `PeriodChartProvider` | 국내 KRX 수정주가 일·주·월·년봉 |
| `MinuteChartProvider` | 국내 KRX 일별·당일 최근 분봉 |
| `MarketIndexChartProvider` | 기존 국내 시장 지수 일봉 |
| `StockRankingProvider` | 기존 거래대금순·거래량순 조회 조건 |
| `InvestorFlowProvider` | 국내 외국인·기관 순매수 가집계 |

각 등록은 계약에 정의된 모든 메서드를 지원해야 하며, 현재 애플리케이션은 여섯 계약을 모두 요구합니다.
새 Provider는 지원 계약과 adapter를 등록하고 공통 라우팅 구성으로 선택합니다.
테스트의 `alternate`는 합성 대역으로 실제 증권사 지원을 의미하지 않습니다.
선택 테스트는 실제 KIS 등록 구성·여섯 adapter와 대역 Provider를 사용해 기본 선택·아홉 메서드 위임,
설정/지원 오류·우선 주입·부모/자식 context·HTTP 응답을 확인합니다.
개발 properties·DB·Redis·HTTP/WS·전체 앱/보안 context·스케줄러는 로드하지 않습니다.
이 설정은 공통 REST 계약을 사용하는 경로에 적용됩니다. KIS 전용 직접 호출과 WebSocket에는 적용하지 않습니다.
선택 설정만으로 기존 KIS 기반 구성이나 연결의 생성을 비활성화하지는 않습니다.
Kiwoom·토큰/캐시/제한 격리·Circuit Breaker·자동 장애 전환은 후속 범위입니다.

외국인·기관 가집계 Provider와 신호등 HTTP/화면 호환성은 아래 명령으로 검증합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KisInvestorFlowProviderTest,KisInvestorSignalControllerTest' test
node --test scripts/tests/investor-signal.test.cjs
```

원본 가집계 문자열·첫 행 선택·Provider/실제 요청 환경/종목별 600초 캐시·공유 호출 제한과 기존 URL/JSON,
동반 매수/매도·혼조/0·누락 값·숫자 변환 오류·429 응답을 확인합니다.
환경 양방향 혼용 방지·동일 조건 재사용·구분자/null/빈 조건 비충돌과 기존 키 미사용도 확인합니다.
캐시 조회·HTTP 요청·저장은 같은 base URL을 사용하며 이전 키는 자연 만료시킵니다.
실제 서비스·repository·limiter는 HTTP·메모리 Redis 대역만 사용하며,
공통 신호등 서비스·독립 MVC와 세 화면의 실제 JS 함수를 대역에서 검증합니다.
개발 설정·DB·실제 Redis/증권사·전체 앱 context는 로드하지 않습니다.
Java 회귀는 `Test-Trading.ps1`에도 포함됩니다.

실시간 수신 변환과 공통 방송·체결 전달은 아래 명령으로 검증합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KisRealtimeMessageAdapterTest,RealtimeMarketDataServiceTest,KisWebSocketRecoveryTest,MarketDataRoutingTest' test
```

`KisRealtimeMessageAdapter`가 KRX/통합 현재가·호가 프레임을 공통 불변 DTO로 변환하고,
`RealtimeMarketDataReceiver`의 구현인 `RealtimeMarketDataService`가 기존 STOMP 토픽·문자열 필드를 방송합니다.
호가는 매도·매수 각각 최우선부터 5단계이며, 방송 후 BUY에 최우선 매도호가·잔량을,
SELL에 최우선 매수호가·잔량을 순서대로 전달합니다. 숫자는 trim 후 int로 변환하며
빈 값·잘못된 값·범위 초과는 기존처럼 0으로 전달합니다. BUY 실패 시 SELL은 호출하지 않습니다.
KIS 현재가의 정확히 `"0"`인 값 제외와 잘못된 프레임의 메시지별 예외 경계도 유지합니다.
Handler의 현재 세션 검사·ACK·heartbeat·복원 정책은 변경하지 않습니다.
위 테스트는 합성 프레임·공통 DTO·메모리 방송/체결 대역만 사용하며 개발 properties·DB·실제 외부 API를 로드하지 않습니다.
`Test-Trading.ps1`에는 이 회귀와 KIS 프레임 없이 공통 호가로 실제 격리 DB의 양방향 체결을 확인하는 통합 테스트도 포함됩니다.
Kiwoom·실제 서버/브라우저/증권사 수신 검증은 후속 범위입니다.

WS Provider 선택·지원 기능과 미선택 KIS의 수명 관리는 아래 명령으로 검증합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=RealtimeRoutingTest,MarketDataRoutingTest,RealtimeSubscriptionProviderTest,RealtimeMarketDataServiceTest,KisRealtimeMessageAdapterTest,KisWebSocketRecoveryTest,KisSubscriptionRegistryTest,OrderQuoteSubscriptionSchedulerTest,MockDevelopmentModeTest' test
```

`marketdata.realtime-provider`는 WS 구독 Provider를 기동 시 선택하며 미설정 기본값은 `kis`입니다.
기존 `db.properties`의 동일 키 또는 JVM 옵션 `-Dmarketdata.realtime-provider=kis`로 설정합니다.
JVM 옵션이 파일보다 우선하며 변경 후 재시작해야 합니다. REST의 `marketdata.provider`와 독립적입니다.
`RealtimeProviderRegistration`에 구독 구현과 지원 종류를 등록합니다. 현재 화면과 주문은
`CURRENT_PRICE`와 `ORDER_BOOK`이 모두 필요하므로 선택한 Provider가 하나라도 지원하지 않으면 기동에 실패합니다.
빈 값·미등록 식별자·중복 등록도 기동 실패로 처리합니다. 현재 운영 등록은 KIS뿐이며 `alternate`는 테스트 대역입니다.

| WS 종류 | KIS 지원 범위 |
|---|---|
| `CURRENT_PRICE` | 기존 KRX/통합 현재가 희망 구독·수신 |
| `ORDER_BOOK` | 기존 KRX/통합 5단계 호가 희망 구독·수신 |

선택한 구독 구현을 화면·주문 참조 Registry에 우선 주입하며 기존 STOMP 토픽과 수신 DTO/방송·체결 순서를 유지합니다.
KIS Manager는 같은 placeholder를 한 번 주입받고 미선택 시 worker·handshake executor를 시작하지 않습니다.
따라서 KIS approval/handshake도 시작하지 않습니다. `dev-mock`에서는 WS 선택·등록·소유권 bean을 제외하고 Manager를 시작하지 않습니다.
KIS REST·Redis 구성·마스터 동기화의 생성/실행은 이 WS 선택의 제어 범위가 아닙니다.
새 Provider는 자체 연결·수신·복원 수명도 선택에 맞게 제어하고, 현재 연결의 메시지를 공통 `RealtimeMarketDataReceiver`에 전달해야 합니다.
동적 전환·자동 장애 전환·Kiwoom·토큰/캐시/제한/CB 정책은 추가하지 않습니다.

테스트는 실제 선택·KIS 등록 구성과 Manager, 합성 Provider/STOMP 이벤트를 사용합니다.
선택된 KIS의 worker는 외부 구독 없이 시작/종료하며, 미선택 KIS는 희망 구독이 있어도 worker 생성·approval·handshake가 0인지 확인합니다.
개발 properties·DB·실제 Redis/증권사·전체 앱 context는 로드하지 않습니다. Java 회귀는 `Test-Trading.ps1`에도 포함됩니다.

KIS WS 실전·모의 환경의 approval·연결 일치는 아래 명령으로 검증합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KisWebSocketEnvironmentTest,KisWebSocketRecoveryTest,RealtimeRoutingTest' test
```

`kis.mode`의 기존 선택 규칙을 사용해 실전은 approval 9443 / WS 21000,
모의는 approval 29443 / WS 31000으로 연결합니다. 해당 환경의 앱 키·시크릿을 설정하고 변경 후 재시작합니다.
한 연결 시도의 환경·자격증명을 고정하여 approval 대기 중 설정이 바뀌어도 handshake와 섞이지 않습니다.
실제 발급 서비스와 Manager의 handshake URI까지 HTTP/WS 대역으로 확인하며,
환경별 장애·재연결·마지막 참조 해제·15:30/자정 전환을 검증합니다.
개발 properties·DB·Redis·실제 KIS·전체 앱 context는 로드하지 않으며 `Test-Trading.ps1`에도 포함됩니다.
모의도 기존 15:30 이후 통합·자정 KRX 전환 정책을 유지합니다. 공식 샘플의 KRX TR 모의 지원과 별개로
통합 TR의 모의 지원·실수신은 검증 필요 사항이며 이 대역 테스트로 보장하지 않습니다.
주소·TR 근거는 [공식 설정](https://github.com/koreainvestment/open-trading-api/blob/main/kis_devlp.yaml)과
[공식 WS 샘플](https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py)입니다.

KIS 액세스 토큰·발급 잠금 격리는 아래 명령으로 별도 검증합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KisAccessTokenIsolationTest' test
```

실제 서비스·저장소에 MockRestServiceServer, 메모리 Redis와 Redisson 잠금 대역만 연결합니다.
실전/모의 양방향·실제 요청 주소·앱 키/시크릿별 격리, 같은 범위 재사용·선택적 삭제,
잠금 후 재확인·획득 실패·인터럽트·HTTP/저장 실패 시 해제를 확인합니다.
한 번의 토큰 처리에서는 환경·자격증명의 같은 스냅샷을 사용하며, 응답의 expires_in TTL과 기존 잠금 대기/lease를 유지합니다.
키는 Provider·실제 base URL·앱 키·시크릿의 길이 구분 UTF-8 값을 SHA-256으로 식별하여 원문을 포함하지 않습니다.
기존 `kis:access_token`·`kis:token:lock`은 사용하지 않으며 기존 토큰은 자연 만료됩니다.
배포 후 첫 cache miss는 새 범위에서 발급을 요청하므로 구버전과 신버전의 동시 실행은 발급 잠금을 공유하지 않습니다.
실제 자격증명·외부 HTTP·Redis·분산 잠금/TTL 만료·전체 앱 기동은 검증하지 않습니다.
선제 갱신·401 무효화·발급 제한 정책은 후속 범위이며 이 Java 회귀도 `Test-Trading.ps1`에 포함됩니다.

KIS 시세 REST의 환경·계좌별 호출 제한은 아래 명령으로 검증합니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KisRestRateLimitTest,KisAccessTokenIsolationTest,KisCurrentPriceProviderTest,KisPeriodChartProviderTest,KisPeriodChartControllerTest,KisMinuteChartProviderTest,KisMinuteChartControllerTest,KisMarketIndexChartProviderTest,KisMarketIndexChartControllerTest,KisStockRankingProviderTest,KisStockRankingControllerTest,KisInvestorFlowProviderTest,KisInvestorSignalControllerTest,MarketDataProviderRegistryTest,MarketDataRoutingTest,InterestControllerTest' test
```

실제 요청 주소·계좌번호·계좌 상품코드별로 JVM의 Bucket4j 예산을 공유합니다.
앱 키/시크릿을 변경해도 같은 계좌의 예산은 유지하며, 범위 식별자는 원문을 노출하지 않는 SHA-256 값입니다.
시세 REST 한 번의 예산·토큰·헤더·주소·캐시는 같은 요청 설정 스냅샷을 사용합니다.
서버의 `kisApi.properties` 또는 JVM 시스템 속성으로 아래 값을 설정하고 재시작합니다.

| 설정 | 기본값 | 허용 범위 |
|---|---|---|
| `kis.rest.rate-limit.real-per-second` | 18 | 1~18 |
| `kis.rest.rate-limit.virtual-per-second` | 1 | 1 |

빈 값·숫자가 아닌 값·범위 초과 값이나 누락된 계좌번호/상품코드는 bean 생성 시 거부합니다.
[공식 일반 유량 안내](https://apiportal.koreainvestment.com/community/10000000-0000-0011-0000-000000000001/post/d0d1a83f-6f8d-4437-9700-6d26702fd989)의 계좌별 실전 18건/초·모의 1건/초를 상한으로 사용합니다.
신규 고객 별도 제한이 있으므로 실제 적용 한도를 확인하고 실전 값을 직접 낮춰야 합니다. 고객 상태를 자동 판단하거나 한도를 자동 상향하지 않습니다.

버킷 용량과 초당 greedy 리필량은 해당 설정값이며 기존 제한 대기·1회 재소비·캐시 fallback·429/Retry-After를 유지합니다.
호출 실패 시 예산은 환급하지 않으며, 캐시 우선 경로의 적중은 예산을 소비하지 않습니다.
대역 테스트는 고정 단조 시각으로 범위 분리·자격증명 변경·리필·동시 최초 요청을 확인하고,
기존 1회 대기의 성공/인터럽트·실제 서비스의 토큰/HTTP 차단·설정 스냅샷과 기존 REST 응답도 검증합니다.
개발 properties·DB·실제 Redis/증권사·WS·전체 앱 context는 로드하지 않습니다.
토큰/approval 발급·마스터 다운로드·다른 JVM/프로그램의 호출은 공유하지 않으며, 재시작 시 JVM 예산은 초기화됩니다.
실제 증권사의 제한 집계 방식·다른 프로그램의 동시 사용·전체 서버 기동은 별도 검증 범위입니다.
이 회귀도 `Test-Trading.ps1`에 포함됩니다.

## Kiwoom REST 인증 기반 검증 (Issue #80)

Kiwoom 인증은 기본 비활성입니다. `kiwoomApi.properties`가 없어도 인증 bean을 등록하지 않습니다.
필요할 때 `src/main/resources/kiwoomApi.properties.example`을 Git 제외 파일
`kiwoomApi.properties`로 복사하고 자격증명을 별도로 주입합니다.
설정은 Spring Environment를 사용하며 JVM/환경 설정이 파일보다 우선합니다.

| 설정 | 기본값 | 허용 범위 |
|---|---|---|
| `kiwoom.auth.enabled` | false | true / false |
| `kiwoom.mode` | virtual | real / virtual |
| `kiwoom.app-key`, `kiwoom.app-secret` | 없음 | 활성 시 필수 |
| `kiwoom.auth.refresh-buffer-seconds` | 600 | 0~43200초 |

활성화해도 기동 시 토큰 발급·Redis 조회·잠금 획득을 하지 않습니다.
내부 `KiwoomTokenService.getAccessToken()` 호출에만 인증하며 HTTP 매핑·예약 갱신은 없습니다.
REST/WS Provider로는 아직 등록하지 않았으므로 `marketdata.provider=kiwoom` 선택은 지원하지 않습니다.

[공식 au10001 명세](https://openapi.kiwoom.com/m/guide/apiguide?dummyVal=0)와
[공식 인증 샘플](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/953e5dbff123f437ab4d11a78a95191a685eb51f/kiwoom/core/auth.py)을 참고했습니다.
실전·모의의 `/oauth2/token`에 `client_credentials`·앱 키·시크릿을 전송하며,
`expires_dt`를 한국 시각으로 해석하고 기본 600초 전부터 재사용을 중단합니다.
요청이 있을 때만 재발급하며 자동 재시도·401 복구·원격 폐기는 추가하지 않습니다.
신규 응답이 이미 만료됐거나 여유 시간 이내이면 반환·저장하지 않습니다.

토큰/발급 잠금 키는 `kiwoom`·실제 주소·앱 키/시크릿의 길이 구분 SHA-256으로 격리합니다.
Redis에는 토큰과 절대 만료 시각의 JSON 문자열을 저장하고, TTL은 저장 시점의 잔여 시간에서 여유 시간을 뺍니다.
조회 시에도 절대 만료 시각을 검사하며 캐시 적중은 TTL을 연장하지 않습니다.
발급은 최대 5초 잠금 대기 → 같은 범위 재확인 → 발급/저장을 사용합니다.
고정 lease 대신 Redisson watchdog을 사용하며 소유한 잠금만 해제합니다.

다음 명령은 합성 자격증명·고정 시각·메모리 Redis·잠금/HTTP 대역만 사용합니다.
설정 테스트는 실제 Kiwoom 설정 파일도 가립니다. 개발 DB·실제 Redis/증권사·스케줄러는 로드하지 않습니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KiwoomTokenServiceTest,KiwoomAuthConfigTest' test
```

환경/자격증명별 격리·만료/재사용·동시 최초 발급·오류/인터럽트/소유권 상실·민감값 비노출과
기본 비활성/선택적 파일·잘못된 설정 거부·기존 KIS RestTemplate 우선 주입을 검증합니다.
실제 Redis 잠금/TTL·전체 앱/JDK 11 기동·Kiwoom 실연동·발급 제한·시세/WS adapter·CB는 별도 검증 범위입니다.
두 테스트는 `Test-Trading.ps1`의 격리 전체 실행 목록에도 포함됩니다.

## Kiwoom KRX 현재가 adapter 검증 (Issue #82)

`KiwoomCurrentPriceProvider`는 `CurrentPriceProvider`의 단건·목록 계약을 구현합니다.
Spring bean·운영 Provider로 등록하지 않으며, 기존 KIS 선택과 기본 비활성을 유지합니다.
`marketdata.provider=kiwoom` 전환은 지원하지 않습니다.

[공식 ka10001 명세](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/953e5dbff123f437ab4d11a78a95191a685eb51f/kiwoom/_data/kiwoom_api_spec.json)와
[공식 현재가 샘플](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/953e5dbff123f437ab4d11a78a95191a685eb51f/examples/국내주식/종목정보/get_domestic_stock_info.py)을 참고했습니다.
인증 서비스의 불변 환경과 캐시 토큰을 재사용하여 `/api/dostk/stkinfo`에 POST합니다.
헤더는 `api-id=ka10001`·Bearer 토큰이며 본문은 `stk_cd`만 전송합니다.
KRX 6자리 영문 대문자/숫자 코드를 지원하고 NXT/SOR 접미 코드는 요청 전에 거부합니다.

가격의 방향 부호를 제거하고 원 단위 정수·Integer 범위를 정확히 검사합니다.
정수와 같은 소수 표현(`71000.00`)은 허용하고 소수 원·범위 초과는 실패합니다.
등락률은 부호·소수 자릿수를 보존하며, 누락은 null·공백은 빈 문자열로 유지합니다.
단건의 누락/빈 가격은 null입니다. 목록은 입력 순서·중복을 유지하고 실패한 종목만 제외합니다.
HTTP/파싱/토큰 조회 오류는 원문·원인 예외를 노출하지 않는 고정 메시지로 전달합니다.

다음 명령은 합성 자격증명·고정 시각·메모리 Redis·잠금/HTTP 대역 및 최소 Spring context만 사용합니다.
개발 DB·실제 Redis/증권사·전체 앱/스케줄러는 로드하지 않습니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KiwoomCurrentPriceProviderTest,KiwoomTokenServiceTest,KiwoomAuthConfigTest,KisCurrentPriceProviderTest,MarketDataProviderRegistryTest,MarketDataRoutingTest,RealtimeRoutingTest' test
```

정상/누락/잘못된 응답·HTTP/통신 오류·목록 부분 실패·실전/모의 토큰과 주소 일치·DEBUG 로그 비노출,
기존 KIS 선택 및 Kiwoom 인증 활성 상태에서도 adapter 미등록을 검증합니다.
신규 회귀는 `Test-Trading.ps1`에도 포함됩니다.
Issue #82는 시세 캐시·호출 제한·retry/401 복구·운영 등록/전환·차트/WS·CB를 추가하지 않았습니다.
호출 제한은 아래 Issue #84 범위에서 연결합니다.
실제 증권사/Redis·전체 앱/JDK 11·브라우저·격리 DB 체결은 별도 검증 범위입니다.

## Kiwoom 코스피·코스닥 지수 일봉 adapter 검증 (Issue #96)

`KiwoomMarketIndexChartProvider`는 명시 주입 전용 `MarketIndexChartProvider`입니다.
공식 [ka20006 명세](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/953e5dbff123f437ab4d11a78a95191a685eb51f/kiwoom/_data/kiwoom_api_spec.json)를 기준으로
공통 코드 `0001`/`1001`을 Kiwoom `001`/`101`로 매핑하고 요청 종료일을 `base_dt`로 보냅니다.

- OHLC는 방향 부호를 제거하고 100배 정수를 포인트 단위 소수 문자열로 정확히 변환합니다. null/빈 값·null 행·중복·최신순을 보존합니다.
- 기존 KIS 지수 adapter처럼 단일 응답을 사용합니다. 연속 조회나 임의 건수 제한은 추가하지 않으며 서로의 실제 제공 건수·수치는 별도 검증 대상입니다.
- 기존 인증·전용 HTTP·실전 공유/모의 `ka20006` 예산을 재사용합니다. 한도 소진은 토큰/HTTP 전에 거절하고 실패한 요청도 예산을 소비합니다.
- 잘못된 입력/날짜·미래/역순 봉·다른 지수·누락 목록·잘못된 지수 값은 부분 반환 없이 실패합니다. 원격 메시지·토큰·파싱 예외 원문은 노출하지 않습니다.
- Spring bean·운영 Provider로 등록하지 않으며 캐시·retry/401 복구·CB·WS는 후속입니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KiwoomMarketIndexChartProviderTest,KiwoomAuthConfigTest,KiwoomRestRateLimitTest,KisMarketIndexChartProviderTest,KisMarketIndexChartControllerTest,MarketDataProviderRegistryTest,MarketDataRoutingTest,RealtimeRoutingTest' test
node --test scripts/tests/market-index-chart.test.cjs
```

합성 인증·고정 시각·메모리 Redis/잠금·MockRestServiceServer와 실제 두 adapter를 사용해 공통 DTO·기존 MVC JSON을 비교합니다.
개발 설정·DB·실제 증권사/Redis·전체 앱 context/스케줄러는 로드하지 않습니다. 설정 테스트는 실제 Kiwoom 파일을 가립니다.
실연동·실데이터 수치/거래일/조회 건수·전체 앱/브라우저·실제 JDK 11 실행은 검증 필요입니다.
`scripts/Test-Trading.ps1`의 격리 DB 전체 실행 목록에도 신규 회귀를 포함합니다.

## Kiwoom KRX 분봉 adapter 검증 (Issue #92)

`KiwoomMinuteChartProvider`는 내부 명시 주입 전용 `MinuteChartProvider`입니다.
공식 [ka10080 명세](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/953e5dbff123f437ab4d11a78a95191a685eb51f/kiwoom/_data/kiwoom_api_spec.json)의
KRX 종목·1분봉·수정주가 1·기준일자를 사용합니다. Spring bean/운영 Provider로 등록하지 않습니다.

- 일별: 요청 날짜의 15:30 이하 최신 최대 120건을 선택한 뒤 과거→최신 순으로 반환합니다. 날짜·시각과 원 단위 가격은 문자열입니다.
- 최근: 기존 서비스와 같은 JVM 기본 시간대의 현재 날짜·시각을 한 번 확보하고 당일 해당 시각 이하 최신 최대 30건을 반환합니다. 가격은 long이며 누락/빈 값은 0입니다.
- KIS처럼 단일 HTTP 조회만 수행합니다. 첫 응답이 짧거나 비어도 연속 조회로 채우지 않습니다. 누락 목록은 일별 빈 목록/최근 null이며 null 행·중복을 보존합니다.
- 기존 인증·불변 환경을 재사용합니다. 실전은 현재가/기간별 차트와 공유하고 모의는 두 분봉 경로가 ka10080 예산을 공유합니다. 한도 소진 시 토큰/HTTP 전에 즉시 실패하며 실패한 요청도 예산을 소비합니다.
- 가격 방향 부호를 제거하고 정확한 정수 원으로 정규화합니다. 잘못된 날짜/시각·역순·종목 불일치·가격/long 범위 오류는 부분 반환 없이 실패합니다. 원격 오류/토큰·파싱 예외의 원문은 노출하지 않습니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KiwoomMinuteChartProviderTest,KiwoomDailyMinuteCacheTest,KiwoomRestRateLimitTest,KisMinuteChartProviderTest,KisMinuteChartControllerTest,KiwoomPeriodChartProviderTest,KiwoomCurrentPriceProviderTest,MarketDataRoutingTest' test
```

합성 인증·고정 시각·메모리 Redis/잠금·HTTP 대역으로 실제 KIS adapter와 DTO·MVC JSON을 비교합니다.
기존 JS 분봉 소비 회귀는 `node --test scripts/tests/minute-chart.test.cjs`로 실행합니다.
실제 설정·DB·증권사·Redis·전체 앱 context/스케줄러는 로드하지 않습니다.
일별 캐시는 Issue #94에서 아래와 같이 추가했습니다. 최근 분봉 캐시·운영 등록/선택·retry/401·CB·WS는 후속입니다. 실제 두 증권사의 수정주가·봉 시각/수치·허봉·장 경계 동등성은 검증 필요입니다.

## Kiwoom 일별 분봉 캐시 검증 (Issue #94)

내부 adapter 생성 시 `KiwoomDailyMinuteRedisRepository`를 명시 주입합니다. 저장소도 Spring bean/운영 Provider로 등록하지 않습니다.

- 일별 분봉만 기존 KIS와 같은 캐시 우선·60초 TTL을 적용합니다. 적중 시 호출 예산·토큰 조회/발급·HTTP를 사용하지 않습니다. 미적중 시 단일 조회·공유 예산·소진 즉시 실패를 유지합니다.
- 키는 `kiwoom:chart:daily-minute:v1`과 실제 주소·KRX·종목·요청 날짜·1분 주기·수정주가 1·15:30 기준 시각을 길이 구분하여 구성합니다. 공개 시세에는 자격증명을 포함하지 않으며 다른 Provider/캐시와 격리합니다.
- 필터·정규화 후 과거→최신 최대 120건을 JSON 문자열로 저장합니다. 문자열 가격·큰 정수·null/빈 값·중복·null 행을 보존하고, 정상 빈 목록은 저장하며 누락 목록과 HTTP/업무/토큰/변환 실패는 저장하지 않습니다.
- Redis TTL·절대 만료를 검사하며 조회로 만료를 연장하지 않습니다. 손상·만료·조회 장애는 miss, 저장 장애는 정상 응답 유지입니다. 만료 캐시의 장애 fallback은 추가하지 않습니다. 장중 봉·빈 목록도 TTL 동안 재사용합니다.
- 최근 분봉은 캐시를 읽거나 쓰지 않으며 요청마다 기존 ka10080 예산을 소비합니다. 일별/최근 간 예산 공유를 유지합니다.
- `KiwoomDailyMinuteCacheTest`는 고정 시각·메모리 Redis/잠금·HTTP 대역으로 위 정책을 검증합니다. 기존 분봉 계약 테스트는 저장 실패 대역에서 HTTP/한도/정규화 회귀를 유지합니다. 위 분봉 명령과 `Test-Trading.ps1`에 신규 회귀를 포함했습니다.

실제 DB/Redis/증권사·전체 앱/스케줄러를 로드하지 않습니다. 실제 TTL/분산 실행·동시 miss 제어·장중 신선도 정책 개선·실데이터 동등성·실제 JDK 11·서버/브라우저·운영 등록/전환은 후속입니다.

## Kiwoom 기간별 차트 adapter 검증 (Issue #88)

`KiwoomPeriodChartProvider`는 KRX 수정주가 일·주·월·년봉의 `PeriodChartProvider` 계약을 구현합니다.
Spring bean/운영 Provider로 등록하지 않으며 기존 전역 REST/WS 선택과 기본 KIS를 유지합니다.
공식 [차트 명세](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/953e5dbff123f437ab4d11a78a95191a685eb51f/kiwoom/_data/kiwoom_api_spec.json)의
`ka10081/ka10082/ka10083/ka10094`, `/api/dostk/chart`, `upd_stkpc_tp=1`을 사용합니다.

- 기존 KIS와 같이 시세 API를 한 번만 호출합니다. 종료일을 `base_dt`로 전달하고, 응답 `dt`가 시작·종료일 양끝을 포함하는 최신 최대 100건을 반환합니다. `cont-yn`/`next-key`는 사용하지 않으며 연속 조회로 기간을 채우지 않습니다. 100건보다 적은 첫 응답도 정상 결과입니다.
- 응답의 최신순·중복·null 행·빈/누락 가격을 보존합니다. 가격은 방향 부호를 제거한 정확한 원 단위 정수 문자열로 변환하며, 소수 원·잘못된 가격/날짜·다른 종목·역순 날짜·누락 목록은 실패합니다.
- 공통 Controller의 일봉 100일·주봉 52주·월봉 48개월·년봉 30년 조회 기간을 유지합니다. 봉을 재집계하거나 날짜를 바꾸지 않습니다. [KIS 공식 예제](https://github.com/koreainvestment/open-trading-api/blob/2077773a69a428daf63e148182511f068cc45de3/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py)의 단일 호출 최대 100건을 반환 기준으로 사용합니다.
- 캐시 미적중 시 단일 시세 조회가 토큰 조회/HTTP 전에 호출 예산을 한 번 소비합니다. 실전은 현재가와 환경·앱 키별 예산을 공유하고, 모의는 TR별 1회/초를 적용합니다. 기존 현재가 예산 키와 토큰/캐시 키는 유지합니다.
- 캐시 미적중 시 한도 소진은 기존 Kiwoom 정책대로 즉시 실패합니다. 대기·retry·HTTP 매핑·401 복구·자동 전환은 추가하지 않습니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KiwoomPeriodChartProviderTest,KiwoomPeriodChartCacheTest,KiwoomCurrentPriceProviderTest,KiwoomCurrentPriceCacheTest,KiwoomRestRateLimitTest,KiwoomTokenServiceTest,KiwoomAuthConfigTest,KisPeriodChartProviderTest,KisPeriodChartControllerTest,KisCurrentPriceProviderTest,MarketDataProviderRegistryTest,MarketDataRoutingTest,RealtimeRoutingTest' test
mvn -B '-DskipTests' '-Dproject.build.sourceEncoding=UTF-8' clean package
```

실제 KIS 서비스/adapter와 Kiwoom adapter를 HTTP·메모리 Redis 대역으로 함께 실행하여 동일 Controller 요청의 기간·봉 날짜/OHLC·최신순·문자열/null과 기존 MVC JSON을 비교합니다. 연속 헤더가 있어도 추가 HTTP 없이 정상 반환하고 99/100/101건 경계를 적용하는지 검증합니다. 합성 데이터 기반 계약 비교이며 실제 증권사 간 수치·권리 조정 일치를 입증하지 않습니다.
테스트는 합성 자격증명·고정 시각·메모리 Redis/잠금·MockRestServiceServer·실제 파일을 가린 최소 Spring context를 사용합니다.
개발 DB 설정·DB·실제 Redis/증권사·전체 앱/스케줄러를 로드하지 않습니다. 실제 연동·JDK 11·서버/브라우저·분산 실행은 별도 검증 범위입니다.

## Kiwoom 기간별 차트 캐시 검증 (Issue #90)

내부 adapter 생성 시 `KiwoomPeriodChartRedisRepository`를 함께 명시 주입합니다.
캐시 저장소도 Spring bean/운영 Provider로 등록하지 않습니다.

- 기존 KIS 기간별 차트와 같이 캐시 우선·24시간 TTL을 적용합니다. 적중 시 호출 예산·토큰 조회/발급·시세 HTTP를 사용하지 않습니다. 정상 빈 목록도 적중으로 취급하며 장중 응답도 TTL 동안 재사용합니다.
- 키는 `kiwoom:chart:period:v1`과 실제 주소·KRX·종목·봉 주기·시작/종료일·수정주가 `1`을 길이 구분하여 구성합니다. 공개 차트 데이터에는 자격증명을 포함하지 않으며 KIS/현재가/토큰 키를 재사용하지 않습니다.
- 정규화와 기간 필터를 마친 최신 최대 100건을 JSON 문자열로 저장하고 순서·중복·null 행·가격의 문자열/null/빈 값을 보존합니다. 정상 빈 목록은 저장하되 HTTP/업무/토큰/변환 실패는 저장하지 않습니다.
- Redis TTL과 저장한 절대 만료 시각을 검사하며 조회로 TTL을 연장하지 않습니다. 만료·손상·조회 장애는 miss, 저장 장애는 정상 응답 유지로 처리합니다. 미적중 시 기존 단일 조회·공유 한도·소진 즉시 실패를 유지합니다.
- `KiwoomPeriodChartCacheTest`는 고정 시각·메모리 Redis/잠금·HTTP 대역으로 TTL 경계·조건 격리·직렬화·손상/장애·적중 시 예산/토큰/HTTP 미사용과 기존 응답 호환성을 확인합니다. 기존 adapter 계약 회귀는 차트 캐시 저장 불가 대역에서 기존 HTTP/제한/정규화 동작을 계속 검증합니다.

위 기간별 차트 명령과 `Test-Trading.ps1`에 신규 회귀를 포함했습니다.
실제 Redis/증권사·전체 앱/JDK 11·브라우저·분산 실행은 별도 검증 범위입니다.
동시 miss 제어·장중 신선도 개선·retry/401·CB·운영 등록/전환은 후속입니다.

## Kiwoom 현재가 REST 호출 제한 검증 (Issue #84)

내부 adapter 생성 시 동일한 `KiwoomRestRateLimiter`를 전달합니다. Spring에서는 전용 singleton으로
등록하며, 현재가 adapter 자체는 계속 bean·운영 Provider로 등록하지 않습니다.
제한 클래스 생성·기동 시 토큰 조회·HTTP·Redis 호출은 없습니다.

[공식 이용안내](https://openapi2.kiwoom.com/intro?dummyVal=0)의 국내주식 조회 실전 5회/초,
모의 계좌·토큰별 TR당 1회/초를 기준으로 다음 설정을 사용합니다.

```properties
kiwoom.rest.rate-limit.real-per-second=5
kiwoom.rest.rate-limit.virtual-per-second=1
```

실전은 1~5, 모의는 1만 허용하며 잘못된 값은 bean 생성 시 거부합니다.
이번 공유 범위는 사용자 선택에 따른 **환경·앱 키별 JVM 예산**입니다. 길이 구분 SHA-256으로
식별하며 시크릿/토큰 교체로 초기화하지 않습니다. 같은 계좌의 여러 앱 키·다른 JVM/프로그램 간
공유는 지원하지 않으므로 운영 연결 전에 계좌별 공유 범위를 보완해야 합니다.
실전 키는 국내주식 조회, 모의 키는 `ka10001`을 구분하며 현재 적용 경로는 현재가 TR뿐입니다.

기존 KIS와 독립적인 Bucket4j greedy 예산(capacity와 초당 refill은 설정값)을 사용합니다.
이는 원격 서버의 시간 구간 집계나 HTTP 전송 간격을 보장하지 않습니다. 예산은 자동 퇴거하지 않고
프로세스 재시작 시 초기화됩니다. 각 유효 종목은 토큰 조회 전에 소비하며 이후 실패해도 환급하지 않습니다.
소진 시 단건은 대기/재시도 없이 `KiwoomRateLimitExceededException`과 대기 시간을 전달합니다.
목록은 한도에 걸린 항목도 기존 부분 실패처럼 제외합니다. HTTP 오류 응답 매핑은 추가하지 않습니다.

```powershell
mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=KiwoomRestRateLimitTest,KiwoomCurrentPriceProviderTest,KiwoomTokenServiceTest,KiwoomAuthConfigTest,KisRestRateLimitTest,KisCurrentPriceProviderTest,MarketDataProviderRegistryTest,MarketDataRoutingTest,RealtimeRoutingTest' test
```

고정 단조 시각·합성 토큰·메모리 Redis·잠금/HTTP 대역·최소 Spring context로 동시성·리필 경계·
공유/격리·소진 시 토큰/HTTP 차단·기존 응답 계약을 검증합니다. 기존 응답 계약 회귀는 요청마다
합성 시각을 전진시켜 충분히 리필하고, 호출 제한 회귀는 고정 시각으로 한도를 확인합니다.
개발 설정·DB·실제 Redis/증권사·전체 앱/스케줄러는 로드하지 않습니다.
신규 회귀를 `Test-Trading.ps1`에도 등록합니다. 발급 제한·캐시·retry/401·운영 등록/전환·차트/WS·CB는 후속입니다.
