package com.statistiloto.server.controller;

import com.statistiloto.server.dto.request.AnalyzeRequest;
import com.statistiloto.server.dto.request.GenerateFormRequest;
import com.statistiloto.server.dto.request.SimulateRequest;
import com.statistiloto.server.dto.request.StatisticsRequest;
import com.statistiloto.server.dto.response.LotteryResultResponse;
import com.statistiloto.server.dto.response.SimulateResultResponse;
import com.statistiloto.server.service.LotteryClientService;
import com.statistiloto.server.util.JwtUtils;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Lottery computation endpoints. These proxy to the Go lottery-stats-server
 * via gRPC. The user's JWT is validated by Spring Security before reaching
 * here.
 *
 * <p>Thin controllers: log start, delegate to {@link LotteryClientService}.
 * Exception mapping is handled by {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/generate")
@Slf4j
public class GenerateController {

    private final LotteryClientService lotteryClient;

    public GenerateController(LotteryClientService lotteryClient) {
        this.lotteryClient = lotteryClient;
    }

    @PostMapping("/form")
    public LotteryResultResponse generateForm(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody GenerateFormRequest request) {
        log.info("[generateForm] START user={} howMany={} formType={} strength={}",
            JwtUtils.userSub(jwt), request.howMany(), request.formType(), request.strength());
        return lotteryClient.generateForm(request);
    }

    @PostMapping("/statistics")
    public LotteryResultResponse getStatistics(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody StatisticsRequest request) {
        log.info("[getStatistics] START user={} howMany={} formType={} strength={}",
            JwtUtils.userSub(jwt), request.howMany(), request.formType(), request.strength());
        return lotteryClient.getStatistics(request);
    }

    @PostMapping("/analyze")
    public LotteryResultResponse analyze(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody AnalyzeRequest request) {
        log.info("[analyze] START user={} formSize={} from={} to={}",
            JwtUtils.userSub(jwt), request.form().size(), request.from(), request.to());
        return lotteryClient.analyze(request);
    }

    @PostMapping("/simulate")
    public SimulateResultResponse simulate(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody SimulateRequest request) {
        log.info("[simulate] START user={} formSize={} strong={} archiveFrom={} archiveTo={} simulateFrom={} simulateTo={}",
            JwtUtils.userSub(jwt), request.form().size(), request.strong(),
            request.archiveFrom(), request.archiveTo(),
            request.simulateFrom(), request.simulateTo());
        return lotteryClient.simulate(request);
    }
}
