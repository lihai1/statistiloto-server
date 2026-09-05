package com.statistiloto.server.controller;

import com.statistiloto.server.dto.request.FeedbackRequest;
import com.statistiloto.server.dto.response.FeedbackResponse;
import com.statistiloto.server.service.FeedbackService;
import com.statistiloto.server.util.JwtUtils;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Feedback endpoints.
 * <ul>
 *   <li>POST /api/feedback — USER: submit feedback or lottery suggestion</li>
 *   <li>GET /api/feedback — ADMIN: list all feedback</li>
 *   <li>PUT /api/feedback/{id}/status — ADMIN: update status (new/read/archived)</li>
 *   <li>DELETE /api/feedback/{id} — ADMIN: delete feedback</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/feedback")
@Slf4j
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public FeedbackResponse submitFeedback(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody FeedbackRequest request) {
        String userSub = JwtUtils.userSub(jwt);
        log.info("[submitFeedback] START user={} type={}", userSub, request.type());
        return feedbackService.submit(userSub, request);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<FeedbackResponse> getAllFeedback() {
        log.info("[getAllFeedback] START");
        return feedbackService.getAll();
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public FeedbackResponse updateFeedbackStatus(@PathVariable Long id,
                                                  @RequestParam String status) {
        log.info("[updateFeedbackStatus] START id={} status={}", id, status);
        return feedbackService.updateStatus(id, status);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteFeedback(@PathVariable Long id) {
        log.info("[deleteFeedback] START id={}", id);
        feedbackService.delete(id);
    }
}
