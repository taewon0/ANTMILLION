package com.antmillion.kiwoom;

import com.antmillion.kiwoom.repository.KiwoomCurrentPriceRedisRepository;
import com.antmillion.marketdata.dto.CurrentQuote;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 개발 설정·DB·실제 Redis/증권사 없이 저장 타입·환경·직렬화·신선도를 검증합니다. */
public class KiwoomCurrentPriceCacheTest {
    private static final String REAL = "https://api.kiwoom.com";
    private static final String VIRTUAL = "https://mockapi.kiwoom.com";
    private KiwoomTokenServiceTest.MutableClock clock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomCurrentPriceRedisRepository cache;

    @Before
    public void setUp() {
        clock = new KiwoomTokenServiceTest.MutableClock();
        redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        cache = new KiwoomCurrentPriceRedisRepository(redis, clock);
    }

    @Test
    public void tenSecondTtlAndReadDoNotExtendExpiration() {
        cache.savePrice(VIRTUAL, "005930", 100);
        String key = onlyKey();
        assertEquals(Long.valueOf(10_000), redis.ttls.get(key));
        long expiresAt = redis.expirations.get(key);
        clock.advanceSeconds(9);
        assertEquals(Integer.valueOf(100), cache.findPrice(VIRTUAL, "005930").orElseThrow());
        assertEquals(Long.valueOf(expiresAt), redis.expirations.get(key));
        assertEquals(1, redis.writes.get());
        clock.advanceSeconds(1);
        assertFalse(cache.findPrice(VIRTUAL, "005930").isPresent());
    }

    @Test
    public void absoluteExpirationRejectsStaleEntryEvenIfRedisTtlIsMissing() {
        cache.saveQuote(VIRTUAL, new CurrentQuote("005930", "100", "-01.20"));
        redis.expirations.clear();
        clock.advanceSeconds(10);
        assertFalse(cache.findQuote(VIRTUAL, "005930").isPresent());
        assertEquals(1, redis.values.size());
    }

    @Test
    public void providerEnvironmentMarketStockAndStorageTypeAreSeparatedInBothOrders() {
        for (boolean realFirst : List.of(true, false)) {
            setUp();
            String first = realFirst ? REAL : VIRTUAL;
            String second = realFirst ? VIRTUAL : REAL;
            cache.savePrice(first, "005930", 100);
            cache.savePrice(second, "005930", 200);
            cache.savePrice(first, "000660", 300);
            CurrentQuote quote = new CurrentQuote("005930", "400", "+1.20");
            cache.saveQuote(first, quote);
            assertEquals(Integer.valueOf(100), cache.findPrice(first, "005930").orElseThrow());
            assertEquals(Integer.valueOf(200), cache.findPrice(second, "005930").orElseThrow());
            assertEquals(Integer.valueOf(300), cache.findPrice(first, "000660").orElseThrow());
            assertEquals(quote, cache.findQuote(first, "005930").orElseThrow());
            assertFalse(cache.findQuote(second, "005930").isPresent());
            assertFalse(cache.findPrice(first, "035420").isPresent());
            assertTrue(redis.values.keySet().stream().allMatch(key -> key.startsWith("kiwoom:price:")
                    && key.contains(":3:KRX:") && (key.contains(":single:v1:") || key.contains(":quote:v1:"))));
        }
    }

    @Test
    public void kisLegacyAndOtherMarketKeysAreNeverReadOrWritten() {
        cache.savePrice(VIRTUAL, "005930", 100);
        String key = onlyKey();
        Object value = redis.values.remove(key);
        Map<String, Object> unrelated = new HashMap<>();
        unrelated.put(key.replace("kiwoom:", "kis:"), value);
        unrelated.put(key.replace(":3:KRX:", ":3:NXT:"), value);
        unrelated.put("current:price:005930", 700);
        unrelated.put("kiwoom:access_token:v1:synthetic", "synthetic-token");
        redis.values.putAll(unrelated);
        assertFalse(cache.findPrice(VIRTUAL, "005930").isPresent());
        assertEquals(List.of(key), redis.reads);
        assertEquals(unrelated, redis.values);
    }

