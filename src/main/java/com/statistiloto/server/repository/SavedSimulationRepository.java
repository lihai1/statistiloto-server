package com.statistiloto.server.repository;

import com.statistiloto.server.entity.SavedSimulation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SavedSimulationRepository extends JpaRepository<SavedSimulation, Long> {
    List<SavedSimulation> findByUserSubOrderByCreatedAtDesc(String userSub);
}
