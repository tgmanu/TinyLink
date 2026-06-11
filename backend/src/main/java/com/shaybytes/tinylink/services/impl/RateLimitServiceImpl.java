package com.shaybytes.tinylink.services.impl;

import com.shaybytes.tinylink.models.RateLimitData;
import com.shaybytes.tinylink.services.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitServiceImpl implements RateLimitService {

    private final RedisTemplate<String, Object> redisTemplate;

    // In-memory rate limiting data (backed by Redis for distributed scenarios)
    private final ConcurrentHashMap<String, RateLimitData> rateLimitData = new ConcurrentHashMap<>();

    @Value("${tinylink.rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Value("${tinylink.rate-limit.requests-per-hour:1000}")
    private int requestsPerHour;

    private static final String REDIS_KEY_PREFIX = "ratelimit:";

    @Override
    public boolean isAllowed(String clientId) {
        /*
         * Build the Redis key for this client.
         *
         * Redis is a key-value store, so every client's rate-limit state must be stored
         * under a unique key. With the current prefix, an IP like "192.168.1.10"
         * becomes:
         *
         * ratelimit:192.168.1.10
         *
         * This lets us fetch and update the request counter for one specific client
         * without affecting other clients.
         */
        String redisKey = REDIS_KEY_PREFIX + clientId;
        LocalDateTime now = LocalDateTime.now();

        /*
         * Try Redis first.
         *
         * Why Redis:
         * - Redis is external to the application process.
         * - Multiple backend instances can read and write the same key.
         * - Rate limits therefore remain consistent even in a distributed setup.
         *
         * If this application is scaled to multiple servers, keeping the counter only
         * in
         * local Java memory would be incorrect because each server would have its own
         * copy.
         * Redis acts as the shared fast store for this state.
         */
        RateLimitData data = getRateLimitDataFromRedis(redisKey);
        if (data == null) {
            /*
             * Fall back to local in-memory state.
             *
             * This happens when:
             * - Redis has no value for this client yet
             * - Redis is temporarily unavailable
             * - the Redis entry expired
             *
             * computeIfAbsent means:
             * - if this client already has an in-memory entry, reuse it
             * - otherwise create a new RateLimitData object and store it in the map
             *
             * The newly created object starts with:
             * - minuteCount = 0
             * - hourCount = 0
             * - minuteWindowStart = now
             * - hourWindowStart = now
             *
             * We start at 0 because the current request has not been counted yet. It will
             * be incremented later only after all checks pass.
             */
            data = rateLimitData.computeIfAbsent(clientId, k -> RateLimitData.builder()
                    .minuteCount(0)
                    .hourCount(0)
                    .minuteWindowStart(now)
                    .hourWindowStart(now)
                    .build());
        }

        /*
         * Enforce the per-minute rate limit.
         *
         * isWithinMinuteWindow(...) checks whether "now" still belongs to the same
         * one-minute window that started at data.windowStart.
         *
         * If we are still in that same minute window:
         * - keep using the current requestCount
         * - reject the request if the client has already hit the allowed limit
         */
        if (isWithinMinuteWindow(data, now)) {

            if (data.getMinuteCount() >= requestsPerMinute) {

                log.warn("Minute limit exceeded for {}", clientId);
                return false;
            }

        } else {

            data.setMinuteCount(0);
            data.setMinuteWindowStart(now);

        }

        /*
         * Enforce the per-hour rate limit.
         *
         * This uses the same RateLimitData object and checks whether the last request
         * was
         * made within the last hour. If so, the code compares the current counter
         * against
         * the configured hourly threshold.
         *
         * Conceptually this is trying to answer:
         * "Has this client been active recently, and have they already consumed too
         * many
         * requests for the hour?"
         *
         * Note:
         * This is a simplified implementation. In a production-grade design, minute and
         * hour limits are usually tracked with separate counters or sliding windows.
         */
        if (isWithinHourWindow(data, now)) {

            if (data.getHourCount() >= requestsPerHour) {

                log.warn("Hour limit exceeded for {}", clientId);
                return false;
            }

        } else {

            data.setHourCount(0);
            data.setHourWindowStart(now);

        }

        /*
         * Count the current request.
         *
         * Reaching this point means the request passed all rate-limit checks, so it now
         * becomes part of the client's history:
         * - increment requestCount by 1
         * - update lastRequest to the current timestamp
         */
        data.setMinuteCount(
                data.getMinuteCount() + 1);

        data.setHourCount(
                data.getHourCount() + 1);

        /*
         * Persist the updated state back to Redis.
         *
         * This step is what makes the latest counter visible to future requests and to
         * other application instances. On the next call for the same client, the
         * service
         * will try to read this saved value and continue from there.
         */
        saveRateLimitDataToRedis(redisKey, data);

        return true;
    }

    @Override
    public int getRemainingRequests(String clientId) {
        String redisKey = REDIS_KEY_PREFIX + clientId;
        RateLimitData data = getRateLimitDataFromRedis(redisKey);

        if (data == null) {
            return requestsPerMinute; // Full quota available
        }

        LocalDateTime now = LocalDateTime.now();
        if (!isWithinMinuteWindow(data, now)) {
            return requestsPerMinute; // New window, full quota
        }

        return Math.max(0, requestsPerMinute - data.getMinuteCount());
    }

    @Override
    public long getTimeUntilReset(String clientId) {

        String redisKey = REDIS_KEY_PREFIX + clientId;

        RateLimitData data = getRateLimitDataFromRedis(redisKey);

        if (data == null) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();

        if (data.getMinuteCount() >= requestsPerMinute) {

            LocalDateTime nextMinute = data.getMinuteWindowStart()
                    .plusMinutes(1);

            return ChronoUnit.SECONDS.between(
                    now,
                    nextMinute);
        }

        if (data.getHourCount() >= requestsPerHour) {

            LocalDateTime nextHour = data.getHourWindowStart()
                    .plusHours(1);

            return ChronoUnit.SECONDS.between(
                    now,
                    nextHour);
        }

        return 0;
    }

    private boolean isWithinMinuteWindow(
            RateLimitData data,
            LocalDateTime now) {

        return data.getMinuteWindowStart() != null
                &&
                ChronoUnit.MINUTES.between(
                        data.getMinuteWindowStart(),
                        now) < 1;
    }

    private boolean isWithinHourWindow(
            RateLimitData data,
            LocalDateTime now) {

        return data.getHourWindowStart() != null
                &&
                ChronoUnit.HOURS.between(
                        data.getHourWindowStart(),
                        now) < 1;
    }

    private RateLimitData getRateLimitDataFromRedis(String key) {
        try {
            /*
             * Read the serialized RateLimitData object from Redis and convert it back into
             * a Java object.
             *
             * Redis itself does not understand Java classes. Spring serializes the object
             * before writing it and deserializes it here when reading.
             */
            return (RateLimitData) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Failed to get rate limit data from Redis: {}", e.getMessage());
            return null;
        }
    }

    private void saveRateLimitDataToRedis(String key, RateLimitData data) {
        try {
            /*
             * Save the latest rate-limit state to Redis with a TTL of one hour.
             *
             * TTL (time to live) means Redis will automatically remove this key after the
             * configured duration if it is not updated again.
             *
             * Why this helps:
             * - inactive clients do not leave stale keys forever
             * - Redis memory is cleaned up automatically over time
             * - the application does not need a separate cleanup job for rate-limit keys
             */
            redisTemplate.opsForValue().set(key, data, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Failed to save rate limit data to Redis: {}", e.getMessage());
        }
    }
}
