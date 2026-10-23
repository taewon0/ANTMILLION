package com.antmillion.kiwoom.repository;

import com.antmillion.marketdata.dto.MinuteCandle;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 내부 명시 주입 전용입니다. 일별 분봉만 저장하며 60초의 절대 만료를 검사합니다. */
public final class KiwoomDailyMinuteRedisRepository {
    private static final long TTL_MILLIS = TimeUnit.SECONDS.toMillis(60);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private final RedisTemplate<String, Object> redis;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public KiwoomDailyMinuteRedisRepository(RedisTemplate<String, Object> redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<List<MinuteCandle>> find(String baseUrl, MinuteChartRequest request) {
        try {
            Object saved = redis.opsForValue().get(key(baseUrl, request));
            if (!(saved instanceof String)) return Optional.empty();
            JsonNode value = mapper.readTree((String) saved);
            if (value == null || !value.path("expires_at").isIntegralNumber()
                    || !value.path("expires_at").canConvertToLong()) return Optional.empty();
            long remaining = Math.subtractExact(value.path("expires_at").asLong(), clock.millis());
            if (remaining <= 0 || remaining > TTL_MILLIS) return Optional.empty();
            JsonNode candles = value.path("candles");
            if (!candles.isArray() || candles.size() > 120) return Optional.empty();
            List<MinuteCandle> result = new ArrayList<>();
            LocalTime previous = null;
            for (JsonNode candle : candles) {
                if (candle.isNull()) {
                    result.add(null);
                    continue;
                }
                JsonNode dateValue = candle.path("date");
                JsonNode timeValue = candle.path("time");
                if (!dateValue.isTextual() || !dateValue.asText().matches("[0-9]{8}")
                        || !timeValue.isTextual() || !timeValue.asText().matches("[0-9]{6}")) {
                    return Optional.empty();
                }
                LocalDate date = LocalDate.parse(dateValue.asText(), DateTimeFormatter.BASIC_ISO_DATE);
                LocalTime time = LocalTime.parse(timeValue.asText(), TIME);
                if (!date.equals(request.getDate()) || time.isAfter(LocalTime.of(15, 30))
                        || (previous != null && time.isBefore(previous))) return Optional.empty();
                previous = time;
                result.add(new MinuteCandle(dateValue.asText(), timeValue.asText(), price(candle.get("close")),
                        price(candle.get("open")), price(candle.get("high")), price(candle.get("low"))));
            }
            return Optional.of(result);
        } catch (JsonProcessingException | RuntimeException e) {
            // 손상·만료·저장소 장애는 miss입니다. 원문/원인 예외를 노출하지 않습니다.
            return Optional.empty();
        }
    }

    public void save(String baseUrl, MinuteChartRequest request, List<MinuteCandle> candles) {
        if (candles == null) return;
        try {
            ObjectNode value = mapper.createObjectNode().put("expires_at", Math.addExact(clock.millis(), TTL_MILLIS));
            ArrayNode rows = value.putArray("candles");
            for (MinuteCandle candle : candles) {
                if (candle == null) {
                    rows.addNull();
                } else {
                    rows.addObject().put("date", candle.getDate()).put("time", candle.getTime())
                            .put("close", candle.getClosePrice()).put("open", candle.getOpenPrice())
                            .put("high", candle.getHighPrice()).put("low", candle.getLowPrice());
                }
            }
            // JSON 문자열로 저장하여 기존 Redis default typing과 DTO 생성 방식에 의존하지 않습니다.
            redis.opsForValue().set(key(baseUrl, request), mapper.writeValueAsString(value),
                    TTL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException | RuntimeException e) {
            // 부가 캐시 저장 장애로 정상 API 응답을 실패시키지 않습니다.
        }
    }

    private static String price(JsonNode value) {
        if (value == null || (!value.isNull() && (!value.isTextual()
                || !value.asText().matches("|0|[1-9][0-9]*")))) {
            throw new IllegalArgumentException("Kiwoom 일별 분봉 캐시 가격이 유효하지 않습니다.");
        }
        return value.isNull() ? null : value.asText();
    }

    private String key(String baseUrl, MinuteChartRequest request) {
        StringBuilder key = new StringBuilder("kiwoom:chart:daily-minute:v1");
        for (String value : new String[]{baseUrl, "KRX", request.getStockCode(),
                request.getDate().format(DateTimeFormatter.BASIC_ISO_DATE), "1", "1", "153000"}) {
            key.append(':').append(value.length()).append(':').append(value);
        }
        return key.toString();
    }
}
