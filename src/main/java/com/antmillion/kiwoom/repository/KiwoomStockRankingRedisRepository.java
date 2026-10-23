package com.antmillion.kiwoom.repository;

import com.antmillion.kiwoom.dto.KiwoomStockRankingRequest;
import com.antmillion.marketdata.dto.RankedStock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 내부 명시 주입 전용입니다. 조회 조건별로 분리하고 60초의 절대 만료를 검사합니다. */
public final class KiwoomStockRankingRedisRepository {
    private static final long TTL_MILLIS = TimeUnit.SECONDS.toMillis(60);
    private final RedisTemplate<String, Object> redis;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public KiwoomStockRankingRedisRepository(RedisTemplate<String, Object> redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<List<RankedStock>> find(String baseUrl, KiwoomStockRankingRequest request) {
        try {
            Object saved = redis.opsForValue().get(key(baseUrl, request));
            if (!(saved instanceof String)) return Optional.empty();
            JsonNode value = mapper.readTree((String) saved);
            if (value == null || !value.path("expires_at").isIntegralNumber()
                    || !value.path("expires_at").canConvertToLong()) return Optional.empty();
            long remaining = Math.subtractExact(value.path("expires_at").asLong(), clock.millis());
            if (remaining <= 0 || remaining > TTL_MILLIS) return Optional.empty();
            JsonNode rows = value.path("stocks");
            if (!rows.isArray() || rows.size() > 30) return Optional.empty();
            List<RankedStock> result = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                JsonNode row = rows.get(i);
                if (row.isNull()) {
                    result.add(null);
                    continue;
                }
                String rank = text(row.get("rank"));
                JsonNode favorite = row.path("favorite");
                if (!Integer.toString(i + 1).equals(rank) || !favorite.isBoolean() || favorite.asBoolean()) {
                    return Optional.empty();
                }
                result.add(new RankedStock(text(row.get("name")),
                        matching(row.get("code"), "|[0-9A-Z]{6}"), rank,
                        matching(row.get("price"), "|0|[1-9][0-9]*"),
                        matching(row.get("volume"), "|0|[1-9][0-9]*"),
                        matching(row.get("rate"), "|[+-]?[0-9]+(?:\\.[0-9]+)?"), false));
            }
            return Optional.of(result);
        } catch (JsonProcessingException | RuntimeException e) {
            // 손상·만료·저장소 장애는 miss입니다. 원문/원인 예외를 노출하지 않습니다.
            return Optional.empty();
        }
    }

    public void save(String baseUrl, KiwoomStockRankingRequest request, List<RankedStock> stocks) {
        if (stocks == null) return;
        try {
            ObjectNode value = mapper.createObjectNode().put("expires_at", Math.addExact(clock.millis(), TTL_MILLIS));
            ArrayNode rows = value.putArray("stocks");
            for (RankedStock stock : stocks) {
                if (stock == null) {
                    rows.addNull();
                } else {
                    rows.addObject().put("name", stock.getStockName()).put("code", stock.getStockCode())
                            .put("rank", stock.getRank()).put("price", stock.getCurrentPrice())
                            .put("volume", stock.getAccumulatedVolume()).put("rate", stock.getChangeRate())
                            .put("favorite", stock.getFavorite());
                }
            }
            // JSON 문자열을 사용하여 기존 Redis default typing과 불변 DTO 생성 방식에 의존하지 않습니다.
            redis.opsForValue().set(key(baseUrl, request), mapper.writeValueAsString(value),
                    TTL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException | RuntimeException e) {
            // 부가 캐시 저장 장애로 정상 API 응답을 실패시키지 않습니다.
        }
    }

    private static String text(JsonNode value) {
        if (value == null || (!value.isNull() && !value.isTextual())) {
            throw new IllegalArgumentException("Kiwoom 종목 순위 캐시 값이 유효하지 않습니다.");
        }
        return value.isNull() ? null : value.asText();
    }

    private static String matching(JsonNode value, String pattern) {
        String text = text(value);
        if (text != null && !text.matches(pattern)) {
            throw new IllegalArgumentException("Kiwoom 종목 순위 캐시 값이 유효하지 않습니다.");
        }
        return text;
    }

    private String key(String baseUrl, KiwoomStockRankingRequest request) {
        StringBuilder key = new StringBuilder("kiwoom:stock:ranking:v1");
        for (String value : new String[]{baseUrl, "ka10030", request.getMarket(), request.getSort(),
                request.getIncludedClasses(), request.getCredit(), request.getVolume(), request.getPrice(),
                request.getTradingValue(), request.getSession(), request.getExchange()}) {
            key.append(':').append(value == null ? -1 : value.length()).append(':');
            if (value != null) key.append(value);
        }
        return key.toString();
    }
}
