package com.statistiloto.server.service;

import com.google.protobuf.Timestamp;
import com.statistiloto.lottery.v1.AnalyzeRequest;
import com.statistiloto.lottery.v1.DateWindow;
import com.statistiloto.lottery.v1.GenerateFormRequest;
import com.statistiloto.lottery.v1.GetStatisticsRequest;
import com.statistiloto.lottery.v1.LotteryServiceGrpc;
import com.statistiloto.lottery.v1.SimulateDrawResult;
import com.statistiloto.lottery.v1.SimulateRequest;
import com.statistiloto.lottery.v1.SimulateResponse;
import com.statistiloto.lottery.v1.SimulateSummary;
import com.statistiloto.lottery.v1.SimulateTierHit;
import com.statistiloto.lottery.v1.SimulateTierSummary;
import com.statistiloto.lottery.v1.Strength;
import com.statistiloto.server.dto.request.StatisticsRequest;
import com.statistiloto.server.dto.response.FrequencyEntryResponse;
import com.statistiloto.server.dto.response.FrequencyGroupResponse;
import com.statistiloto.server.dto.response.LotteryResultResponse;
import com.statistiloto.server.dto.response.PairResponse;
import com.statistiloto.server.dto.response.SimulateDrawResultResponse;
import com.statistiloto.server.dto.response.SimulateResultResponse;
import com.statistiloto.server.dto.response.SimulateSummaryResponse;
import com.statistiloto.server.dto.response.SimulateTierHitResponse;
import com.statistiloto.server.dto.response.SimulateTierSummaryResponse;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service that proxies lottery computation requests to the Go
 * lottery-stats-server via gRPC. Uses fully-qualified DTO names to
 * avoid collision with proto generated types of the same name.
 */
@Service
@Slf4j
public class LotteryClientService {

    private final LotteryServiceGrpc.LotteryServiceBlockingStub stub;

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    public LotteryClientService(ManagedChannel lotteryGrpcChannel) {
        this.stub = LotteryServiceGrpc.newBlockingStub(lotteryGrpcChannel);
    }

