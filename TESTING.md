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
`/candle`의 서버 현재 시각·숫자 가격·누락 가격 0·null 목록/행·기존 limiter 우회를 확인합니다.
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
