package com.antmillion.kiwoom.config;

import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;

/** 설정 파일이 없어도 기동하며 enabled=true일 때만 인증 bean을 등록합니다. */
@Configuration
@PropertySource(value = "classpath:kiwoomApi.properties", ignoreResourceNotFound = true)
public class KiwoomAuthConfig {
    @Bean
    @Conditional(Enabled.class)
    public RestTemplate kiwoomAuthRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    @Bean
    @Conditional(Enabled.class)
    public KiwoomAccessTokenRedisRepository kiwoomAccessTokenRedisRepository(RedisTemplate<String, Object> redis) {
        return new KiwoomAccessTokenRedisRepository(redis, Clock.systemUTC());
    }

    @Bean
    @Conditional(Enabled.class)
    public KiwoomTokenService kiwoomTokenService(Environment environment,
            @Qualifier("kiwoomAuthRestTemplate") RestTemplate rest, KiwoomAccessTokenRedisRepository tokens,
            RedissonClient locks) {
        KiwoomAccessTokenScope scope = new KiwoomAccessTokenScope(environment.getProperty("kiwoom.mode", "virtual"),
                environment.getProperty("kiwoom.app-key"), environment.getProperty("kiwoom.app-secret"));
        long buffer = environment.getProperty("kiwoom.auth.refresh-buffer-seconds", Long.class, 600L);
        return new KiwoomTokenService(scope, buffer, rest, tokens, locks, Clock.systemUTC());
    }

    public static class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment().getProperty("kiwoom.auth.enabled", "false");
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("kiwoom.auth.enabled는 true 또는 false이어야 합니다.");
            }
            return Boolean.parseBoolean(value);
        }
    }
}