    /** Create a per-call stub with the user's JWT attached as gRPC metadata. */
    private LotteryServiceGrpc.LotteryServiceBlockingStub authStub(String jwtToken) {
        if (jwtToken == null || jwtToken.isBlank()) {
            return stub;
        }
        Metadata headers = new Metadata();
        String bearer = jwtToken.startsWith("Bearer ") ? jwtToken : "Bearer " + jwtToken;
        headers.put(AUTHORIZATION_KEY, bearer);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    public LotteryResultResponse generateForm(com.statistiloto.server.dto.request.GenerateFormRequest req, String jwtToken) {
        log.info("[generateForm] START howMany={} formType={} strength={} willBe={} from={} to={}",
            req.howMany(), req.formType(), req.strength(), req.willBe(), req.from(), req.to());
        try {
            var protoReq = GenerateFormRequest.newBuilder()
                .setHowMany(req.howMany())
                .setFormType(req.formType() != null ? req.formType() : 0)
                .addAllWillBe(req.willBe() != null ? req.willBe().stream().map(Integer::intValue).toList() : List.of())
                .setStrength(parseStrength(req.strength()))
                .setWindow(buildWindow(req.from(), req.to()))
                .build();

            log.info("[generateForm] Calling gRPC stub.generateForm...");
            var resp = authStub(jwtToken).generateForm(protoReq);
            log.info("[generateForm] gRPC response received: {} forms", resp.getFormsCount());

            List<List<Integer>> forms = resp.getFormsList().stream()
                .map(ns -> {
                    var nums = toIntList(ns.getNumbersList());
                    if (ns.hasStrong() && ns.getStrong() > 0) {
                        nums.add(ns.getStrong());
                    }
                    return nums;
                })
                .toList();

            log.info("[generateForm] SUCCESS returning {} forms", forms.size());
            return new LotteryResultResponse(forms, null, null);
        } catch (RuntimeException e) {
            log.error("[generateForm] ERROR msg={}", e.getMessage(), e);
            throw e;
        }
    }

    public LotteryResultResponse getStatistics(StatisticsRequest req, String jwtToken) {
        log.info("[getStatistics] START howMany={} formType={} strength={} from={} to={}",
            req.howMany(), req.formType(), req.strength(), req.from(), req.to());
        try {
            var protoReq = GetStatisticsRequest.newBuilder()
                .setHowMany(req.howMany())
                .setFormType(req.formType() != null ? req.formType() : 0)
                .setStrength(parseStrength(req.strength()))
                .setWindow(buildWindow(req.from(), req.to()))
                .build();

            log.info("[getStatistics] Calling gRPC stub.getStatistics...");
            var resp = authStub(jwtToken).getStatistics(protoReq);
            log.info("[getStatistics] gRPC response received: {} pairs", resp.getPairsCount());

            List<PairResponse> pairs = resp.getPairsList().stream()
                .map(p -> new PairResponse(toIntList(p.getNumbersList()), p.getCount()))
                .toList();

            log.info("[getStatistics] SUCCESS returning {} pairs, totalDrawsInRange={}",
                pairs.size(), resp.getTotalDrawsInRange());
            return new LotteryResultResponse(null, pairs, null, resp.getTotalDrawsInRange());
        } catch (RuntimeException e) {
            log.error("[getStatistics] ERROR msg={}", e.getMessage(), e);
            throw e;
        }
    }

    public LotteryResultResponse analyze(com.statistiloto.server.dto.request.AnalyzeRequest req, String jwtToken) {
        log.info("[analyze] START formSize={} form={} from={} to={}",
            req.form().size(), req.form(), req.from(), req.to());
        try {
            var protoReq = AnalyzeRequest.newBuilder()
                .addAllForm(req.form().stream().map(Integer::intValue).toList())
                .setWindow(buildWindow(req.from(), req.to()))
                .build();

            log.info("[analyze] Calling gRPC stub.analyze...");
            var resp = authStub(jwtToken).analyze(protoReq);
            log.info("[analyze] gRPC response received: {} frequency groups, archiveSize={}",
                resp.getFrequencyGroupsCount(), resp.getArchiveSize());

            List<FrequencyGroupResponse> frequencyGroups = resp.getFrequencyGroupsList().stream()
                .map(g -> new FrequencyGroupResponse(
                    g.getSize(),
                    g.getCombos(),
                    g.getEntriesList().stream()
                        .map(e -> new FrequencyEntryResponse(toIntList(e.getNumbersList()), e.getCount()))
                        .toList()))
                .toList();

            int totalEntries = frequencyGroups.stream()
                .mapToInt(g -> g.entries().size())
                .sum();

            log.info("[analyze] SUCCESS returning {} frequency groups ({} total entries), archiveSize={}",
                frequencyGroups.size(), totalEntries, resp.getArchiveSize());
            return new LotteryResultResponse(null, null, frequencyGroups, resp.getArchiveSize());
        } catch (RuntimeException e) {
            log.error("[analyze] ERROR msg={}", e.getMessage(), e);
            throw e;
        }
    }

    /** Convert a proto int32 list to a mutable List<Integer>. */
    private static List<Integer> toIntList(List<Integer> protoList) {
        return protoList.stream().map(Integer::valueOf).collect(Collectors.toList());
    }

    public SimulateResultResponse simulate(com.statistiloto.server.dto.request.SimulateRequest req, String jwtToken) {
        log.info("[simulate] START formSize={} strong={} archiveFrom={} archiveTo={} simulateFrom={} simulateTo={}",
            req.form().size(), req.strong(), req.archiveFrom(), req.archiveTo(),
            req.simulateFrom(), req.simulateTo());
        try {
            var builder = SimulateRequest.newBuilder()
                .addAllForm(req.form().stream().map(Integer::intValue).toList())
                .setStrong(req.strong() != null ? req.strong() : 0)
                .setArchiveWindow(buildWindow(req.archiveFrom(), req.archiveTo()));

            // simulate_window is optional — only set when the client supplied
            // a distinct backtest range. When unset, the Go service falls back
            // to the archive window (legacy single-window behavior).
            if (req.simulateFrom() != null || req.simulateTo() != null) {
                builder.setSimulateWindow(buildWindow(req.simulateFrom(), req.simulateTo()));
            }

            if (req.ticketCost() != null && req.ticketCost() > 0) {
                builder.setTicketCost(req.ticketCost());
            }
            if (req.prizeAmounts() != null && req.prizeAmounts().size() == 8) {
                builder.addAllPrizeAmounts(req.prizeAmounts());
            }

            var protoReq = builder.build();

            log.info("[simulate] Calling gRPC stub.simulate...");
            var resp = authStub(jwtToken).simulate(protoReq);
            log.info("[simulate] gRPC response received: {} draws, totalSpent={}, totalWon={}",
                resp.getDrawsCount(), resp.getSummary().getTotalSpent(), resp.getSummary().getTotalWon());

            var draws = resp.getDrawsList().stream()
                .map(this::toSimulateDrawResult)
                .toList();

            var summary = toSimulateSummary(resp.getSummary());

            log.info("[simulate] SUCCESS returning {} draws", draws.size());
            return new SimulateResultResponse(draws, summary);
        } catch (RuntimeException e) {
            log.error("[simulate] ERROR msg={}", e.getMessage(), e);
            throw e;
        }
    }

    private SimulateDrawResultResponse toSimulateDrawResult(SimulateDrawResult d) {
        return new SimulateDrawResultResponse(
            d.getDrawNumber(),
            d.hasDrawDate() ? LocalDate.ofInstant(
                java.time.Instant.ofEpochSecond(d.getDrawDate().getSeconds(), d.getDrawDate().getNanos()),
                java.time.ZoneOffset.UTC) : null,
            toIntList(d.getWinningNumbersList()),
            d.getWinningStrong(),
            d.getTierHitsList().stream()
                .map(h -> new SimulateTierHitResponse(
                    h.getTier(), h.getHits(), h.getAmountPerHit(), h.getTotal()))
                .toList(),
            d.getPrizeWon(),
            d.getTicketCost(),
            d.getUsedRealPrizes()
        );
    }

    private SimulateSummaryResponse toSimulateSummary(SimulateSummary s) {
        return new SimulateSummaryResponse(
            s.getTotalDraws(),
            s.getTotalCombinations(),
            s.getTotalSpent(),
            s.getTotalWon(),
            s.getNet(),
            s.getTierSummariesList().stream()
                .map(ts -> new SimulateTierSummaryResponse(
                    ts.getTier(), ts.getLabel(), ts.getTotalHits(), ts.getTotalAmount()))
                .toList(),
            s.getDrawsWithRealPrizes()
        );
    }

    private Strength parseStrength(String s) {
        if (s == null) return Strength.STRENGTH_UNSPECIFIED;
        return switch (s.toLowerCase()) {
            case "strong" -> Strength.STRONG;
            case "weak" -> Strength.WEAK;
            default -> Strength.STRENGTH_UNSPECIFIED;
        };
    }

    private DateWindow buildWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            log.debug("buildWindow: from or to is null, using default instance");
            return DateWindow.getDefaultInstance();
        }
        return DateWindow.newBuilder()
            .setFrom(toTimestamp(from))
            .setTo(toTimestamp(to))
            .build();
    }

    private Timestamp toTimestamp(LocalDate date) {
        return Timestamp.newBuilder()
            .setSeconds(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC))
            .build();
    }
}
