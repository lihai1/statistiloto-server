package com.statistiloto.server.controller;

import com.statistiloto.server.dto.response.ScraperStatusResponse;
import com.statistiloto.server.dto.response.ScraperTriggerResponse;
import com.statistiloto.server.service.ScraperQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/scraper")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin Scraper", description = "Admin lottery scraper queue management and monitoring")
public class AdminScraperController {

    private final ScraperQueueService scraperQueueService;

    @PostMapping("/trigger")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Trigger a lottery scraper run via Redis queue")
    public ResponseEntity<ScraperTriggerResponse> trigger(@AuthenticationPrincipal Jwt jwt) {
        String sub = jwt != null ? jwt.getSubject() : "admin";
        log.info("[admin-scraper.trigger] START by={}", sub);

        ScraperStatusResponse latest = scraperQueueService.getLatestStatus();
        if (latest != null && ("queued".equals(latest.status()) || "running".equals(latest.status()))) {
            log.info("[admin-scraper.trigger] Scraper already active id={} status={}", latest.requestId(), latest.status());
            return ResponseEntity.ok(new ScraperTriggerResponse(latest.requestId(), latest.status()));
        }

        String requestId = UUID.randomUUID().toString().substring(0, 12);
        ScraperTriggerResponse response = scraperQueueService.enqueue(requestId, sub);
        log.info("[admin-scraper.trigger] SUCCESS id={}", requestId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get current/latest scraper status")
    public ResponseEntity<ScraperStatusResponse> getStatus() {
        return ResponseEntity.ok(scraperQueueService.getLatestStatus());
    }

    @GetMapping("/stream/{requestId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Stream scraper execution progress via SSE")
    public SseEmitter streamEvents(@PathVariable String requestId) {
        log.info("[admin-scraper.stream] START id={}", requestId);
        return scraperQueueService.streamEvents(requestId);
    }
}
