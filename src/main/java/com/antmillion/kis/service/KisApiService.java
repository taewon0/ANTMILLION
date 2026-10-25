package com.antmillion.kis.service;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisWebSocketConfig;
import com.antmillion.kis.constant.KisApiConstant;
import com.antmillion.kis.dto.*;
import com.antmillion.kis.exception.KisMarketDataException;
import com.antmillion.marketdata.resilience.MarketDataCircuitBreakers;
import com.antmillion.marketdata.resilience.MarketDataCircuitBreakers.Provider;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisChartRedisRepository;
import com.antmillion.kis.repository.KisFrgnOrgnRedisRepository;
import com.antmillion.kis.repository.KisMarketIndexChartRedisRepository;
import com.antmillion.kis.repository.KisStockVolumeRankRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Service
public class KisApiService {

    private final KisConfig config;
    private final KisWebSocketConfig kisWebSocketConfig;
    private final RestTemplate restTemplate;
    private final KisAccessTokenRedisRepository kisAccessTokenRedisRepository;
    private final KisChartRedisRepository kisChartRedisRepository;
    private final KisMarketIndexChartRedisRepository kisMarketIndexChartRepository;
    private final KisStockVolumeRankRedisRepository kisStockVolumeRankRedisRepository;
    private final KisFrgnOrgnRedisRepository kisFrgnOrgnRedisRepository;

    // Redisson 분산 락
    private final RedissonClient redissonClient;
    // Bucket4j으로 한국투자증권 api 유량 제어
    private final RateLimitedKisApiClient rateLimitedClient;
    private final MarketDataCircuitBreakers circuitBreakers;

    private <T> T callCurrentPrice(KisRestRequestScope scope, String cacheKey, Supplier<T> remoteCall) {
        try (MarketDataCircuitBreakers.Call call = circuitBreakers.acquire(Provider.KIS, scope.getBaseUrl())) {
            return rateLimitedClient.callWithCache(scope, cacheKey, () -> call.execute(remoteCall), 10);
        }
    }

    private KisRestRequestScope requestScope() {
        return new KisRestRequestScope(config.getBaseUrl(), config.accountNo, config.acntPrdtCd,
                config.appKey, config.appSecret);
    }

    private String getMarketDataAccessToken(KisAccessTokenScope scope) {
        try {
            return getKisAccessToken(scope);
        } catch (RuntimeException e) {
            throw KisMarketDataException.tokenAccess("KIS 시세 토큰 조회에 실패했습니다.");
        }
    }

    /** 시세 HTTP만 분류하며 호출 제한·캐시·토큰 발급 자체의 정책은 변경하지 않습니다. */
    private <T> T exchangeMarketData(String url, HttpEntity<Void> request, Class<T> responseType,
                                     Function<T, String> returnCode, String message) {
        T body;
        try {
            body = restTemplate.exchange(url, HttpMethod.GET, request, responseType).getBody();
        } catch (RestClientException e) {
            throw KisMarketDataException.fromHttp(e, message);
        }
        if (body == null) throw KisMarketDataException.invalidResponse(message);
        KisMarketDataException.checkReturnCode(returnCode.apply(body), message);
        return body;
    }

    /**
     * KIS 액세스 토큰 조회/발급
     * Redis에 유효한 토큰이 있으면 반환, 없으면 신규 발급 후 Redis에 저장
     */
    public String getKisAccessToken() {
        return getKisAccessToken(new KisAccessTokenScope(config.getBaseUrl(), config.appKey, config.appSecret));
    }

