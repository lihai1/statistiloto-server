package com.statistiloto.server.controller;

import com.statistiloto.server.dto.request.SaveSimulationRequest;
import com.statistiloto.server.dto.response.SavedSimulationResponse;
import com.statistiloto.server.service.SavedSimulationService;
import com.statistiloto.server.util.JwtUtils;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** CRUD for user-saved simulation results. */
@RestController
@RequestMapping("/api/user/simulations")
public class SavedSimulationController {

    private static final Logger log = LoggerFactory.getLogger(SavedSimulationController.class);

    private final SavedSimulationService savedSimulationService;

    public SavedSimulationController(SavedSimulationService savedSimulationService) {
        this.savedSimulationService = savedSimulationService;
    }

    @GetMapping
    public List<SavedSimulationResponse> getMySimulations(@AuthenticationPrincipal Jwt jwt) {
        String userSub = JwtUtils.requireUserSub(jwt);
        log.info("[getMySimulations] START user={}", userSub);
        return savedSimulationService.getForUser(userSub);
    }

    @PostMapping
    public SavedSimulationResponse saveSimulation(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody SaveSimulationRequest request) {
        String userSub = JwtUtils.requireUserSub(jwt);
        log.info("[saveSimulation] START user={}", userSub);
        return savedSimulationService.save(userSub, request);
    }

    @DeleteMapping("/{id}")
    public void deleteSimulation(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        String userSub = JwtUtils.requireUserSub(jwt);
        log.info("[deleteSimulation] START user={} id={}", userSub, id);
        savedSimulationService.delete(userSub, id);
    }
}
