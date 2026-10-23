package com.antmillion.kiwoom.repository;

import com.antmillion.kiwoom.dto.KiwoomInvestorFlowRequest;
import com.antmillion.marketdata.dto.InvestorFlow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigInteger;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 내부 명시 주입 전용입니다. 조회 조건별로 분리하고 600초의 절대 만료를 검사합니다. */
public final class KiwoomInvestorFlowRedisRepository {
    private static final long TTL_MILLIS = TimeUnit.SECONDS.toMillis(600);
    private final RedisTemplate<String, Object> redis;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public KiwoomInvestorFlowRedisRepository(RedisTemplate<String, Object> redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<InvestorFlow> find(String baseUrl, KiwoomInvestorFlowRequest request) {
        try {
            Object saved = redis.opsForValue().get(key(baseUrl, request));
            if (!(saved instanceof String)) return Optional.empty();
            JsonNode value = mapper.readTree((String) saved);
            if (value == null || !value.path("expires_at").isIntegralNumber()
                    || !value.path("expires_at").canConvertToLong()) return Optional.empty();
            long remaining = Math.subtractExact(value.path("expires_at").asLong(), clock.millis());
            if (remaining <= 0 || remaining > TTL_MILLIS) return Optional.empty();
            String foreign = quantity(value.get("foreign"));
            String organization = quantity(value.get("organization"));
            String combined = quantity(value.get("combined"));
            String expected = hasQuantity(foreign) && hasQuantity(organization)
                    ? new BigInteger(foreign).add(new BigInteger(organization)).toString() : null;
            if (!Objects.equals(expected, combined)) return Optional.empty();
            return Optional.of(new InvestorFlow(foreign, organization, combined));
        } catch (JsonProcessingException | RuntimeException e) {
            // 손상·만료·저장소 장애는 miss입니다. 원문/원인 예외를 노출하지 않습니다.
            return Optional.empty();
        }
    }

    public void save(String baseUrl, KiwoomInvestorFlowRequest request, InvestorFlow flow) {
        if (flow == null) return;
        try {
            ObjectNode value = mapper.createObjectNode().put("expires_at", Math.addExact(clock.millis(), TTL_MILLIS))
                    .put("foreign", flow.getForeignNetQuantity())
                    .put("organization", flow.getOrganizationNetQuantity())
                    .put("combined", flow.getCombinedNetQuantity());
            // JSON 문자열로 기존 Redis default typing과 불변 DTO 생성 방식에 의존하지 않습니다.
            redis.opsForValue().set(key(baseUrl, request), mapper.writeValueAsString(value),
                    TTL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException | RuntimeException e) {
            // 부가 캐시 저장 장애로 정상 API 응답을 실패시키지 않습니다.
        }
    }

    private static String quantity(JsonNode value) {
        if (value == null || (!value.isNull() && !value.isTextual())) {
            throw new IllegalArgumentException("Kiwoom 외국인·기관 캐시 값이 유효하지 않습니다.");
        }
        String text = value.isNull() ? null : value.asText();
        if (hasQuantity(text) && !new BigInteger(text).toString().equals(text)) {
            throw new IllegalArgumentException("Kiwoom 외국인·기관 캐시 수량이 유효하지 않습니다.");
        }
        return text;
    }

    private static boolean hasQuantity(String value) {
        return value != null && !value.isEmpty();
    }

    private String key(String baseUrl, KiwoomInvestorFlowRequest request) {
        StringBuilder key = new StringBuilder("kiwoom:investor-flow:v1");
        for (String value : new String[]{baseUrl, "ka10064", request.getMarket(), request.getAmountQuantity(),
                request.getTrade(), request.getStockCode()}) {
            key.append(':').append(value == null ? -1 : value.length()).append(':');
            if (value != null) key.append(value);
        }
        return key.toString();
    }
}
