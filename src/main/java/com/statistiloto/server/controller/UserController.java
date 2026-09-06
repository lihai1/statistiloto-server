package com.statistiloto.server.controller;

import com.statistiloto.server.dto.request.UpdateArchiveWindowRequest;
import com.statistiloto.server.dto.response.UserProfileResponse;
import com.statistiloto.server.entity.UserProfile;
import com.statistiloto.server.service.UserProfileService;
import com.statistiloto.server.util.JwtUtils;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Returns the authenticated user's profile from the JWT claims. */
@RestController
@RequestMapping("/api")
@Slf4j
public class UserController {

    private final UserProfileService userProfileService;

    public UserController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping("/me")
    public UserProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        log.info("[me] START jwt={}", jwt != null ? "present" : "null");
        if (jwt == null) {
            log.warn("[me] ERROR jwt is null — AuthenticationPrincipal not resolved");
            throw new IllegalStateException("JWT principal is null");
        }
        String sub = jwt.getSubject();
        java.util.List<String> roles = JwtUtils.realmRoles(jwt);
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        if (name == null) {
            name = jwt.getClaimAsString("preferred_username");
        }

        // Ensure a user_profile row exists (auto-create on first login).
        UserProfile profile = userProfileService.ensureProfile(sub, name);

        log.info("[me] SUCCESS sub={} email={} name={} roles={}", sub, email, name, roles);
        return new UserProfileResponse(
            sub, email, name, roles,
            profile.getArchiveFrom(),
            profile.getArchiveTo()
        );
    }

    /**
     * Update the authenticated user's preferred archive date range.
     * Persisted so the same window is restored across sessions and devices.
     */
    @PutMapping("/me/archive")
    public UserProfileResponse updateArchive(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateArchiveWindowRequest req
    ) {
        log.info("[updateArchive] START sub={} from={} to={}",
            jwt != null ? jwt.getSubject() : "null", req.from(), req.to());
        if (jwt == null) {
            throw new IllegalStateException("JWT principal is null");
        }
        String sub = jwt.getSubject();
        UserProfile profile = userProfileService.updateArchiveWindow(
            sub, req.fromDate(), req.toDate()
        );
        java.util.List<String> roles = JwtUtils.realmRoles(jwt);
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        if (name == null) {
            name = jwt.getClaimAsString("preferred_username");
        }
        log.info("[updateArchive] SUCCESS sub={}", sub);
        return new UserProfileResponse(
            sub, email, name, roles,
            profile.getArchiveFrom(),
            profile.getArchiveTo()
        );
    }

    /**
     * Stateless JWT verification endpoint used by Traefik's ForwardAuth
     * middleware for edge validation. Returns 200 if the token is valid
     * (Spring Security already validated it); 401 otherwise.
     */
    @GetMapping("/auth/verify")
    public Map<String, Object> verify(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            log.warn("[verify] No JWT principal — returning unauthenticated");
            return Map.of("authenticated", false);
        }
        log.info("[verify] SUCCESS sub={} email={}", jwt.getSubject(), jwt.getClaimAsString("email"));
        return Map.of(
            "authenticated", true,
            "sub", jwt.getSubject(),
            "email", jwt.getClaimAsString("email")
        );
    }

    /**
     * Soft-archive the authenticated user's account. Sets archived_at on all
     * user-owned data (profile, saved numbers, saved simulations, feedback).
     * The Keycloak account is NOT deleted — on re-login, ensureProfile
     * reactivates the profile with fresh defaults. Admin can view archived
     * data via GET /api/admin/archived-users.
     */
    @DeleteMapping("/me")
    public Map<String, String> deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new IllegalStateException("JWT principal is null");
        }
        String sub = jwt.getSubject();
        log.info("[deleteAccount] START sub={}", sub);
        userProfileService.archiveUser(sub);
        log.info("[deleteAccount] SUCCESS sub={} — account archived", sub);
        return Map.of("status", "archived", "sub", sub);
    }
}
