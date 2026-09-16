package com.shahbytes.tinylink.services;

import com.shahbytes.tinylink.controllers.UrlStatsResponse;
import com.shahbytes.tinylink.dto.ShortenUrlRequest;
import com.shahbytes.tinylink.dto.ShortenUrlResponse;
import com.shahbytes.tinylink.dto.UrlAnalyticsResponse;
import com.shahbytes.tinylink.models.ClickEvent;
import com.shahbytes.tinylink.models.UrlData;
import com.shahbytes.tinylink.persistence.ClickEventEntity;
import com.shahbytes.tinylink.persistence.ClickEventRepository;
import com.shahbytes.tinylink.persistence.UrlEntity;
import com.shahbytes.tinylink.persistence.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlShortenerService {

    private final RedisTemplate<String, Object> redisTemplate;

    private final UrlRepository urlRepository;
    private final ClickEventRepository clickEventRepository;

    @Value("${tinylink.base-url}")
    private String baseUrl;

    @Value("${tinylink.short-code.length}")
    private int shortCodeLength;

    @Value("${tinylink.short-code.max-attempts}")
    private int maxGenerationAttempts;

    @Value("${tinylink.cache.ttl-minutes}")
    private int cacheTtlMinutes;

    private static final String BASE_62_CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public ShortenUrlResponse shortenUrl(ShortenUrlRequest request, String clientIp) {
        String shortCode = request.getCustomAlias();

        if (shortCode == null || shortCode.trim().isEmpty()) {
            shortCode = generateUniqueShortCode();
        } else {
            shortCode = shortCode.trim();

            if (shortCodeExists(shortCode)) {
                throw new IllegalArgumentException(
                        "Custom alias already exists: " + shortCode
                );
            }
        }

        UrlEntity urlEntity = new UrlEntity();
        urlEntity.setOriginalUrl(request.getOriginalUrl());
        urlEntity.setShortCode(shortCode);
        urlEntity.setExpiresAt(request.getExpiresAt());
        urlEntity.setCreatedAt(LocalDateTime.now());
        urlEntity.setCreatedBy(clientIp);
        urlEntity.setClickCount(0);
        urlEntity.setActive(true);

        urlRepository.save(urlEntity);

        cacheUrl(shortCode, request.getOriginalUrl(), request.getExpiresAt());

        log.info("Created short URL: {} -> {}", shortCode, request.getOriginalUrl());

        return ShortenUrlResponse.builder()
                .shortUrl(buildShortUrl(shortCode))
                .shortCode(shortCode)
                .originalUrl(request.getOriginalUrl())
                .createdAt(urlEntity.getCreatedAt())
                .expiresAt(urlEntity.getExpiresAt())
                .build();
    }

    private String buildShortUrl(String shortCode) {
        String normalizedBaseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        return normalizedBaseUrl + "/api/" + shortCode;
    }

    private void cacheUrl(String shortCode, String originalUrl, LocalDateTime expiresAt) {
        try {
            long ttlMinutes = cacheTtlMinutes;

            if (expiresAt != null) {
                long minutesUntilExpiry =
                        java.time.Duration.between(LocalDateTime.now(), expiresAt).toMinutes();

                if (minutesUntilExpiry <= 0) {
                    return;
                }

                ttlMinutes = Math.min(cacheTtlMinutes, minutesUntilExpiry);
            }

            redisTemplate.opsForValue().set(
                    "url:" + shortCode,
                    originalUrl,
                    ttlMinutes,
                    TimeUnit.MINUTES
            );
        } catch (Exception e) {
            log.warn("Failed to cache URL for {}: {}", shortCode, e.getMessage());
        }
    }

    private String generateUniqueShortCode() {
        for (int attempt = 0; attempt < maxGenerationAttempts; attempt++) {
            String code = generateRandomBase62();

            if (!shortCodeExists(code)) {
                return code;
            }
        }

        throw new RuntimeException(
                "Failed to generate unique short code after "
                        + maxGenerationAttempts + " attempts"
        );
    }

    private boolean shortCodeExists(String code) {
        return urlRepository.existsByShortCode(code);
    }

    private String generateRandomBase62() {
        StringBuilder sb = new StringBuilder(shortCodeLength);

        for (int i = 0; i < shortCodeLength; i++) {
            int index = ThreadLocalRandom.current().nextInt(BASE_62_CHARS.length());
            sb.append(BASE_62_CHARS.charAt(index));
        }

        return sb.toString();
    }

    public Optional<String> getOriginalUrl(String shortCode) {

        // First check Redis cache
        String cachedUrl = getCachedUrl(shortCode);

        if (cachedUrl != null) {
            return Optional.of(cachedUrl);
        }

        // If not in Redis, check PostgreSQL
        Optional<UrlEntity> entityOptional =
                urlRepository.findByShortCode(shortCode);

        if (entityOptional.isEmpty()) {
            return Optional.empty();
        }

        UrlEntity urlEntity = entityOptional.get();

        if (!urlEntity.isActive()) {
            return Optional.empty();
        }

        if (isExpired(urlEntity)) {
            urlEntity.setActive(false);
            urlRepository.save(urlEntity);
            return Optional.empty();
        }

        // Put the URL into Redis for future requests
        cacheUrl(shortCode, urlEntity.getOriginalUrl(), urlEntity.getExpiresAt());

        return Optional.of(urlEntity.getOriginalUrl());
    }

    private boolean isExpired(UrlEntity urlEntity) {
        return urlEntity.getExpiresAt() != null
                && urlEntity.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private String getCachedUrl(String shortCode) {
        try {
            return (String) redisTemplate.opsForValue().get("url:" + shortCode);
        } catch (Exception e) {
            log.warn(
                    "Failed to reach cached URL for {}: {}",
                    shortCode,
                    e.getMessage()
            );
            return null;
        }
    }

    @Transactional
    public void recordClick(
            String shortCode,
            String clientIp,
            String userAgent,
            String referrer
    ) {
        Optional<UrlEntity> entityOptional =
                urlRepository.findByShortCode(shortCode);

        if (entityOptional.isEmpty()) {
            return;
        }

        UrlEntity urlEntity = entityOptional.get();

        if (!urlEntity.isActive() || isExpired(urlEntity)) {
            return;
        }

        // Increase persistent click count directly in the database
        int updated = urlRepository.incrementClickCount(shortCode);

        if (updated == 0) {
            return;
        }

        // Create persistent click event
        ClickEventEntity clickEventEntity = new ClickEventEntity();

        clickEventEntity.setUrl(urlEntity);
        clickEventEntity.setTimestamp(LocalDateTime.now());
        clickEventEntity.setIpAddress(clientIp);
        clickEventEntity.setUserAgent(userAgent);
        clickEventEntity.setReferrer(referrer);

        clickEventRepository.save(clickEventEntity);

        log.debug("Recorded click for short code: {}", shortCode);
    }

    public Optional<UrlStatsResponse> getUrlStats(String shortCode) {

        Optional<UrlEntity> entityOptional =
                urlRepository.findByShortCode(shortCode);

        if (entityOptional.isEmpty()) {
            return Optional.empty();
        }

        UrlEntity urlEntity = entityOptional.get();

        return Optional.of(
                UrlStatsResponse.builder()
                        .shortCode(shortCode)
                        .originalUrl(urlEntity.getOriginalUrl())
                        .clickCount(urlEntity.getClickCount())
                        .createdAt(urlEntity.getCreatedAt())
                        .expiresAt(urlEntity.getExpiresAt())
                        .isActive(urlEntity.isActive())
                        .createdBy(urlEntity.getCreatedBy())
                        .build()
        );
    }

    public Optional<UrlAnalyticsResponse> getUrlAnalytics(String shortCode) {

        Optional<UrlEntity> entityOptional =
                urlRepository.findByShortCode(shortCode);

        if (entityOptional.isEmpty()) {
            return Optional.empty();
        }

        UrlEntity urlEntity = entityOptional.get();

        List<ClickEventEntity> clickEntities =
                clickEventRepository.findByUrlOrderByTimestampDesc(urlEntity);

        List<ClickEvent> clicks = clickEntities.stream()
                .map(this::toClickEvent)
                .toList();

        Map<String, Integer> clicksByReferrer = clicks.stream()
                .filter(c -> c.getReferrer() != null)
                .collect(Collectors.groupingBy(
                        ClickEvent::getReferrer,
                        Collectors.summingInt(e -> 1)
                ));

        Map<String, Integer> clicksByHour = clicks.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getTimestamp().getHour() + ":00",
                        Collectors.summingInt(e -> 1)
                ));

        Map<String, Integer> clicksByDay = clicks.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getTimestamp().toLocalDate().toString(),
                        Collectors.summingInt(e -> 1)
                ));

        List<ClickEvent> recentClicks = clicks.stream()
                .sorted((a, b) ->
                        b.getTimestamp().compareTo(a.getTimestamp())
                )
                .limit(10)
                .toList();

        return Optional.of(
                UrlAnalyticsResponse.builder()
                        .shortCode(shortCode)
                        .originalUrl(urlEntity.getOriginalUrl())
                        .totalClicks(urlEntity.getClickCount())
                        .createdAt(urlEntity.getCreatedAt())
                        .expiresAt(urlEntity.getExpiresAt())
                        .recentClicks(recentClicks)
                        .clicksByReferrer(clicksByReferrer)
                        .clicksByHour(clicksByHour)
                        .clicksByDay(clicksByDay)
                        .build()
        );
    }

    private ClickEvent toClickEvent(ClickEventEntity entity) {
        return ClickEvent.builder()
                .timestamp(entity.getTimestamp())
                .ipAddress(entity.getIpAddress())
                .userAgent(entity.getUserAgent())
                .referrer(entity.getReferrer())
                .country(entity.getCountry())
                .city(entity.getCity())
                .build();
    }

    public boolean deleteUrl(String shortCode) {

        Optional<UrlEntity> entityOptional =
                urlRepository.findByShortCode(shortCode);

        if (entityOptional.isEmpty()) {
            return false;
        }

        UrlEntity urlEntity = entityOptional.get();

        urlEntity.setActive(false);
        urlRepository.save(urlEntity);

        deleteCacheUrl(shortCode);

        log.info("Deleted URL: {}", shortCode);

        return true;
    }

    private void deleteCacheUrl(String shortCode) {
        try {
            redisTemplate.delete("url:" + shortCode);
        } catch (Exception e) {
            log.warn(
                    "Failed to delete cached URL for {}: {}",
                    shortCode,
                    e.getMessage()
            );
        }
    }

    public void cleanupExpiredUrls() {

        List<UrlEntity> expiredUrls =
                urlRepository.findExpiredActiveUrls(LocalDateTime.now());
        int cleanedCount = 0;

        for (UrlEntity urlEntity : expiredUrls) {
            urlEntity.setActive(false);
            urlRepository.save(urlEntity);

            deleteCacheUrl(urlEntity.getShortCode());

            cleanedCount++;
        }


        if (cleanedCount > 0) {
            log.info("Cleaned up {} expired URLs", cleanedCount);
        }
    }
}
