package com.statistiloto.server.controller;

import com.statistiloto.server.entity.UserProfile;
import com.statistiloto.server.repository.UserProfileRepository;
import com.statistiloto.server.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin-only endpoints for viewing archived user data.
 * Archived users have archived_at IS NOT NULL on their user_profile row.
 * Admin can list archived users and view details for audit purposes.
 */
@RestController
@RequestMapping("/api/admin/archived-users")
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
@RequiredArgsConstructor
public class AdminArchiveController {

    private final UserProfileRepository userProfileRepository;

    /** List all archived user profiles. */
    @GetMapping
    public List<Map<String, Object>> listArchivedUsers(@AuthenticationPrincipal Jwt jwt) {
        String adminSub = jwt != null ? jwt.getSubject() : "unknown";
        log.info("[admin.listArchivedUsers] START admin={}", adminSub);
        List<UserProfile> all = userProfileRepository.findAll();
        List<Map<String, Object>> result = all.stream()
            .filter(p -> p.getArchivedAt() != null)
            .map(p -> Map.<String, Object>of(
                "sub", p.getSub(),
                "displayName", p.getDisplayName() != null ? p.getDisplayName() : "",
                "archivedAt", p.getArchivedAt().toString(),
                "createdAt", p.getCreatedAt().toString()
            ))
            .toList();
        log.info("[admin.listArchivedUsers] SUCCESS admin={} count={}", adminSub, result.size());
        return result;
    }

    /** Get details of a specific archived user. */
    @GetMapping("/{sub}")
    public Map<String, Object> getArchivedUser(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable String sub) {
        String adminSub = jwt != null ? jwt.getSubject() : "unknown";
        log.info("[admin.getArchivedUser] START admin={} target={}", adminSub, sub);
        UserProfile profile = userProfileRepository.findById(sub)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + sub));
        if (profile.getArchivedAt() == null) {
            throw new IllegalStateException("User is not archived: " + sub);
        }
        log.info("[admin.getArchivedUser] SUCCESS admin={} target={}", adminSub, sub);
        return Map.of(
            "sub", profile.getSub(),
            "displayName", profile.getDisplayName() != null ? profile.getDisplayName() : "",
            "archivedAt", profile.getArchivedAt().toString(),
            "createdAt", profile.getCreatedAt().toString(),
            "archiveFrom", profile.getArchiveFrom() != null ? profile.getArchiveFrom().toString() : "",
            "archiveTo", profile.getArchiveTo() != null ? profile.getArchiveTo().toString() : ""
        );
    }
}
