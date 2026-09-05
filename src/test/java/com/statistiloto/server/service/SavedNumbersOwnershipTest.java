package com.statistiloto.server.service;

import com.statistiloto.server.dto.request.SaveNumbersRequest;
import com.statistiloto.server.dto.response.SavedNumbersResponse;
import com.statistiloto.server.entity.SavedNumbers;
import com.statistiloto.server.repository.SavedNumbersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression tests for saved-numbers ownership and duplicate validation (#3, #4).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>User A cannot see user B's saved numbers (#3)</li>
 *   <li>User A cannot delete user B's saved numbers (#3)</li>
 *   <li>Internal duplicates within a numbers list are rejected (#4)</li>
 *   <li>Exact-duplicate sets (same user, category, numbers, willBe) are rejected (#4)</li>
 *   <li>Different categories allow the same numbers (#4)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SavedNumbersOwnershipTest {

    @Mock
    private SavedNumbersRepository repository;
    @Mock
    private UserProfileService userProfileService;

    @InjectMocks
    private SavedNumbersService service;

    private static final String USER_A = "sub-user-a";
    private static final String USER_B = "sub-user-b";

    @Test
    void getForUser_onlyReturnsOwnNumbers() {
        // User A has 2 saved sets
        SavedNumbers set1 = makeSavedNumbers(1L, USER_A, "lucky", List.of(1, 2, 3));
        SavedNumbers set2 = makeSavedNumbers(2L, USER_A, "user-generated", List.of(4, 5, 6));
        when(repository.findByUserSubOrderByCreatedAtDesc(USER_A)).thenReturn(List.of(set1, set2));

        List<SavedNumbersResponse> result = service.getForUser(USER_A);

        assertEquals(2, result.size());
        verify(repository).findByUserSubOrderByCreatedAtDesc(USER_A);
        verify(repository, never()).findByUserSubOrderByCreatedAtDesc(USER_B);
    }

    @Test
    void delete_userCannotDeleteOthersNumbers() {
        SavedNumbers entity = makeSavedNumbers(10L, USER_B, "lucky", List.of(7, 8, 9));
        when(repository.findById(10L)).thenReturn(java.util.Optional.of(entity));

        // User A tries to delete user B's record
        assertThrows(SecurityException.class, () -> service.delete(USER_A, 10L));
        verify(repository, never()).delete(any());
    }

    @Test
    void delete_ownerCanDeleteOwnNumbers() {
        SavedNumbers entity = makeSavedNumbers(10L, USER_A, "lucky", List.of(7, 8, 9));
        when(repository.findById(10L)).thenReturn(java.util.Optional.of(entity));

        service.delete(USER_A, 10L);

        verify(repository).delete(entity);
    }

    @Test
    void save_rejectsInternalDuplicates() {
        SaveNumbersRequest req = new SaveNumbersRequest("lucky", List.of(1, 2, 3, 3), null, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.save(USER_A, req));
        assertTrue(ex.getMessage().contains("duplicates"));
        verify(repository, never()).save(any());
    }

    @Test
    void save_rejectsExactDuplicateSet() {
        // Existing set for user A in "lucky" category
        SavedNumbers existing = makeSavedNumbers(1L, USER_A, "lucky", List.of(1, 2, 3));
        existing.setWillBe(List.of(7));
        when(repository.findByUserSubOrderByCreatedAtDesc(USER_A)).thenReturn(List.of(existing));

        // Same numbers + willBe → should be rejected
        SaveNumbersRequest req = new SaveNumbersRequest("lucky", List.of(1, 2, 3), List.of(7), null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.save(USER_A, req));
        assertTrue(ex.getMessage().contains("already exists"));
        verify(repository, never()).save(any());
    }

    @Test
    void save_allowsSameNumbersInDifferentCategory() {
        // Existing set for user A in "lucky" category
        SavedNumbers existing = makeSavedNumbers(1L, USER_A, "lucky", List.of(1, 2, 3));
        when(repository.findByUserSubOrderByCreatedAtDesc(USER_A)).thenReturn(List.of(existing));

        // Same numbers but different category → should be allowed
        SaveNumbersRequest req = new SaveNumbersRequest("user-generated", List.of(1, 2, 3), null, null, null);
        SavedNumbers newEntity = makeSavedNumbers(2L, USER_A, "user-generated", List.of(1, 2, 3));
        when(repository.save(any())).thenReturn(newEntity);

        SavedNumbersResponse result = service.save(USER_A, req);

        assertNotNull(result);
        assertEquals("user-generated", result.category());
        verify(repository).save(any());
    }

    @Test
    void save_nullUserSub_throws() {
        SaveNumbersRequest req = new SaveNumbersRequest("lucky", List.of(1, 2, 3), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.save(null, req));
    }

    private SavedNumbers makeSavedNumbers(Long id, String userSub, String category, List<Integer> numbers) {
        SavedNumbers entity = new SavedNumbers(userSub, category, numbers);
        entity.setId(id);
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
