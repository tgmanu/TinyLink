package com.shaybytes.tinylink.dto;

import com.shaybytes.tinylink.models.ClickEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlAnalyticsResponse {
    private String shortCode;
    private String originalUrl;
    private int totalClicks;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private List<ClickEvent> recentClicks;
    private Map<String, Integer> clicksByCountry;
    private Map<String, Integer> clicksByReferrer;
    private Map<String, Integer> clicksByHour;
    private Map<String, Integer> clicksByDay;
}
