package com.shaybytes.tinylink.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    // Redis configuration for caching URL lookups and rate-limit state.
    // The application stores values as JSON so Java objects can be recovered
    // correctly.
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
            ObjectMapper baseObjectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Wraps Jackson into a Redis serializer that stores object values as JSON.
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(baseObjectMapper);

        // Stores Redis keys as plain text strings, not binary blobs.
        // This makes keys readable and consistent with url:<shortCode> usage.
        template.setKeySerializer(new StringRedisSerializer());

        // Same idea for hash field names.
        template.setHashKeySerializer(new StringRedisSerializer());

        // Use JSON serializer for values so Java objects can be stored and restored
        // reliably
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        // Finalizes the template setup so the serializers are actually applied
        template.afterPropertiesSet();
        return template;
    }
}
