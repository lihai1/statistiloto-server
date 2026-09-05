package com.statistiloto.server.repository;

import com.statistiloto.server.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    List<Feedback> findAllByOrderByCreatedAtDesc();
    List<Feedback> findByUserSubOrderByCreatedAtDesc(String userSub);
}
