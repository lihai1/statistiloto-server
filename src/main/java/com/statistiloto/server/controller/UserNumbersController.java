package com.statistiloto.server.controller;

import com.statistiloto.server.dto.request.SaveNumbersRequest;
import com.statistiloto.server.dto.response.SavedNumbersResponse;
import com.statistiloto.server.service.SavedNumbersService;
import com.statistiloto.server.util.JwtUtils;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** CRUD for user-saved lottery numbers. Thin controllers — see {@code GlobalExceptionHandler}. */
@RestController
@RequestMapping("/api/user/numbers")
public class UserNumbersController {

    private static final Logger log = LoggerFactory.getLogger(UserNumbersController.class);

    private final SavedNumbersService savedNumbersService;

    public UserNumbersController(SavedNumbersService savedNumbersService) {
        this.savedNumbersService = savedNumbersService;
    }

    @GetMapping
    public List<SavedNumbersResponse> getMyNumbers(@AuthenticationPrincipal Jwt jwt) {
        String userSub = JwtUtils.requireUserSub(jwt);
        log.info("[getMyNumbers] START user={}", userSub);
        return savedNumbersService.getForUser(userSub);
    }

    @PostMapping
    public SavedNumbersResponse saveNumbers(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody SaveNumbersRequest request) {
        String userSub = JwtUtils.requireUserSub(jwt);
        log.info("[saveNumbers] START user={} category={} count={} willBe={} dateFrom={} dateTo={}",
            userSub, request.category(), request.numbers().size(),
            request.willBe(), request.dateFrom(), request.dateTo());
        return savedNumbersService.save(userSub, request);
    }

    @DeleteMapping("/{id}")
    public void deleteNumbers(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        String userSub = JwtUtils.requireUserSub(jwt);
        log.info("[deleteNumbers] START user={} id={}", userSub, id);
        savedNumbersService.delete(userSub, id);
    }
}
