package com.techlab.renderpdf.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Cache configuration for performance optimization
 * - Template caching: Cache loaded DOCX templates in memory
 * - Font caching: Cache loaded fonts to avoid reloading
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Cache manager for templates
     * Templates are cached for 1 hour, max 100 templates
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("templates", "fonts");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(100) // Max 100 templates in cache
                .expireAfterWrite(1, TimeUnit.HOURS) // Expire after 1 hour
                .recordStats()); // Enable statistics for monitoring
        return cacheManager;
    }
}

