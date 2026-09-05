package com.statistiloto.server.service;

import com.statistiloto.server.dto.response.SavedSimulationResponse;
import com.statistiloto.server.entity.SavedSimulation;
import com.statistiloto.server.repository.SavedSimulationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Ownership tests for {@link SavedSimulationService}.
 *
 * <p>Verifies that a user cannot delete another user's saved simulation and
 * that {@code getForUser} only ever queries the calling user's records.
 * Mirrors the style of {@code SavedNumbersOwnershipTest}.
 */
@ExtendWith(MockitoExtension.class)
class SavedSimulationOwnershipTest {

    @Mock
    private SavedSimulationRepository repository;
    @Mock
    private UserProfileService userProfileService;

    @InjectMocks
    private SavedSimulationService service;

    private static final String USER_A = "sub-user-a";
    private static final String USER_B = "sub-user-b";

    @Test
    void delete_crossUser_throwsSecurityException() {
        // Simulation owned by user B
        SavedSimulation entity = makeSavedSimulation(10L, USER_B, "{}", "{}");
        when(repository.findById(10L)).thenReturn(java.util.Optional.of(entity));

        // User A tries to delete user B's simulation
        assertThrows(SecurityException.class, () -> service.delete(USER_A, 10L));
        verify(repository, never()).delete(any());
    }

    @Test
    void findByUserSub_onlyReturnsOwn() {
        // User A has 2 saved simulations
        SavedSimulation sim1 = makeSavedSimulation(1L, USER_A, "{\"a\":1}", "{\"s\":1}");
        SavedSimulation sim2 = makeSavedSimulation(2L, USER_A, "{\"a\":2}", "{\"s\":2}");
        when(repository.findByUserSubOrderByCreatedAtDesc(USER_A)).thenReturn(List.of(sim1, sim2));

        List<SavedSimulationResponse> result = service.getForUser(USER_A);

        assertEquals(2, result.size());
        verify(repository).findByUserSubOrderByCreatedAtDesc(USER_A);
        verify(repository, never()).findByUserSubOrderByCreatedAtDesc(USER_B);
    }

    private SavedSimulation makeSavedSimulation(Long id, String userSub, String requestJson, String summaryJson) {
        SavedSimulation entity = new SavedSimulation(userSub, requestJson, summaryJson);
        entity.setId(id);
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
