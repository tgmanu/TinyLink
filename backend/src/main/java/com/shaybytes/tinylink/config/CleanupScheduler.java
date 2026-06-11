package com.shaybytes.tinylink.config;

import com.shaybytes.tinylink.services.UrlShortenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CleanupScheduler {

    // Scheduler that periodically removes or deactivates expired URL entries.
    private final UrlShortenerService urlShortenerService;

    @Value("${tinylink.cleanup.interval-minutes:5}")
    private int cleanupIntervalMinutes;

    @Scheduled(fixedRateString = "#{${tinylink.cleanup.interval-minutes:5} * 60 * 1000}")
    public void cleanupExpiredUrls() {
        try {
            log.debug("Running scheduled cleanup of expired URLs");
            urlShortenerService.cleanupExpiredUrls();
        } catch (Exception e) {
            log.error("Error during scheduled cleanup", e);
        }
    }
}
