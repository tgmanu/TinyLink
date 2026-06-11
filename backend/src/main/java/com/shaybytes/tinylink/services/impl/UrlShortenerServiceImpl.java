package com.shaybytes.tinylink.services.impl; // package declaration: places this class in the service implementation namespace

import com.shaybytes.tinylink.dto.ShortenUrlRequest; // DTO for requests asking to shorten a URL
import com.shaybytes.tinylink.dto.ShortenUrlResponse; // DTO for responses after a URL is shortened
import com.shaybytes.tinylink.dto.UrlAnalyticsResponse; // DTO for analytics data returned to the client
import com.shaybytes.tinylink.dto.UrlStatsResponse; // DTO for basic URL statistics returned to the client
import com.shaybytes.tinylink.models.ClickEvent; // model representing one click event on a short URL
import com.shaybytes.tinylink.models.UrlData; // model representing stored URL metadata
import com.shaybytes.tinylink.services.UrlShortenerService; // service interface implemented by this class
import lombok.RequiredArgsConstructor; // Lombok annotation to generate constructor for final fields
import lombok.extern.slf4j.Slf4j; // Lombok annotation to provide a logger instance
import org.springframework.beans.factory.annotation.Value; // Spring annotation to inject property values
import org.springframework.data.redis.core.RedisTemplate; // Redis helper for caching and persistence
import org.springframework.stereotype.Service; // Spring annotation marking this as a service bean

import java.time.LocalDateTime; // Java type for timestamps
import java.util.*; // utility collections and Optional
import java.util.concurrent.ConcurrentHashMap; // thread-safe map implementation
import java.util.concurrent.ThreadLocalRandom; // secure random generator for short codes
import java.util.stream.Collectors; // stream collector utilities

@Service // marks this class as a Spring-managed service component
@RequiredArgsConstructor // generates a constructor for all final fields
@Slf4j // provides a logger instance named log
public class UrlShortenerServiceImpl implements UrlShortenerService { // implements the URL shortener service interface

    private final RedisTemplate<String, Object> redisTemplate; // Redis client template injected by Spring

    private final Map<String, UrlData> urlMappings = new ConcurrentHashMap<>(); // in-memory storage for short code ->
                                                                                // URL data

    private final Map<String, List<ClickEvent>> clickAnalytics = new ConcurrentHashMap<>(); // in-memory analytics list
                                                                                            // for each short code

    @Value("${tinylink.base-url:http://localhost:8080}")
    private String baseUrl; // base URL used to construct full shortened URLs

    @Value("${tinylink.short-code.length:6}")
    private int shortCodeLength; // configured length for generated short codes

    @Value("${tinylink.short-code.max-attempts:10}")
    private int maxGenerationAttempts; // max attempts to avoid collisions when generating codes

    @Value("${tinylink.cache.ttl-minutes:30}")
    private int cacheTtlMinutes; // TTL for Redis cached URL values

    private static final String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    @Override // service method implementation
    public ShortenUrlResponse shortenUrl(ShortenUrlRequest request, String clientIp) {
        String shortCode = request.getCustomAlias(); // read optional custom alias from request

        if (shortCode == null || shortCode.trim().isEmpty()) { // if no alias provided
            shortCode = generateUniqueShortCode(); // generate a random unique code
        } else {
            shortCode = shortCode.trim(); // trim whitespace from custom alias
            if (shortCodeExists(shortCode)) { // check alias uniqueness
                throw new IllegalArgumentException("Custom alias already exists: " + shortCode); // reject duplicates
            }
        }

        UrlData urlData = UrlData.builder() // build stored URL metadata object
                .originalUrl(request.getOriginalUrl()) // store original long URL
                .shortCode(shortCode) // store chosen or generated short code
                .createdAt(LocalDateTime.now()) // record creation time
                .expiresAt(request.getExpiresAt()) // record optional expiration time
                .clickCount(0) // initialize click count to zero
                .createdBy(clientIp) // store client IP that created the short URL
                .isActive(true) // mark URL as currently active
                .clickEvents(new ArrayList<>()) // initialize empty click event list
                .build(); // produce UrlData instance

        urlMappings.put(shortCode, urlData); // save URL metadata in in-memory map
        clickAnalytics.put(shortCode, new ArrayList<>()); // initialize analytics list for this code

        cacheUrl(shortCode, request.getOriginalUrl()); // cache the original URL in Redis for fast retrieval

        log.info("Created short URL: {} -> {}", shortCode, request.getOriginalUrl()); // log the created mapping

        return ShortenUrlResponse.builder() // create the response payload
                .shortUrl(buildShortUrl(shortCode)) // include the complete short URL
                .shortCode(shortCode) // include the short code itself
                .originalUrl(request.getOriginalUrl()) // include the original URL
                .createdAt(urlData.getCreatedAt()) // include creation timestamp
                .expiresAt(urlData.getExpiresAt()) // include expiration timestamp if present
                .build(); // return the response object
    }

