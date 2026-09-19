package com.statistiloto.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.statistiloto.server.dto.response.ScraperStatusResponse;
import com.statistiloto.server.dto.response.ScraperTriggerResponse;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class ScraperQueueService {

    private final String redisUrl;
    private volatile RedisClient redisClient;
    private volatile long nextRedisProbeAt = 0L;
    private static final long REDIS_PROBE_BACKOFF_MS = 30_000L;
    private static final long XREAD_BLOCK_MS = 5_000L;
    private static final long STREAM_RUN_CAP_MS = 300_000L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public interface ScraperStreamSink {
        void send(String event, String data) throws IOException;
        void complete();
        void completeWithError(Throwable t);
        void onClose(Runnable cleanup);
    }

    private static class SseEmitterScraperSink implements ScraperStreamSink {
        private final SseEmitter emitter;

        SseEmitterScraperSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void send(String event, String data) throws IOException {
            emitter.send(SseEmitter.event().name(event).data(data));
        }

        @Override
        public void complete() {
            emitter.complete();
        }

        @Override
        public void completeWithError(Throwable t) {
            emitter.completeWithError(t);
        }

        @Override
        public void onClose(Runnable cleanup) {
            emitter.onCompletion(cleanup);
            emitter.onTimeout(cleanup);
            emitter.onError(e -> cleanup.run());
        }
    }

    @Autowired
    public ScraperQueueService(@Value("${redis.url:}") String redisUrl) {
        this.redisUrl = redisUrl;
    }

    /** Test-visible constructor injecting a pre-configured RedisClient. */
    ScraperQueueService(RedisClient testClient) {
        this.redisUrl = "test";
        this.redisClient = testClient;
    }

    public boolean isRedisAvailable() {
        if (redisUrl == null || redisUrl.isBlank()) return false;
        if (redisClient != null) return true;
        long now = System.currentTimeMillis();
        if (now < nextRedisProbeAt) return false;
        synchronized (this) {
            if (redisClient != null) return true;
            if (now < nextRedisProbeAt) return false;
            nextRedisProbeAt = now + REDIS_PROBE_BACKOFF_MS;
            try {
                RedisClient client = RedisClient.create(redisUrl);
                try (StatefulRedisConnection<String, String> conn = client.connect()) {
                    conn.sync().ping();
                }
                redisClient = client;
                log.info("[scraper-queue] Redis connected: {}", redisUrl);
            } catch (Exception e) {
                log.warn("[scraper-queue] Redis unavailable, retry in {}ms: {}", REDIS_PROBE_BACKOFF_MS, e.getMessage());
            }
            return redisClient != null;
        }
    }

    public ScraperTriggerResponse enqueue(String requestId, String sub) {
        if (!isRedisAvailable()) {
            throw new IllegalStateException("Redis is unavailable for scraper queue");
        }
        try (StatefulRedisConnection<String, String> conn = redisClient.connect()) {
            var sync = conn.sync();
            Map<String, String> reqBody = new HashMap<>();
            reqBody.put("request_id", requestId);
            reqBody.put("requested_by", sub != null ? sub : "");
            reqBody.put("source", "ui");

            sync.xadd("scraper:requests", XAddArgs.Builder.maxlen(1000).approximateTrimming(true), reqBody);

            String statusKey = "scraper:status:" + requestId;
            Map<String, String> statusBody = new HashMap<>();
            statusBody.put("request_id", requestId);
            statusBody.put("status", "queued");
            statusBody.put("phase", "queued");
            statusBody.put("requested_by", sub != null ? sub : "");
            statusBody.put("updated_at", String.valueOf(System.currentTimeMillis() / 1000L));

            sync.hset(statusKey, statusBody);
            sync.expire(statusKey, 86400);

            sync.set("scraper:latest", requestId);
            log.info("[scraper-queue] Enqueued scraper request_id={} by={}", requestId, sub);
            return new ScraperTriggerResponse(requestId, "queued");
        }
    }

    public ScraperStatusResponse getLatestStatus() {
        if (!isRedisAvailable()) {
            return new ScraperStatusResponse(null, "idle", null, 0, 0, null, null);
        }
        try (StatefulRedisConnection<String, String> conn = redisClient.connect()) {
            var sync = conn.sync();
            String latestId = sync.get("scraper:latest");
            if (latestId == null || latestId.isBlank()) {
                return new ScraperStatusResponse(null, "idle", null, 0, 0, null, null);
            }
            Map<String, String> map = sync.hgetall("scraper:status:" + latestId);
            if (map == null || map.isEmpty()) {
                return new ScraperStatusResponse(latestId, "idle", null, 0, 0, null, null);
            }
            Integer inserted = map.get("inserted") != null ? Integer.parseInt(map.get("inserted")) : 0;
            Integer prizes = map.get("prizes_written") != null ? Integer.parseInt(map.get("prizes_written")) : 0;
            Long updatedAt = map.get("updated_at") != null ? Long.parseLong(map.get("updated_at")) : null;

            return new ScraperStatusResponse(
                latestId,
                map.getOrDefault("status", "idle"),
                map.get("phase"),
                inserted,
                prizes,
                map.get("error"),
                updatedAt
            );
        } catch (Exception e) {
            log.warn("[scraper-queue] Failed to read latest status: {}", e.getMessage());
            return new ScraperStatusResponse(null, "idle", null, 0, 0, null, null);
        }
    }

    public SseEmitter streamEvents(String requestId) {
        SseEmitter emitter = new SseEmitter(STREAM_RUN_CAP_MS);
        streamEvents(requestId, new SseEmitterScraperSink(emitter));
        return emitter;
    }

    void streamEvents(String requestId, ScraperStreamSink sink) {
        if (!isRedisAvailable()) {
            emitTerminalError(sink, "Redis unavailable");
            return;
        }

        AtomicBoolean stopped = new AtomicBoolean(false);
        StatefulRedisConnection<String, String> conn;
        try {
            conn = redisClient.connect();
        } catch (Exception e) {
            emitTerminalError(sink, "Redis connection failed: " + e.getMessage());
            return;
        }
        sink.onClose(() -> {
            stopped.set(true);
            conn.close();
        });

        Thread.ofVirtual().name("scraper-xread-" + requestId).start(() -> {
            String eventsKey = "scraper:events:" + requestId;
            String statusKey = "scraper:status:" + requestId;
            String lastId = "0-0";
            long startedAt = System.currentTimeMillis();

            try {
                while (!stopped.get()) {
                    List<StreamMessage<String, String>> messages = conn.sync().xread(
                        XReadArgs.Builder.block(XREAD_BLOCK_MS).count(100),
                        XReadArgs.StreamOffset.from(eventsKey, lastId));

                    if (stopped.get()) break;

                    if (messages != null && !messages.isEmpty()) {
                        for (StreamMessage<String, String> m : messages) {
                            lastId = m.getId();
                            Map<String, String> body = m.getBody();
                            if (body == null) continue;
                            String event = body.get("event");
                            String json = objectMapper.writeValueAsString(body);
                            sink.send(event != null ? event : "progress", json);

                            if ("done".equals(event) || "error".equals(event)) {
                                sink.complete();
                                log.info("[scraper-queue] Stream completed normally for requestId={} event={}", requestId, event);
                                return;
                            }
                        }
                    }

                    // On block slice timeout, check status hash as authoritative liveness/terminal source
                    Map<String, String> statusMap = conn.sync().hgetall(statusKey);
                    if (statusMap != null && !statusMap.isEmpty()) {
                        String status = statusMap.get("status");
                        if ("done".equals(status)) {
                            sink.send("done", objectMapper.writeValueAsString(statusMap));
                            sink.complete();
                            log.info("[scraper-queue] Synthesized terminal 'done' from status hash for requestId={}", requestId);
                            return;
                        } else if ("error".equals(status) || "interrupted".equals(status)) {
                            sink.send("error", objectMapper.writeValueAsString(statusMap));
                            sink.complete();
                            log.info("[scraper-queue] Synthesized terminal 'error' from status hash for requestId={}", requestId);
                            return;
                        }
                    }

                    if (System.currentTimeMillis() - startedAt > STREAM_RUN_CAP_MS) {
                        emitTerminalError(sink, "Scraper stream timeout (capped at 5 minutes)");
                        return;
                    }
                }
            } catch (IllegalStateException e) {
                log.debug("[scraper-queue] Emitter already closed for requestId={}", requestId);
            } catch (Exception e) {
                if (!stopped.get()) {
                    log.error("[scraper-queue] Stream relay error for requestId={}: {}", requestId, e.getMessage());
                    emitTerminalError(sink, "Relay error: " + e.getMessage());
                }
            } finally {
                try { conn.close(); } catch (Exception ignored) {}
            }
        });
    }

    private void emitTerminalError(ScraperStreamSink sink, String error) {
        try {
            Map<String, String> errMap = new HashMap<>();
            errMap.put("event", "error");
            errMap.put("message", error);
            sink.send("error", objectMapper.writeValueAsString(errMap));
            sink.complete();
        } catch (Exception e) {
            try { sink.completeWithError(e); } catch (Exception ignored) {}
        }
    }
}
