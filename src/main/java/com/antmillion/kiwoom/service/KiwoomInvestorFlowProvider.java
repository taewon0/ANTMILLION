package com.antmillion.kiwoom.service;

import com.antmillion.kiwoom.dto.KiwoomInvestorFlowRequest;
import com.antmillion.kiwoom.dto.KiwoomInvestorFlowResponse;
import com.antmillion.kiwoom.dto.KiwoomInvestorFlowResponse.Flow;
import com.antmillion.kiwoom.exception.KiwoomMarketDataException;
import com.antmillion.kiwoom.repository.KiwoomInvestorFlowRedisRepository;
import com.antmillion.marketdata.dto.InvestorFlow;
import com.antmillion.marketdata.provider.InvestorFlowProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Objects;
import java.util.Optional;

/** 장중 순매수 내부 adapter입니다. KIS 추정 가집계와 실데이터 일치를 보장하지 않습니다. */
public final class KiwoomInvestorFlowProvider implements InvestorFlowProvider {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private final KiwoomTokenService tokens;
    private final RestTemplate rest;
    private final KiwoomRestRateLimiter limiter;
    private final KiwoomInvestorFlowRedisRepository cache;

    public KiwoomInvestorFlowProvider(KiwoomTokenService tokens, RestTemplate rest,
            KiwoomRestRateLimiter limiter, KiwoomInvestorFlowRedisRepository cache) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public InvestorFlow getInvestorFlow(String stockCode) {
        if (stockCode == null || !stockCode.matches("[0-9A-Z]{6}")) {
            throw new IllegalArgumentException("Kiwoom 외국인·기관 조회는 KRX 단축 코드만 지원합니다.");
        }
        KiwoomInvestorFlowRequest request = new KiwoomInvestorFlowRequest(stockCode);
        Optional<InvestorFlow> cached = cache.find(tokens.getBaseUrl(), request);
        if (cached.isPresent()) return cached.get();
        // 다른 조회와 같은 singleton 예산을 사용하며 토큰 조회보다 먼저 거절합니다.
        limiter.acquireInvestorFlow(tokens);
        ResponseEntity<KiwoomInvestorFlowResponse> response = fetch(request);
        KiwoomInvestorFlowResponse data = response.getBody();
        if (data != null) {
            KiwoomMarketDataException.checkReturnCode(data.getReturnCode(), "Kiwoom 외국인·기관 응답이 유효하지 않습니다.");
        }
        if (data == null || data.getFlows() == null) throw invalidResponse();
        // 뒤 페이지에 더 최신 값이 있을 수 있으므로 불완전한 목록을 최신 값으로 반환하지 않습니다.
        String continuation = response.getHeaders().getFirst("cont-yn");
        if (continuation != null && !continuation.isBlank() && !"N".equals(continuation)) throw invalidResponse();
        LocalTime latestTime = null;
        InvestorFlow latest = new InvestorFlow(null, null, null);
        boolean latestConflicts = false;
        for (Flow flow : data.getFlows()) {
            if (flow == null) throw invalidResponse();
            LocalTime time = time(flow.getTime());
            String foreign = quantity(flow.getForeignNetQuantity());
            String organization = quantity(flow.getOrganizationNetQuantity());
            String combined = hasQuantity(foreign) && hasQuantity(organization)
                    ? new BigInteger(foreign).add(new BigInteger(organization)).toString() : null;
            InvestorFlow current = new InvestorFlow(foreign, organization, combined);
            if (latestTime == null || time.isAfter(latestTime)) {
                latestTime = time;
                latest = current;
                latestConflicts = false;
            } else if (time.equals(latestTime) && !current.equals(latest)) {
                latestConflicts = true;
            }
        }
        if (latestConflicts) throw invalidResponse();
        // 빈 목록은 기존 KIS처럼 저장하지 않으며, 유효 행의 null/빈 수량은 그대로 캐시합니다.
        if (latestTime != null) cache.save(tokens.getBaseUrl(), request, latest);
        return latest;
    }

    private ResponseEntity<KiwoomInvestorFlowResponse> fetch(KiwoomInvestorFlowRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.set("api-id", "ka10064");
        try {
            headers.setBearerAuth(tokens.getAccessToken());
        } catch (RuntimeException e) {
            throw KiwoomMarketDataException.tokenAccess("Kiwoom 외국인·기관 토큰 조회에 실패했습니다.");
        }
        try {
            return rest.exchange(tokens.getBaseUrl() + "/api/dostk/chart", HttpMethod.POST,
                    new HttpEntity<>(body, headers), KiwoomInvestorFlowResponse.class);
        } catch (RestClientException e) {
            throw KiwoomMarketDataException.fromHttp(e, "Kiwoom 외국인·기관 HTTP 요청에 실패했습니다.");
        }
    }

    private static LocalTime time(String value) {
        if (value == null || !value.matches("[0-9]{6}")) throw invalidResponse();
        try {
            return LocalTime.parse(value, TIME);
        } catch (DateTimeParseException e) {
            throw invalidResponse();
        }
    }

    private static String quantity(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return text;
        if (!text.matches("[+-]?[0-9]+")) throw invalidResponse();
        return new BigInteger(text).toString();
    }

    private static boolean hasQuantity(String value) {
        return value != null && !value.isEmpty();
    }

    private static KiwoomMarketDataException invalidResponse() {
        return KiwoomMarketDataException.invalidResponse("Kiwoom 외국인·기관 응답이 유효하지 않습니다.");
    }
}
