package com.antmillion.kiwoom.repository;

import com.antmillion.marketdata.dto.MarketIndexCandle;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 내부 명시 주입 전용입니다. 요청 조건별로 분리하고 60초의 절대 만료를 검사합니다. */
public final class KiwoomMarketIndexChartRedisRepository {
    private static final long TTL_MILLIS = TimeUnit.SECONDS.toMillis(60);
    private final RedisTemplate<String, Object> redis;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public KiwoomMarketIndexChartRedisRepository(RedisTemplate<String, Object> redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<List<MarketIndexCandle>> find(String baseUrl, MarketIndexChartRequest request) {
        try {
            Object saved = redis.opsForValue().get(key(baseUrl, request));
            if (!(saved instanceof String)) return Optional.empty();
            JsonNode value = mapper.readTree((String) saved);
            if (value == null || !value.path("expires_at").isIntegralNumber()
                    || !value.path("expires_at").canConvertToLong()) return Optional.empty();
            long remaining = Math.subtractExact(value.path("expires_at").asLong(), clock.millis());
            if (remaining <= 0 || remaining > TTL_MILLIS) return Optional.empty();
            JsonNode candles = value.path("candles");
            if (!candles.isArray()) return Optional.empty();
            List<MarketIndexCandle> result = new ArrayList<>();
            LocalDate previous = null;
            for (JsonNode candle : candles) {
                if (candle.isNull()) {
                    result.add(null);
                    continue;
                }
                JsonNode dateValue = candle.path("date");
                if (!dateValue.isTextual() || !dateValue.asText().matches("[0-9]{8}")) return Optional.empty();
                LocalDate date = LocalDate.parse(dateValue.asText(), DateTimeFormatter.BASIC_ISO_DATE);
                if (date.getYear() < 1 || date.isAfter(request.getEndDate())
                        || (previous != null && date.isAfter(previous))) return Optional.empty();
                previous = date;
                result.add(new MarketIndexCandle(dateValue.asText(), price(candle.get("close")),
                        price(candle.get("open")), price(candle.get("high")), price(candle.get("low"))));
            }
            return Optional.of(result);
        } catch (JsonProcessingException | RuntimeException e) {
            // 손상·만료·저장소 장애는 miss입니다. 원문/원인 예외를 노출하지 않습니다.
            return Optional.empty();
        }
    }

    public void save(String baseUrl, MarketIndexChartRequest request, List<MarketIndexCandle> candles) {
        if (candles == null) return;
        try {
            ObjectNode value = mapper.createObjectNode().put("expires_at", Math.addExact(clock.millis(), TTL_MILLIS));
            ArrayNode rows = value.putArray("candles");
            for (MarketIndexCandle candle : candles) {
                if (candle == null) {
                    rows.addNull();
                } else {
                    rows.addObject().put("date", candle.getDate()).put("close", candle.getCloseValue())
                            .put("open", candle.getOpenValue()).put("high", candle.getHighValue())
                            .put("low", candle.getLowValue());
                }
            }
            // JSON 문자열을 사용하여 기존 Redis default typing과 DTO 생성 방식에 의존하지 않습니다.
            redis.opsForValue().set(key(baseUrl, request), mapper.writeValueAsString(value),
                    TTL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException | RuntimeException e) {
            // 부가 캐시 저장 장애로 정상 API 응답을 실패시키지 않습니다.
        }
    }

    private static String price(JsonNode value) {
        if (value == null || (!value.isNull() && (!value.isTextual()
                || !value.asText().matches("|(?:0|[1-9][0-9]*)[.][0-9]{2}")))) {
            throw new IllegalArgumentException("Kiwoom 지수 일봉 캐시 지수 값이 유효하지 않습니다.");
        }
        return value.isNull() ? null : value.asText();
    }

    private String key(String baseUrl, MarketIndexChartRequest request) {
        StringBuilder key = new StringBuilder("kiwoom:chart:market-index:v1");
        for (String value : new String[]{baseUrl, "U", request.getIndexCode(), "D",
                request.getEndDate().format(DateTimeFormatter.BASIC_ISO_DATE)}) {
            key.append(':').append(value.length()).append(':').append(value);
        }
        return key.toString();
    }
}