    @Override
    public Optional<String> getOriginalUrl(String shortCode) {
        String cachedUrl = getCachedUrl(shortCode); // attempt to read the original URL from Redis cache
        if (cachedUrl != null) {
            return Optional.of(cachedUrl); // return cached value if present
        }

        UrlData urlData = urlMappings.get(shortCode); // read the mapping from in-memory storage
        if (urlData != null && urlData.isActive()) { // only continue if URL exists and is active
            if (isExpired(urlData)) { // check if the link has expired
                urlData.setActive(false); // deactivate expired link
                return Optional.empty(); // do not return an expired URL
            }

            cacheUrl(shortCode, urlData.getOriginalUrl()); // cache the original URL for next time
            return Optional.of(urlData.getOriginalUrl()); // return the active original URL
        }

        return Optional.empty(); // return empty if no active mapping exists
    }

    @Override
    public void recordClick(String shortCode, String clientIp, String userAgent, String referrer) {
        UrlData urlData = urlMappings.get(shortCode); // load the stored URL data for this code
        if (urlData != null && urlData.isActive()) { // only record clicks for active URLs
            urlData.setClickCount(urlData.getClickCount() + 1); // increment total click count

            ClickEvent clickEvent = ClickEvent.builder() // build analytics event for this hit
                    .timestamp(LocalDateTime.now()) // capture current time
                    .ipAddress(clientIp) // capture client IP
                    .userAgent(userAgent) // capture browser/user agent
                    .referrer(referrer) // capture referrer header
                    .build(); // create ClickEvent instance

            clickAnalytics.get(shortCode).add(clickEvent); // append click info to analytics list
            log.debug("Recorded click for short code: {}", shortCode); // debug log for click recording
        }
    }

    @Override
    public Optional<UrlStatsResponse> getUrlStats(String shortCode) {
        UrlData urlData = urlMappings.get(shortCode); // look up stored URL data
        if (urlData == null) { // if it does not exist
            return Optional.empty(); // respond with empty optional
        }

        return Optional.of(UrlStatsResponse.builder() // build stats response payload
                .shortCode(shortCode) // include the short code
                .originalUrl(urlData.getOriginalUrl()) // include original URL
                .clickCount(urlData.getClickCount()) // include total clicks
                .createdAt(urlData.getCreatedAt()) // include creation timestamp
                .expiresAt(urlData.getExpiresAt()) // include expiration timestamp
                .isActive(urlData.isActive()) // include active/inactive state
                .createdBy(urlData.getCreatedBy()) // include creator IP
                .build()); // return the stats DTO
    }

