package com.statistiloto.server.service;

import com.statistiloto.server.dto.request.SaveSimulationRequest;
import com.statistiloto.server.dto.response.SavedSimulationResponse;
import com.statistiloto.server.entity.SavedSimulation;
import com.statistiloto.server.repository.SavedSimulationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** CRUD operations for user-saved simulation results. */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class SavedSimulationService {

    private final SavedSimulationRepository repository;
    private final UserProfileService userProfileService;

    public List<SavedSimulationResponse> getForUser(String userSub) {
        log.info("[sim.getForUser] START user={}", userSub);
        List<SavedSimulationResponse> result = repository.findByUserSubOrderByCreatedAtDesc(userSub).stream()
            .map(this::toResponse)
            .toList();
        log.info("[sim.getForUser] SUCCESS user={} count={}", userSub, result.size());
        return result;
    }

    public SavedSimulationResponse save(String userSub, SaveSimulationRequest req) {
        log.info("[sim.save] START user={}", userSub);
        if (userSub == null) {
            throw new IllegalArgumentException("User subject cannot be null");
        }
        userProfileService.ensureProfile(userSub, null);
        SavedSimulation entity = new SavedSimulation(userSub, req.requestJson(), req.summaryJson());
        SavedSimulation saved = repository.save(entity);
        log.info("[sim.save] SUCCESS user={} id={}", userSub, saved.getId());
        return toResponse(saved);
    }

    public void delete(String userSub, Long id) {
        log.info("[sim.delete] START user={} id={}", userSub, id);
        SavedSimulation entity = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Saved simulation not found: " + id));
        if (!entity.getUserSub().equals(userSub)) {
            log.warn("[sim.delete] FORBIDDEN — user={} attempted to delete id={} owned by {}",
                userSub, id, entity.getUserSub());
            throw new SecurityException("Not authorized to delete this resource");
        }
        repository.delete(entity);
        log.info("[sim.delete] SUCCESS user={} id={}", userSub, id);
    }

    private SavedSimulationResponse toResponse(SavedSimulation entity) {
        return new SavedSimulationResponse(
            entity.getId(),
            entity.getRequestJson(),
            entity.getSummaryJson(),
            entity.getCreatedAt()
        );
    }
}