    @Test
    public void singleAndQuoteCannotDeserializeEachOthersStorageType() {
        cache.savePrice(VIRTUAL, "005930", 100);
        String singleKey = onlyKey();
        Object single = redis.values.get(singleKey);
        cache.saveQuote(VIRTUAL, new CurrentQuote("005930", "200", "0"));
        String quoteKey = redis.values.keySet().stream().filter(key -> !key.equals(singleKey)).findFirst().orElseThrow();
        redis.values.put(singleKey, redis.values.get(quoteKey));
        redis.values.put(quoteKey, single);
        assertFalse(cache.findPrice(VIRTUAL, "005930").isPresent());
        assertFalse(cache.findQuote(VIRTUAL, "005930").isPresent());
    }

    @Test
    public void nullAndBlankPriceAreNotCachedWhileZeroAndNullOrBlankRateArePreserved() {
        cache.savePrice(VIRTUAL, "005930", null);
        for (String price : Arrays.asList(null, "")) {
            cache.saveQuote(VIRTUAL, new CurrentQuote("005930", price, "0"));
        }
        assertTrue(redis.values.isEmpty());
        cache.savePrice(VIRTUAL, "005930", 0);
        assertEquals(Integer.valueOf(0), cache.findPrice(VIRTUAL, "005930").orElseThrow());
        for (String rate : Arrays.asList(null, "")) {
            CurrentQuote quote = new CurrentQuote("005930", "0", rate);
            cache.saveQuote(VIRTUAL, quote);
            assertEquals(quote, cache.findQuote(VIRTUAL, "005930").orElseThrow());
        }
    }

    @Test
    public void corruptedAndOutOfRangeSingleCacheIsMissWithoutRawException() {
        cache.savePrice(VIRTUAL, "005930", 100);
        String key = onlyKey();
        for (Object value : List.of(100, "bad-sensitive", "null", "[]", "{}",
                single("-1"), single("2147483648"), single("1.5"), single("\"100\""),
                "{\"price\":100,\"expires_at\":\"bad\"}",
                "{\"price\":100,\"expires_at\":9223372036854775808}")) {
            redis.values.put(key, value);
            assertFalse(cache.findPrice(VIRTUAL, "005930").isPresent());
        }
    }

    @Test
    public void corruptedQuotePriceOrRateIsMiss() {
        cache.saveQuote(VIRTUAL, new CurrentQuote("005930", "100", "0"));
        String key = onlyKey();
        for (String fields : List.of("\"price\":100,\"change_rate\":\"0\"",
                "\"price\":\"2147483648\",\"change_rate\":null",
                "\"price\":\"-100\",\"change_rate\":null",
                "\"price\":\"100\",\"change_rate\":2",
                "\"price\":\"100\",\"change_rate\":\"bad-sensitive\"", "\"price\":\"100\"")) {
            redis.values.put(key, "{" + fields + ",\"expires_at\":" + (clock.millis() + 10_000) + "}");
            assertFalse(cache.findQuote(VIRTUAL, "005930").isPresent());
        }
    }

    @Test
    public void clockBeforeSaveTimeRejectsFutureCache() {
        cache.savePrice(VIRTUAL, "005930", 100);
        clock.advanceSeconds(-1);
        assertFalse(cache.findPrice(VIRTUAL, "005930").isPresent());
    }

    @Test
    public void redisFailureIsMissAndBestEffortWriteDoesNotExposeRawCause() {
        redis.failWrites = true;
        cache.savePrice(VIRTUAL, "005930", 100);
        cache.saveQuote(VIRTUAL, new CurrentQuote("005930", "100", "0"));
        assertTrue(redis.values.isEmpty());
        redis.failReads = true;
        assertFalse(cache.findPrice(VIRTUAL, "005930").isPresent());
        assertFalse(cache.findQuote(VIRTUAL, "005930").isPresent());
    }

    @Test
    public void existingRedisSerializerRoundTripsBothCacheTypes() {
        cache.savePrice(VIRTUAL, "005930", Integer.MAX_VALUE);
        CurrentQuote quote = new CurrentQuote("005930", "2147483647", "-01.20");
        cache.saveQuote(VIRTUAL, quote);
        ObjectMapper mapper = new ObjectMapper();
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(), ObjectMapper.DefaultTyping.NON_FINAL);
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);
        redis.values.replaceAll((key, value) -> serializer.deserialize(serializer.serialize(value)));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), cache.findPrice(VIRTUAL, "005930").orElseThrow());
        assertEquals(quote, cache.findQuote(VIRTUAL, "005930").orElseThrow());
    }

    private String single(String price) {
        return "{\"price\":" + price + ",\"expires_at\":" + (clock.millis() + 10_000) + "}";
    }

    private String onlyKey() {
        assertEquals(1, redis.values.size());
        return redis.values.keySet().iterator().next();
    }
}
