package com.statistiloto.server.service;

import com.statistiloto.server.entity.UserProfile;
import com.statistiloto.server.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Ensures a {@link UserProfile} row exists for an authenticated user.
 * Called on first login (via /api/me) and before any save operation
 * to satisfy the foreign key constraint on saved_numbers.user_sub.
 */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class UserProfileService {

    private final UserProfileRepository repository;

    /**
     * Ensure a user profile exists for the given subject. Creates one
     * if missing, updates the display name if it has changed.
     */
    public UserProfile ensureProfile(String sub, String displayName) {
        log.info("[ensureProfile] START sub={} name={}", sub, displayName);
        if (sub == null) {
            log.error("[ensureProfile] FAIL — sub is null");
            throw new IllegalArgumentException("User subject cannot be null");
        }
        try {
            return repository.findById(sub).orElseGet(() -> {
                log.info("[ensureProfile] Creating new profile for sub={}", sub);
                UserProfile profile = new UserProfile(sub, displayName);
                return repository.save(profile);
            });
        } catch (RuntimeException e) {
            log.error("[ensureProfile] ERROR sub={} msg={}", sub, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Update the user's preferred archive date range. Ensures the profile
     * exists first (auto-creates with a null display name if missing).
     */
    public UserProfile updateArchiveWindow(String sub, LocalDate from, LocalDate to) {
        log.info("[updateArchiveWindow] START sub={} from={} to={}", sub, from, to);
        if (sub == null) {
            throw new IllegalArgumentException("User subject cannot be null");
        }
        UserProfile profile = repository.findById(sub).orElseGet(() -> {
            log.info("[updateArchiveWindow] Creating new profile for sub={}", sub);
            UserProfile p = new UserProfile(sub, null);
            return repository.save(p);
        });
        profile.setArchiveFrom(from);
        profile.setArchiveTo(to);
        profile.setUpdatedAt(Instant.now());
        UserProfile saved = repository.save(profile);
        log.info("[updateArchiveWindow] SUCCESS sub={}", sub);
        return saved;
    }
}
