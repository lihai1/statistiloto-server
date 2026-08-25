package com.statistiloto.server.util;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

/**
 * Shared JWT extraction helpers used by controllers and security config.
 *
 * <p>Centralizes subject extraction, Bearer header construction, and realm-role
 * extraction so callers don't repeat the same null-check / claim-walking logic.
 */
public final class JwtUtils {

    /** Anonymous subject used when no JWT principal is available (e.g. pre-auth). */
    public static final String ANONYMOUS = "anonymous";

    private JwtUtils() {}

    /**
     * Extract the JWT subject, falling back to {@link #ANONYMOUS} when the
     * principal is null (matches the prior inline behavior in the controllers).
     */
    public static String userSub(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : ANONYMOUS;
    }

    /**
     * Require a non-null JWT subject. Throws {@link IllegalStateException} when
     * the principal or subject is missing — used by endpoints that cannot
     * proceed anonymously (saved-numbers CRUD).
     */
    public static String requireUserSub(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new IllegalStateException("JWT subject is null");
        }
        return jwt.getSubject();
    }

    /** Build a {@code Authorization: Bearer <token>} header value. */
    public static String bearer(Jwt jwt) {
        return "Bearer " + jwt.getTokenValue();
    }

    /**
     * Extract realm roles from the {@code realm_access.roles} claim as a list
     * of strings. Returns an empty list when the claim is absent or malformed.
     */
    @SuppressWarnings("unchecked")
    public static List<String> realmRoles(Jwt jwt) {
        if (jwt == null) return List.of();
        var realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess instanceof Map<?, ?> map) {
            Object roles = map.get("roles");
            if (roles instanceof List<?> list) {
                return (List<String>) list;
            }
        }
        return List.of();
    }
}