    private String getKisAccessToken(KisAccessTokenScope scope) {
        // Redis에서 토큰 조회
        Optional<String> saved = kisAccessTokenRedisRepository.findAccessToken(scope);
        if (saved.isPresent()) {
            log.info("기존 토큰 재사용");
            return saved.get();
        }

        // 분산 락 획득
        RLock lock = redissonClient.getLock(scope.getLockKey());

        try {
            // 락 획득 시도: 최대 5초 대기, 획득 후 10초간 유지
            boolean isLocked = lock.tryLock(5, 10, TimeUnit.SECONDS);

            if (!isLocked) {
                log.warn("토큰 발급 락 획득 실패 - 다른 스레드가 발급 중");
                // 락 획득 실패 시 재시도 (다른 스레드가 발급 완료 후 저장했을 가능성)
                Thread.sleep(100); // 짧은 대기
                Optional<String> retried = kisAccessTokenRedisRepository.findAccessToken(scope);
                if (retried.isPresent()) {
                    log.info("다른 스레드가 발급한 토큰 사용");
                    return retried.get();
                }
                throw new RuntimeException("토큰 발급 락 획득 실패");
            }

            // 2차 체크: 락 획득 후 다시 한 번 Redis 확인 (Double-Checked Locking)
            Optional<String> doubleChecked = kisAccessTokenRedisRepository.findAccessToken(scope);
            if (doubleChecked.isPresent()) {
                log.info("락 획득 후 확인: 기존 토큰 재사용");
                return doubleChecked.get();
            }

            // 신규 발급
            log.info("토큰 신규 발급");
            KisAccessTokenResponse response = issueAccessTokenAPI(scope);

            // Redis 저장 (TTL 자동 설정)
            kisAccessTokenRedisRepository.save(
                    scope,
                    response.getAccessToken(),
                    response.getExpiresIn()
            );
            log.info("토큰 신규 발급 완료 및 저장");
            return response.getAccessToken();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("토큰 발급 중 인터럽트 발생", e);
            throw new RuntimeException("토큰 발급 중 인터럽트 발생", e);
        } finally {
            // 락 해제
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("토큰 발급 락 해제");
            }
        }
    }

    /**
     * 신규 접근 토큰 발급
     * 24시간 유효, 6시간마다 재발급 가능, api 호출 시 1분간 재호출 제한
     * @return 접근 토큰, 유효시간
     */
    private KisAccessTokenResponse issueAccessTokenAPI(KisAccessTokenScope scope) {
        String url = scope.getBaseUrl() + KisApiConstant.OAUTH_TOKEN_PATH;
        KisAccessTokenRequest body = new KisAccessTokenRequest();
        body.setAppkey(scope.getAppKey());
        body.setAppsecret(scope.getAppSecret());
        HttpHeaders headers = new HttpHeaders();

        HttpEntity<KisAccessTokenRequest> request =
                new HttpEntity<>(body, headers);

        ResponseEntity<KisAccessTokenResponse> response = restTemplate.postForEntity(url, request, KisAccessTokenResponse.class);
        return response.getBody();
    }
    
    /**
     * KIS 웹소켓 키 발급 요청
     */
    public String getKisApprovalKey() {
        return getKisApprovalKey(webSocketConnectionScope());
    }

    public KisWebSocketConnectionScope webSocketConnectionScope() {
        return kisWebSocketConfig.connectionScope();
    }

    public String getKisApprovalKey(KisWebSocketConnectionScope scope) {
        KisWebSocketResponse response = issueWebSocketAPI(scope);
        return response.getApprovalKey();
    }
    
    /**
     * KIS 웹소켓 키 발급
     * 24시간 유효, 세션 연결 시 초기 1회만 사용하기 때문에
     * 접속키 인증 후에는 세션 종료되지 않는 이상 접속키 신규 발급받지 않아도 365일 내내 웹소켓 데이터 수신 가능
     */
    private KisWebSocketResponse issueWebSocketAPI(KisWebSocketConnectionScope scope) {
        String url = scope.getBaseUrl() + KisApiConstant.WEB_SOCKET_PATH;
        KisWebSocketRequest body = new KisWebSocketRequest();
        body.setAppkey(scope.getAppKey());
        body.setSecretkey(scope.getAppSecret());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON); // JSON 콘텐츠 유형과 UTF-8 인코딩
        HttpEntity<KisWebSocketRequest> request =
                new HttpEntity<>(body, headers);
        ResponseEntity<KisWebSocketResponse> response = restTemplate.postForEntity(url, request, KisWebSocketResponse.class);
        if(response.getBody() != null) {
            log.info("KIS WebSocket approval 발급 성공");
        }
        return response.getBody();
    }
    
    /**
     * 국내주식 기간별 시세 조회
     * @param request  조회 조건 (종목코드, 기간, 날짜 등)
     * @return 캔들 차트 데이터 리스트
     */
    public List<ChartStockPrice> getPeriodStockPrices(ChartStockPriceRequest request) {
        KisRestRequestScope scope = requestScope();
        String baseUrl = scope.getBaseUrl();
        //Redis에 있는지 먼저 확인
        Optional<List<ChartStockPrice>> cached = kisChartRedisRepository.getChartData(baseUrl, request);

        if (cached.isPresent()) {
            log.info("기간별 차트 데이터 재사용");
            return cached.get();
        }

        log.info("한국투자증권 기간별 시세 api 호출");
        KisChartStockPriceResponse response = periodStockPricesAPI(scope, request);
        kisChartRedisRepository.save(baseUrl, request, response.getOutput2());
        return response.getOutput2();
    }

    /**
     * 한국투자증권 국내주식 기간별 시세 조회 API 호출
     * @param request  조회 조건 (종목코드, 기간, 날짜 등)
     * @return 한국투자증권 API 반환값
     */
    private KisChartStockPriceResponse periodStockPricesAPI(KisRestRequestScope scope, ChartStockPriceRequest request) {
        try (MarketDataCircuitBreakers.Call call = circuitBreakers.acquire(Provider.KIS, scope.getBaseUrl())) {
            // Rate Limit + 캐싱 적용
            return rateLimitedClient.callWithCache(
                    scope,
                    null,
                    () -> call.execute(() -> {
                        //1.접근 토큰 얻기
                        String token = getMarketDataAccessToken(scope.getTokenScope());
                        //2.헤더 설정
                        HttpHeaders headers = createApiHeader(token, scope, "FHKST03010100");
                        //3.URL 생성
                        String url = buildPeriodApiUrl(scope.getBaseUrl(), request);
                        //4.API 호출
                        HttpEntity<Void> httpEntity = new HttpEntity<>(headers);
                        KisChartStockPriceResponse responseBody = exchangeMarketData(url, httpEntity, KisChartStockPriceResponse.class,
                                KisChartStockPriceResponse::getReturnCode, "기간별 차트 데이터 조회 실패");
                        if (responseBody.getOutput2() != null) {
                            return responseBody;
                        }
                        throw KisMarketDataException.invalidResponse("기간별 차트 데이터 조회 실패");
                    }),
                    60 // TTL 60초
            );
        }
    }

    /**
     * 한국투자증권 국내 주식 기간별 시세 조회 API URL 생성
     * @param request  조회 조건 (종목코드, 기간, 날짜 등)
     * @return 한국투자증권 API URL
     */
    private String buildPeriodApiUrl(String baseUrl, ChartStockPriceRequest request) {
        final URI uri = URI.create(baseUrl + KisApiConstant.PERIOD_PRICE_PATH);
        return UriComponentsBuilder
                .fromUri(uri)
                .queryParam("FID_COND_MRKT_DIV_CODE", request.getMarketCode())
                .queryParam("FID_INPUT_ISCD", request.getStockCode())
                .queryParam("FID_INPUT_DATE_1", request.getStartDate())
                .queryParam("FID_INPUT_DATE_2", request.getEndDate())
                .queryParam("FID_PERIOD_DIV_CODE", request.getPeriodCode())
                .queryParam("FID_ORG_ADJ_PRC", request.getAdjPrice())
                .build().toUriString();
    }

    /**
     * 한국투자증권 API 호출을 위한 헤더 생성
     * @param token 접근 토큰
     * @param trId 거래 id
     * @return 한국투자증권 api 요청 헤더
     */
    private HttpHeaders createApiHeader(String token, KisRestRequestScope scope, String trId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("content-type", "application/json; charset=utf-8");
        headers.setBearerAuth(token);
        headers.set("appkey", scope.getTokenScope().getAppKey());
        headers.set("appsecret", scope.getTokenScope().getAppSecret());
        headers.set("tr_id", trId);
        headers.set("custtype", "P");
        return headers;

    }

    /**
     * 시장 지수 차트 데이터 조회
     * @param request 조회 조건 (종목코드, 기간, 날짜 등)
     * @return 코스피, 코스닥 캔들 차트 데이터 리스트
     */
    public List<MarketIndexPrice> getMarketIndexPrices(MarketIndexPriceRequest request) {
        KisRestRequestScope scope = requestScope();
        String baseUrl = scope.getBaseUrl();
        Optional<List<MarketIndexPrice>> cached = kisMarketIndexChartRepository.getChartData(baseUrl, request);
        if (cached.isPresent()) {
            log.info("{} 차트 데이터 재사용",  request.getIndexCode());
            return cached.get();
        }

        log.info("한국투자증권 국내업종 일자별지수 api 호출");
        KisMarketIndexPriceResponse response = marketIndexPricesAPI(scope, request);
        kisMarketIndexChartRepository.save(baseUrl, request, response.getOutput2());
        return response.getOutput2();
    }

    /**
     * 한국투자증권 국내업종 일자별지수 API 호출
     * @param request 조회 조건 (종목코드, 기간, 날짜 등)
     * @return 한국투자증권 api 반환값
     */
    private KisMarketIndexPriceResponse marketIndexPricesAPI(KisRestRequestScope scope, MarketIndexPriceRequest request) {
        try (MarketDataCircuitBreakers.Call call = circuitBreakers.acquire(Provider.KIS, scope.getBaseUrl())) {
            return rateLimitedClient.callWithoutCache(
                    scope,
                    () -> call.execute(() -> {
                        String token = getMarketDataAccessToken(scope.getTokenScope());
                        HttpHeaders headers = createApiHeader(token, scope, "FHPUP02120000");
                        String url = buildMarketIndexApiUrl(scope.getBaseUrl(), request);
                        HttpEntity<Void> httpEntity = new HttpEntity<>(headers);
                        KisMarketIndexPriceResponse responseBody = exchangeMarketData(url, httpEntity, KisMarketIndexPriceResponse.class,
                                KisMarketIndexPriceResponse::getReturnCode, "국내 시장 지수 차트 데이터 조회 실패");
                        if (responseBody.getOutput2() != null) {
                            return responseBody;
                        }
                        throw KisMarketDataException.invalidResponse("국내 시장 지수 차트 데이터 조회 실패");
                    })
            );
        }
    }

    /**
     * 한국투자증권 국내업종 일자별지수 API URL 생성
     * @param request 조회 조건 (종목코드, 기간, 날짜 등)
     * @return 한국투자증권 국내업종 일자별지수 API URL
     */
    private String buildMarketIndexApiUrl(String baseUrl, MarketIndexPriceRequest request) {
        final URI uri = URI.create(baseUrl + KisApiConstant.MARKET_INDEX_PATH);
        return UriComponentsBuilder
                .fromUri(uri)
                .queryParam("FID_PERIOD_DIV_CODE", request.getPeriodCode())
                .queryParam("FID_COND_MRKT_DIV_CODE", request.getMarketCode())
                .queryParam("FID_INPUT_ISCD", request.getIndexCode())
                .queryParam("FID_INPUT_DATE_1", request.getEndDate())
                .build().toUriString();
    }
    	
    /***
     * 한국투자증권 국내업종 당일분봉조회
     * @param request 조회 조건 (분류코드, 종목코드, 시간 등)
     * @return 당일분봉조회 데이터 리스트
     */
    public List<DayMinutePrice> getDayMinutePrices(DayMinutePriceRequest request){
    	DayMinutePriceResponse response = dayMinutePricesAPI(request);
    	return response.getOutput2();
    	// Redis에 있는지 확인하고 반환, 만약 Redis에 데이터가 하나도 없으면 그때만 한투 api 직접호출
    }
    /**
     * 한국투자증권 국내업종 당일분봉조회 API 호출
     * @param request 조회 조건 (분류코드, 종목코드, 시간 등)
     * @return 당일분봉조회 데이터 리스트
     * 
     */
    private DayMinutePriceResponse dayMinutePricesAPI(DayMinutePriceRequest request) {
        KisRestRequestScope scope = requestScope();
        // 캐시 없는 최근 분봉도 다른 KIS 시세 조회와 호출 예산을 공유합니다.
        try (MarketDataCircuitBreakers.Call call = circuitBreakers.acquire(Provider.KIS, scope.getBaseUrl())) {
            return rateLimitedClient.callWithoutCache(scope, () -> call.execute(() -> {
                String token = getMarketDataAccessToken(scope.getTokenScope());
                HttpHeaders headers = createApiHeader(token, scope, "FHKST03010200");
                String url = buildDayMinuteApiUrl(scope.getBaseUrl(), request);
                HttpEntity<Void> httpEntity = new HttpEntity<>(headers);
                return exchangeMarketData(url, httpEntity, DayMinutePriceResponse.class,
                        DayMinutePriceResponse::getReturnCode, "국내 시장 당일 분봉 데이터 조회 실패");
            }));
        }
    }
    
    /**
     * 한국투자증권 국내업종 당일분봉조회 API URL 생성
     * @param request 조회 조건 (분류코드, 종목코드, 시간 등)
     * @return 한국투자증권 국내업종 당일분봉조회 API URL
     */
    private String buildDayMinuteApiUrl(String baseUrl, DayMinutePriceRequest request) {
		String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        final URI uri = URI.create(baseUrl + KisApiConstant.DAY_MINUTE_PATH);
        return UriComponentsBuilder
                .fromUri(uri)
                .queryParam("FID_COND_MRKT_DIV_CODE", request.getCondMrktDivCode()) // 조건 시장 분류 코드(J : KRX)
				.queryParam("FID_INPUT_ISCD", request.getInputIscd()) // 입력 종목코드
				.queryParam("FID_INPUT_HOUR_1", now) // 입력 시간(현재 시간, 1분봉)
				.queryParam("FID_PW_DATA_INCU_YN", request.getPwDataIncuYn()) // 과거 데이터 포함 여부(Y : 최근 30개 output2 받을 수 있음)
				.queryParam("FID_ETC_CLS_CODE", request.getEtcClsCode()) // 기타 구분 코드("0")
				.build().toUriString();
    }

    /**
     * 거래량순 종목리스트 조회
     * @param request 조회 조건 (기간, 날짜 등)
     * @return 거래량순 종목리스트 상위 30개
     */
    public List<StockVolumeRank> getStockVolumeRanks(StockVolumeRankRequest request) {
        KisRestRequestScope scope = requestScope();
        String baseUrl = scope.getBaseUrl();
        Optional<List<StockVolumeRank>> cached =  kisStockVolumeRankRedisRepository.getStockVolumeRanks(baseUrl, request);
        if (cached.isPresent()) {
            log.info("거래량 순위 데이터 재사용");
            return cached.get();
        }

        log.info("한국투자증권 거래량순위 API 호출");
        KisStockVolumeRankResponse response = stockVolumeRankAPI(scope, request);
        kisStockVolumeRankRedisRepository.save(baseUrl, request, response.getOutput());
        return response.getOutput();
    }

    private KisStockVolumeRankResponse stockVolumeRankAPI(KisRestRequestScope scope, StockVolumeRankRequest request) {
        // 캐시 반환은 공유 CB의 표본·HALF_OPEN 탐색 허가를 소비하지 않습니다.
        try (MarketDataCircuitBreakers.Call call = circuitBreakers.acquire(Provider.KIS, scope.getBaseUrl())) {
            return rateLimitedClient.callWithCache(
                    scope,
                    null,
                    () -> call.execute(() -> {
                        String token = getMarketDataAccessToken(scope.getTokenScope());
                        HttpHeaders headers = createApiHeader(token, scope, "FHPST01710000");
                        String url = buildStockVolumeRankUrl(scope.getBaseUrl(), request);
                        HttpEntity<Void> httpEntity = new HttpEntity<>(headers);
                        KisStockVolumeRankResponse response = exchangeMarketData(url, httpEntity, KisStockVolumeRankResponse.class,
                                KisStockVolumeRankResponse::getReturnCode, "거래량 순위 데이터 조회 실패");
                        if (response.getOutput() == null) throw KisMarketDataException.invalidResponse("거래량 순위 데이터 조회 실패");
                        return response;
                    }),
                    60
            );
        }
    }

    private String buildStockVolumeRankUrl(String baseUrl, StockVolumeRankRequest request) {
        final URI uri = URI.create(baseUrl + KisApiConstant.STOCK_VOLUME_RANK);
        return UriComponentsBuilder
                .fromUri(uri)
                .queryParam("FID_COND_MRKT_DIV_CODE", request.getMarketCode())
                .queryParam("FID_COND_SCR_DIV_CODE", request.getScreenCode())
                .queryParam("FID_INPUT_ISCD", request.getInputCode())
                .queryParam("FID_DIV_CLS_CODE", request.getDivClassCode())
                .queryParam("FID_BLNG_CLS_CODE", request.getBlngClassCode())
                .queryParam("FID_TRGT_CLS_CODE", request.getTargetClassCode())
                .queryParam("FID_TRGT_EXLS_CLS_CODE", request.getTargetExlsClassCode())
                .queryParam("FID_INPUT_PRICE_1", request.getInputPrice1())
                .queryParam("FID_INPUT_PRICE_2", request.getInputPrice2())
                .queryParam("FID_VOL_CNT", request.getVolumeCount())
                .queryParam("FID_INPUT_DATE_1", request.getInputDate1())
                .build().toUriString();
    }

    /***
     * 한국투자증권 국내업종 일별분봉조회
     * @param request 조회 조건 (분류코드, 종목코드, 시간, 날짜 등)
     * @return 일별분봉조회 데이터 리스트
     */
    
    // Redis에 있는지 확인하고 반환, 만약 Redis에 데이터가 하나도 없으면 그때만 한투 api 직접호출
    public List<StreamMinutePrice> getStreamMinutePrices(StreamMinutePriceRequest request){
        KisRestRequestScope scope = requestScope();
        String baseUrl = scope.getBaseUrl();
    	String stockCode = request.getInputIscd();
        Optional<List<StreamMinutePrice>> cachedMinute = kisChartRedisRepository.getStreamMinuteChartData(baseUrl, request);
    	
    	if (cachedMinute.isPresent()) {
    	    log.info("일별 분봉 차트 데이터 캐시 재사용: {}", stockCode);
    	    return cachedMinute.get();
    	}
    	
    	log.info("한국투자증권 일별 분봉 시세 api 호출: {}", stockCode);
    	
        StreamMinutePriceResponse response = streamMinutePricesAPI(scope, request);
    	
        // output2가 있으면 빈 목록도 기존 정책대로 저장합니다.
    	if (response != null && response.getOutput2() != null) {
    		List<StreamMinutePrice> minuteData = response.getOutput2();
    	    Collections.reverse(minuteData); // 과거 -> 최신 순으로 정렬
    	    
            kisChartRedisRepository.saveStreamMinute(baseUrl, request, minuteData);
    	    return minuteData;
    	}
    	return Collections.emptyList(); // 빈 리스트 반환
    }

    /**
     * 한국투자증권 국내업종 일별분봉조회 API 호출
     * @param request 조회 조건 (분류코드, 종목코드, 시간, 날짜 등)
     * @return 일별분봉조회 데이터 리스트
     * 
     */
    private StreamMinutePriceResponse streamMinutePricesAPI(KisRestRequestScope scope, StreamMinutePriceRequest request) {
        try (MarketDataCircuitBreakers.Call call = circuitBreakers.acquire(Provider.KIS, scope.getBaseUrl())) {
            return rateLimitedClient.callWithCache(
                    scope,
                    null,
                    () -> call.execute(() -> {
                        String token = getMarketDataAccessToken(scope.getTokenScope());
                        HttpHeaders headers = createApiHeader(token, scope, "FHKST03010230");
                        headers.set("tr_cont", "N"); // 연속 거래 여부
                        String url = buildStreamMinuteApiUrl(scope.getBaseUrl(), request);
                        HttpEntity<Void> httpEntity = new HttpEntity<>(headers);
                        return exchangeMarketData(url, httpEntity, StreamMinutePriceResponse.class,
                                StreamMinutePriceResponse::getReturnCode, "국내 시장 일별 분봉 데이터 조회 실패");
                    }),
                    60
            );
        }
    }
    
    /**
     * 한국투자증권 국내업종 일별분봉조회 API URL 생성
     * @param request 조회 조건 (분류코드, 종목코드, 시간, 날짜 등)
     * @return 한국투자증권 국내업종 일별분봉조회 API URL
     */
    private String buildStreamMinuteApiUrl(String baseUrl, StreamMinutePriceRequest request) {
        final URI uri = URI.create(baseUrl + KisApiConstant.STREAM_MINUTE_PATH);
        return UriComponentsBuilder
                .fromUri(uri)
                .queryParam("FID_COND_MRKT_DIV_CODE", request.getCondMrktDivCode()) // 조건 시장 분류 코드(J : KRX)
				.queryParam("FID_INPUT_ISCD", request.getInputIscd()) // 입력 종목코드
				.queryParam("FID_INPUT_HOUR_1", request.getInputHour1()) // 입력 시간1 (15:30 고정)
				.queryParam("FID_INPUT_DATE_1", request.getInputDate1()) // 입력 날짜
				.queryParam("FID_PW_DATA_INCU_YN", request.getPwDataIncuYn()) // 과거 데이터 포함 여부 (당일 실시간 N 고정)
				.queryParam("FID_FAKE_TICK_INCU_YN", request.getFakeTickIncuYn()) // 허봉 포함 여부 (공백 필수 입력)
				.build().toUriString();
    }

    public Integer getCurrentPrice(CurrentPriceRequest request) {
        // 현재가는 실시간성이 중요하므로 캐싱 TTL 짧게
        KisRestRequestScope scope = requestScope();
        String baseUrl = scope.getBaseUrl();
        String cacheKey = buildCurrentPriceCacheKey("kis:price:single:v2", baseUrl, request);

        return callCurrentPrice(
                scope,
                cacheKey,
                () -> {
                    String token = getMarketDataAccessToken(scope.getTokenScope());
                    HttpHeaders headers = createApiHeader(token, scope, "FHKST01010100");
                    URI uri = URI.create(baseUrl + KisApiConstant.PRESENT_PRICE);
                    String url = UriComponentsBuilder.fromUri(uri)
                            .queryParam("FID_COND_MRKT_DIV_CODE", request.getMarketCode())
                            .queryParam("FID_INPUT_ISCD", request.getStockCode())
                            .build().toUriString();
                    HttpEntity<Void> httpEntity = new HttpEntity<>(headers);
                    KisCurrentPriceResponse responseBody = exchangeMarketData(url, httpEntity, KisCurrentPriceResponse.class,
                            KisCurrentPriceResponse::getReturnCode, "현재가 조회 실패");
                    if(responseBody.getOutput() != null) {
                        try {
                            return Integer.parseInt(responseBody.getOutput().getCurrentPrice());
                        } catch (NumberFormatException e) {
                            throw KisMarketDataException.invalidResponse("현재가 조회 실패");
                        }
                    }
                    throw KisMarketDataException.invalidResponse("현재가 조회 실패");
                }
        );
    }

    /**
     * 외인기관 추정가집계 데이터 조회 (데이터 원본 반환)
     * @param stockCode 종목코드 (MKSC_SHRN_ISCD)
     * @return 외인/기관 가집계 리스트 (ForeignerOrganization 목록)
     */
    public ForeignerOrganization getForeignerOrganizationData(String stockCode) {
        KisRestRequestScope scope = requestScope();
        String baseUrl = scope.getBaseUrl();
        Optional<ForeignerOrganization> cached = kisFrgnOrgnRedisRepository.getKisFrgnorgnNtby(baseUrl, stockCode);
        if (cached.isPresent()) {
            log.info("외인/기관 수량(가집계) 재사용");
            return cached.get();
        }
        
        // 1. API 호출
    	log.info("한국투자증권 외인/기관 추정가집계 api 호출: {}", stockCode);
        KisForeignerOrganizationResponse response = foreignerOrganizationAPI(scope, stockCode);
        if(response.getOutput2().size() ==0){
        	return new ForeignerOrganization();
        }
        kisFrgnOrgnRedisRepository.save(baseUrl, stockCode, response.getOutput2().get(0));
        
        // 2. 응답 데이터 반환
        return response.getOutput2().get(0);
    }

    /**
     * 한국투자증권 외인기관 추정가집계 API 호출 (내부 메서드)
     */
    private KisForeignerOrganizationResponse foreignerOrganizationAPI(KisRestRequestScope scope, String stockCode) {
        try (MarketDataCircuitBreakers.Call call = circuitBreakers.acquire(Provider.KIS, scope.getBaseUrl())) {
            return rateLimitedClient.callWithCache(
                    scope,
                    null,
                    () -> call.execute(() -> {
                        // 1. 접근 토큰 얻기
                        String token = getMarketDataAccessToken(scope.getTokenScope());
                        // 2. 헤더 설정 (이미지 명세 실전 TR ID: HHPTJ04160200)
                        HttpHeaders headers = createApiHeader(token, scope, "HHPTJ04160200");
                        // 3. URL 생성
                        String url = buildForeignerOrganizationUrl(scope.getBaseUrl(), stockCode);
                        // 4. API 호출
                        HttpEntity<Void> httpEntity = new HttpEntity<>(headers);
                        KisForeignerOrganizationResponse responseBody = exchangeMarketData(url, httpEntity, KisForeignerOrganizationResponse.class,
                                KisForeignerOrganizationResponse::getReturnCode, "외인기관 가집계 데이터 조회 실패");
                        if (responseBody.getOutput2() != null) {
                            return responseBody;
                        }
                        throw KisMarketDataException.invalidResponse("외인기관 가집계 데이터 조회 실패");
                    }),
                    60
            );
        }
    }

    /**
     * 외인기관 추정가집계용 URL 생성 (내부 메서드)
     */
    private String buildForeignerOrganizationUrl(String baseUrl, String stockCode) {
        final URI uri = URI.create(baseUrl + KisApiConstant.FOREIGNER_ORGANIZATION_PATH);
        return UriComponentsBuilder
                .fromUri(uri)
                .queryParam("MKSC_SHRN_ISCD", stockCode) // 종목코드 파라미터
                .build().toUriString();
    }

    public CurrentPrice getCurrentPriceDetail(CurrentPriceRequest request) {
        KisRestRequestScope scope = requestScope();
        String baseUrl = scope.getBaseUrl();
        String cacheKey = buildCurrentPriceCacheKey("kis:price:detail:v2", baseUrl, request);

        return callCurrentPrice(
                scope,
                cacheKey,
                () -> {
                    String token = getMarketDataAccessToken(scope.getTokenScope());
                    HttpHeaders headers = createApiHeader(token, scope, "FHKST01010100");
                    URI uri = URI.create(baseUrl + KisApiConstant.PRESENT_PRICE);
                    String url = UriComponentsBuilder.fromUri(uri)
                            .queryParam("FID_COND_MRKT_DIV_CODE", request.getMarketCode())
                            .queryParam("FID_INPUT_ISCD", request.getStockCode())
                            .build().toUriString();
                    HttpEntity<Void> httpEntity = new HttpEntity<>(headers);
                    KisCurrentPriceResponse responseBody = exchangeMarketData(url, httpEntity, KisCurrentPriceResponse.class,
                            KisCurrentPriceResponse::getReturnCode, "현재가 정보 조회 실패");
                    if(responseBody.getOutput() != null) {
                        return responseBody.getOutput();
                    }
                    throw KisMarketDataException.invalidResponse("현재가 정보 조회 실패");
                }
        );
    }

    /** Provider·저장 타입·실제 요청 환경·전체 조회 조건을 구분하고 기존 키는 재사용하지 않습니다. */
    private String buildCurrentPriceCacheKey(String prefix, String baseUrl, CurrentPriceRequest request) {
        StringBuilder key = new StringBuilder(prefix);
        for (String value : new String[]{baseUrl, request.getMarketCode(), request.getStockCode()}) {
            // 길이를 포함해 구분자·null·빈 문자열이 있는 조건도 서로 섞이지 않게 합니다.
            key.append(':').append(value == null ? -1 : value.length()).append(':');
            if (value != null) key.append(value);
        }
        return key.toString();
    }

    public List<CurrentPrice> getCurrentPricesDetail(List<String> stockCodes) {
        return stockCodes.parallelStream()  // 병렬 처리
                .map(stockCode -> {
                    try {
                        CurrentPriceRequest request = CurrentPriceRequest.builder()
                                .marketCode("J")
                                .stockCode(stockCode)
                                .build();
                        CurrentPrice price = getCurrentPriceDetail(request);
                        price.setStockCode(stockCode);
                        return price;
                    } catch (Exception e) {
                        log.error("종목 {} 현재가 조회 실패", stockCode);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

}
