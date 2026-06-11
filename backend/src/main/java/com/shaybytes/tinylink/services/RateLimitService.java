package com.shaybytes.tinylink.services;

public interface RateLimitService {

    /**
     * Checks if a request is allowed based on rate limits
     */
    boolean isAllowed(String clientId);

    /**
     * Gets remaining requests for the current window
     */
    int getRemainingRequests(String clientId);

    /**
     * Gets time until reset in seconds
     */
    long getTimeUntilReset(String clientId);
}
