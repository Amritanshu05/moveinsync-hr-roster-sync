package com.moveinsync.roster.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CacheConfig {

    public static final String EMPLOYEE_CACHE = "employee_cache";
    public static final String OFFICES_CACHE = "offices_cache";
    public static final String TENANT_CACHE = "tenant_cache";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                EMPLOYEE_CACHE,
                OFFICES_CACHE,
                TENANT_CACHE
        );
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .initialCapacity(1000)
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats());
        return cacheManager;
    }
}
