package com.antmillion.kiwoom.repository;

import com.antmillion.marketdata.dto.CurrentQuote;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 내부 명시 주입 전용입니다. Redis TTL과 저장 시각으로 10초 신선도를 검사합니다. */
public final class KiwoomCurrentPriceRedisRepository {
    private static final long TTL_MILLIS = 10_000L;
    private final RedisTemplate<String, Object> redis;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public KiwoomCurrentPriceRedisRepository(RedisTemplate<String, Object> redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<Integer> findPrice(String baseUrl, String stockCode) {
        return read("single", baseUrl, stockCode).map(value -> value.path("price").asInt());
    }

    public Optional<CurrentQuote> findQuote(String baseUrl, String stockCode) {
        return read("quote", baseUrl, stockCode).filter(value -> validRate(value.get("change_rate")))
                .map(value -> new CurrentQuote(stockCode, value.path("price").asText(),
                        value.path("change_rate").isNull() ? null : value.path("change_rate").asText()));
    }

    public void savePrice(String baseUrl, String stockCode, Integer price) {
        if (price == null) return;
        ObjectNode value = mapper.createObjectNode().put("price", price);
        save("single", baseUrl, stockCode, value);
    }

    public void saveQuote(String baseUrl, CurrentQuote quote) {
        // 누락/빈 가격 응답은 원래 계약대로 반환하되 fallback용으로 저장하지 않습니다.
        if (quote.getCurrentPrice() == null || quote.getCurrentPrice().isEmpty()) return;
        ObjectNode value = mapper.createObjectNode().put("price", quote.getCurrentPrice())
                .put("change_rate", quote.getChangeRate());
        save("quote", baseUrl, quote.getStockCode(), value);
    }

    private Optional<JsonNode> read(String type, String baseUrl, String stockCode) {
        try {
            Object saved = redis.opsForValue().get(key(type, baseUrl, stockCode));
            if (!(saved instanceof String)) return Optional.empty();
            JsonNode value = mapper.readTree((String) saved);
            if (value == null || !value.path("expires_at").isIntegralNumber()
                    || !value.path("expires_at").canConvertToLong()) return Optional.empty();
            long remaining = value.path("expires_at").asLong() - clock.millis();
            if (remaining <= 0 || remaining > TTL_MILLIS) return Optional.empty();
            JsonNode price = value.path("price");
            if ("single".equals(type)) {
                if (!price.isIntegralNumber() || !price.canConvertToInt() || price.asInt() < 0) return Optional.empty();
            } else if (!price.isTextual() || !price.asText().matches("0|[1-9][0-9]{0,9}")
                    || Long.parseLong(price.asText()) > Integer.MAX_VALUE) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (JsonProcessingException | RuntimeException e) {
            // 손상·저장소 장애는 캐시 miss입니다. 원문/원인 예외는 노출하지 않습니다.
            return Optional.empty();
        }
    }

    private boolean validRate(JsonNode rate) {
        return rate != null && (rate.isNull() || rate.isTextual()
                && (rate.asText().isEmpty() || rate.asText().matches("[+-]?[0-9]+(?:\\.[0-9]+)?")));
    }

    private void save(String type, String baseUrl, String stockCode, ObjectNode value) {
        try {
            value.put("expires_at", clock.millis() + TTL_MILLIS);
            // 기존 Redis 직렬화에 JSON 문자열만 전달하여 DTO default typing에 의존하지 않습니다.
            redis.opsForValue().set(key(type, baseUrl, stockCode), mapper.writeValueAsString(value),
                    TTL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException | RuntimeException e) {
            // 부가 캐시 저장 실패로 정상 API 응답을 실패시키지 않습니다.
        }
    }

    private String key(String type, String baseUrl, String stockCode) {
        StringBuilder key = new StringBuilder("kiwoom:price:").append(type).append(":v1");
        for (String value : new String[]{baseUrl, "KRX", stockCode}) {
            key.append(':').append(value.length()).append(':').append(value);
        }
        return key.toString();
    }
}