    @Override
    public Optional<UrlAnalyticsResponse> getUrlAnalytics(String shortCode) {
        UrlData urlData = urlMappings.get(shortCode); // retrieve stored URL metadata
        if (urlData == null) { // if missing
            return Optional.empty(); // no analytics available
        }

        List<ClickEvent> clicks = clickAnalytics.getOrDefault(shortCode, new ArrayList<>());

        Map<String, Integer> clicksByCountry = clicks.stream() // group clicks by country
                .filter(c -> c.getCountry() != null) // only count clicks with a country field
                .collect(Collectors.groupingBy(ClickEvent::getCountry, Collectors.summingInt(e -> 1))); // count per
                                                                                                        // country

        Map<String, Integer> clicksByReferrer = clicks.stream() // group clicks by referrer URL
                .filter(c -> c.getReferrer() != null) // only count non-null referrers
                .collect(Collectors.groupingBy(ClickEvent::getReferrer, Collectors.summingInt(e -> 1))); // count per
                                                                                                         // referrer

        Map<String, Integer> clicksByHour = clicks.stream() // group clicks by hour bucket
                .collect(Collectors.groupingBy(
                        c -> c.getTimestamp().getHour() + ":00", // format hour label
                        Collectors.summingInt(e -> 1))); // count per hour

        Map<String, Integer> clicksByDay = clicks.stream() // group clicks by day
                .collect(Collectors.groupingBy(
                        c -> c.getTimestamp().toLocalDate().toString(), // format date label
                        Collectors.summingInt(e -> 1))); // count per day

        List<ClickEvent> recentClicks = clicks.stream() // sort clicks to get most recent entries
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp())) // newest first
                .limit(10) // only keep the last ten clicks
                .collect(Collectors.toList()); // collect into a list

        return Optional.of(UrlAnalyticsResponse.builder() // build analytics response payload
                .shortCode(shortCode) // include short code
                .originalUrl(urlData.getOriginalUrl()) // include original URL
                .totalClicks(urlData.getClickCount()) // include total click count
                .createdAt(urlData.getCreatedAt()) // include creation time
                .expiresAt(urlData.getExpiresAt()) // include expiration time if any
                .recentClicks(recentClicks) // include recent click events
                .clicksByCountry(clicksByCountry) // include country breakdown
                .clicksByReferrer(clicksByReferrer) // include referrer breakdown
                .clicksByHour(clicksByHour) // include hour breakdown
                .clicksByDay(clicksByDay) // include day breakdown
                .build()); // return the analytics DTO
    }

    @Override
    public boolean shortCodeExists(String shortCode) {
        return urlMappings.containsKey(shortCode); // return true if the short code is already stored
    }

    @Override
    public boolean deleteUrl(String shortCode) {
        UrlData urlData = urlMappings.get(shortCode); // look up the stored URL entry
        if (urlData != null) { // if it exists
            urlData.setActive(false); // mark it as inactive
            deleteCachedUrl(shortCode); // remove any cached Redis entry
            log.info("Deleted URL: {}", shortCode); // log the deletion
            return true; // indicate successful deletion
        }
        return false; // indicate the short code was not found
    }

    @Override
    public void cleanupExpiredUrls() {
        int cleanedCount = 0; // counter for expired URLs cleaned up
        LocalDateTime now = LocalDateTime.now(); // current timestamp for expiration checks

        for (Map.Entry<String, UrlData> entry : urlMappings.entrySet()) { // iterate all stored URL entries
            UrlData urlData = entry.getValue(); // get the UrlData for this entry
            if (urlData.getExpiresAt() != null && urlData.getExpiresAt().isBefore(now) && urlData.isActive()) { // if
                                                                                                                // expired
                                                                                                                // and
                                                                                                                // still
                                                                                                                // active
                urlData.setActive(false); // deactivate expired entry
                deleteCachedUrl(entry.getKey()); // remove its cache entry
                cleanedCount++; // increment cleanup counter
            }
        }

        if (cleanedCount > 0) { // only log when cleanup did something
            log.info("Cleaned up {} expired URLs", cleanedCount); // log number of expired URLs cleaned
        }
    }

    private String generateUniqueShortCode() {
        for (int attempt = 0; attempt < maxGenerationAttempts; attempt++) { // try multiple times to avoid collisions
            String code = generateRandomBase62(shortCodeLength); // generate a random code from the allowed charset
            if (!shortCodeExists(code)) { // if code is not already used
                return code; // return this unique code
            }
        }
        throw new RuntimeException("Failed to generate unique short code after " + maxGenerationAttempts + " attempts");
    }

    private String generateRandomBase62(int length) {
        StringBuilder sb = new StringBuilder(length); // builder for the generated code
        for (int i = 0; i < length; i++) { // generate exactly the requested length
            int index = ThreadLocalRandom.current().nextInt(BASE62_CHARS.length()); // choose a random index in the
                                                                                    // allowed charset
            sb.append(BASE62_CHARS.charAt(index)); // append the chosen character
        }
        return sb.toString(); // return the random code string
    }

    private void cacheUrl(String shortCode, String originalUrl) {
        try {
            redisTemplate.opsForValue().set("url:" + shortCode, originalUrl, cacheTtlMinutes,
                    java.util.concurrent.TimeUnit.MINUTES); // store the original URL in Redis with TTL
        } catch (Exception e) {
            log.warn("Failed to cache URL for {}: {}", shortCode, e.getMessage()); // log cache failures without
        }
    }

    private String getCachedUrl(String shortCode) {
        try {
            return (String) redisTemplate.opsForValue().get("url:" + shortCode); // read cached URL string from Redis
        } catch (Exception e) {
            log.warn("Failed to read cached URL for {}: {}", shortCode, e.getMessage()); // log cache read failures
            return null; // return null to indicate cache miss
        }
    }

    private void deleteCachedUrl(String shortCode) {
        try {
            redisTemplate.delete("url:" + shortCode); // delete the Redis entry for this short code
        } catch (Exception e) {
            log.warn("Failed to delete cached URL for {}: {}", shortCode, e.getMessage()); // log deletion failures
        }
    }

    private boolean isExpired(UrlData urlData) {
        return urlData.getExpiresAt() != null && urlData.getExpiresAt().isBefore(LocalDateTime.now()); // return true

    }

    private String buildShortUrl(String shortCode) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl; // remove

        return normalizedBaseUrl + "/api/" + shortCode; // build the full redirectable short URL
    }
}
