package com.shaybytes.tinylink.services;

import com.shaybytes.tinylink.dto.ShortenUrlRequest;
import com.shaybytes.tinylink.dto.ShortenUrlResponse;
import com.shaybytes.tinylink.dto.UrlAnalyticsResponse;
import com.shaybytes.tinylink.dto.UrlStatsResponse;

import java.util.Optional;

public interface UrlShortenerService {

    /**
     * Shortens a URL and stores it in memory
     */
    ShortenUrlResponse shortenUrl(ShortenUrlRequest request, String clientIp);

    /**
     * Retrieves the original URL for a short code
     */
    Optional<String> getOriginalUrl(String shortCode);

    /**
     * Records a click event for analytics
     */
    void recordClick(String shortCode, String clientIp, String userAgent, String referrer);

    /**
     * Gets basic statistics for a URL
     */
    Optional<UrlStatsResponse> getUrlStats(String shortCode);

    /**
     * Gets detailed analytics for a URL
     */
    Optional<UrlAnalyticsResponse> getUrlAnalytics(String shortCode);

    /**
     * Checks if a short code exists
     */
    boolean shortCodeExists(String shortCode);

    /**
     * Deletes a URL (marks as inactive)
     */
    boolean deleteUrl(String shortCode);

    /**
     * Cleans up expired URLs
     */
    void cleanupExpiredUrls();
}
