package com.shaybytes.tinylink.controllers;

import com.shaybytes.tinylink.dto.ShortenUrlRequest;
import com.shaybytes.tinylink.dto.ShortenUrlResponse;
import com.shaybytes.tinylink.dto.UrlAnalyticsResponse;
import com.shaybytes.tinylink.dto.UrlStatsResponse;
import com.shaybytes.tinylink.services.RateLimitService;
import com.shaybytes.tinylink.services.UrlShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
// Controller layer: receives HTTP requests from the frontend and from public
// short URL visitors.
// It routes requests to the service layer and returns JSON or redirect
// responses.
public class UrlShortenerController {

    // Service that handles URL creation, lookup, click analytics, and cleanup.
    private final UrlShortenerService urlShortenerService;
    // Service that enforces per-client rate limiting for incoming requests.
    private final RateLimitService rateLimitService;

    // Endpoint called by the frontend when a user submits the create URL form.
    // It validates the request, checks client rate limits, and creates a new short
    // URL.
    @PostMapping("/shorten")
    public ResponseEntity<?> shortenUrl(
            @Valid @RequestBody ShortenUrlRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = getClientIp(httpRequest);

        // Check rate limit before creating a new short URL.
        // This protects the application from abuse by limiting how often one client can
        // create URLs.
        if (!rateLimitService.isAllowed(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of(
                            "error", "Rate limit exceeded",
                            "remainingRequests", rateLimitService.getRemainingRequests(clientIp),
                            "timeUntilReset", rateLimitService.getTimeUntilReset(clientIp)));
        }

        try {
            ShortenUrlResponse response = urlShortenerService.shortenUrl(request, clientIp);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error shortening URL", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }

    // Public redirect endpoint that is called when someone opens a short URL.
    // It looks up the original target URL, logs click analytics, and redirects the
    // browser.
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirectToUrl(
            @PathVariable String shortCode,
            HttpServletRequest request,
            HttpServletResponse response) {

        String clientIp = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        String referrer = request.getHeader("Referer");

        Optional<String> originalUrl = urlShortenerService.getOriginalUrl(shortCode);

        if (originalUrl.isPresent()) {
            // Record the click for analytics
            urlShortenerService.recordClick(shortCode, clientIp, userAgent, referrer);

            // Redirect to original URL
            response.setHeader("Location", originalUrl.get());
            return ResponseEntity.status(HttpStatus.FOUND).build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // Returns basic stats such as click count, creation time, expiration, and
    // creator info.
    @GetMapping("/stats/{shortCode}")
    public ResponseEntity<?> getUrlStats(@PathVariable String shortCode) {
        Optional<UrlStatsResponse> stats = urlShortenerService.getUrlStats(shortCode);

        if (stats.isPresent()) {
            return ResponseEntity.ok(stats.get());
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Short code not found"));
        }
    }

    // Returns more detailed analytics such as referrers and time-based click
    // breakdowns.
    @GetMapping("/analytics/{shortCode}")
    public ResponseEntity<?> getUrlAnalytics(@PathVariable String shortCode) {
        Optional<UrlAnalyticsResponse> analytics = urlShortenerService.getUrlAnalytics(shortCode);

        if (analytics.isPresent()) {
            return ResponseEntity.ok(analytics.get());
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Short code not found"));
        }
    }

    // Deletes or deactivates a short URL so it can no longer be used for redirects.
    @DeleteMapping("/{shortCode}")
    public ResponseEntity<?> deleteUrl(@PathVariable String shortCode) {
        boolean deleted = urlShortenerService.deleteUrl(shortCode);

        if (deleted) {
            return ResponseEntity.ok(Map.of("message", "URL deleted successfully"));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Short code not found"));
        }
    }

    // Simple health endpoint used to verify the backend is running.
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "TinyLink"));
    }

    private String getClientIp(HttpServletRequest request) {
        // First check for the X-Forwarded-For header, which proxies and load balancers
        // often set to include the original client IP address.
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // If there are multiple IPs, the first one is usually the real client IP.
            return xForwardedFor.split(",")[0].trim();
        }

        // If X-Forwarded-For is not present, check X-Real-IP as another common proxy
        // header.
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        // Fall back to the direct remote address of the HTTP request.
        return request.getRemoteAddr();
    }
}
