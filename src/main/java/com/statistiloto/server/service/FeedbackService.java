package com.statistiloto.server.service;

import com.statistiloto.server.dto.request.FeedbackRequest;
import com.statistiloto.server.dto.response.FeedbackResponse;
import com.statistiloto.server.entity.Feedback;
import com.statistiloto.server.repository.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Service for user feedback submission and admin feedback management. */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackRepository repository;

    public FeedbackResponse submit(String userSub, FeedbackRequest req) {
        log.info("[feedback.submit] START user={} type={}", userSub, req.type());
        Feedback entity = new Feedback(userSub, req.type(), req.message());
        entity.setPage(req.page());
        entity.setLanguage(req.language());
        entity.setTier(req.tier());
        entity.setExtra(req.extra());
        Feedback saved = repository.save(entity);
        log.info("[feedback.submit] SUCCESS id={} user={} type={}", saved.getId(), userSub, req.type());
        return toResponse(saved);
    }

    public List<FeedbackResponse> getAll() {
        log.info("[feedback.getAll] START");
        return repository.findAllByOrderByCreatedAtDesc().stream()
            .map(this::toResponse)
            .toList();
    }

    public FeedbackResponse updateStatus(Long id, String status) {
        log.info("[feedback.updateStatus] START id={} status={}", id, status);
        Feedback entity = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Feedback not found: " + id));
        entity.setStatus(status);
        Feedback saved = repository.save(entity);
        log.info("[feedback.updateStatus] SUCCESS id={} status={}", id, status);
        return toResponse(saved);
    }

    public void delete(Long id) {
        log.info("[feedback.delete] START id={}", id);
        Feedback entity = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Feedback not found: " + id));
        repository.delete(entity);
        log.info("[feedback.delete] SUCCESS id={}", id);
    }

    private FeedbackResponse toResponse(Feedback entity) {
        return new FeedbackResponse(
            entity.getId(),
            entity.getUserSub(),
            entity.getType(),
            entity.getStatus(),
            entity.getPage(),
            entity.getLanguage(),
            entity.getTier(),
            entity.getMessage(),
            entity.getExtra(),
            entity.getCreatedAt()
        );
    }
}
