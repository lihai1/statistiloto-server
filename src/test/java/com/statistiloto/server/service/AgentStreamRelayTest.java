package com.statistiloto.server.service;

import com.statistiloto.server.dto.request.AgentChatRequest;
import com.statistiloto.server.service.AgentClientService.StreamSink;
import com.sun.net.httpserver.HttpServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Redis Streams agent relay in {@link AgentClientService}.
 *
 * <p>Uses a real Redis (Testcontainers) and a fake agent implemented with the
 * JDK {@link HttpServer}. The fake agent honours the Accept-header contract:
 * {@code application/json} → returns {@code {thread_id, channel}} immediately.
 * A recording {@link StreamSink} captures what the relay would emit as SSE.
 *
 * <p>Skipped entirely when Docker is unavailable.
 */
class AgentStreamRelayTest {

    private static GenericContainer<?> redisContainer;
    private static FakeAgent fakeAgent;
    private static RedisClient testRedis;
    private static String redisUrl;

    private AgentClientService service;

    /**
     * Redis URL resolution: TEST_REDIS_URL env wins (dev shells, docker
     * networks), otherwise a Testcontainers Redis is started. Tests are
     * skipped (not failed) when neither is available.
     */
    private static String resolveRedisUrl() {
        String env = System.getenv("TEST_REDIS_URL");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);
                redisContainer.start();
                return "redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Fake agent ──────────────────────────────────────────────

    /** Minimal fake agent: records requests, serves queued canned responses. */
    static class FakeAgent {
        record Req(String method, String path, String accept, String body) {}
        record Resp(int status, String contentType, byte[] body) {}

        final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
        final Queue<Resp> responses = new ConcurrentLinkedQueue<>();
        private HttpServer server;

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                byte[] reqBody = ex.getRequestBody().readAllBytes();
                requests.add(new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst("Accept"), new String(reqBody, StandardCharsets.UTF_8)));
                Resp resp = responses.poll();
                if (resp == null) {
                    resp = new Resp(500, "text/plain", "no canned response".getBytes(StandardCharsets.UTF_8));
                }
                ex.getResponseHeaders().set("Content-Type", resp.contentType());
                ex.sendResponseHeaders(resp.status(), resp.body().length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(resp.body());
                }
                ex.close();
            });
            server.start();
        }

        void enqueue(int status, String contentType, String body) {
            responses.add(new Resp(status, contentType, body.getBytes(StandardCharsets.UTF_8)));
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            if (server != null) server.stop(0);
        }
    }

    // ── Recording sink ──────────────────────────────────────────

    /** Captures every relay event (name + data) and terminal callbacks. */
    static class RecordingSink implements StreamSink {
        record Event(String name, String data) {}

        final List<Event> events = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        volatile Throwable error;

        @Override
        public void send(String event, String data) {
            events.add(new Event(event, data));
        }

        @Override
        public void complete() {
            completed.countDown();
        }

        @Override
        public void completeWithError(Throwable t) {
            error = t;
            completed.countDown();
        }

        @Override
        public void onClose(Runnable cleanup) {
            // no-op: test sink has no resources to release
        }

        boolean awaitComplete(long seconds) throws InterruptedException {
            return completed.await(seconds, TimeUnit.SECONDS);
        }

        List<String> eventNames() {
            List<String> names = new ArrayList<>();
            for (Event e : events) names.add(e.name());
            return names;
        }
    }

    // ── Setup ───────────────────────────────────────────────────

    @BeforeAll
    static void startInfra() throws IOException {
        fakeAgent = new FakeAgent();
        fakeAgent.start();
        redisUrl = resolveRedisUrl();
        Assumptions.assumeTrue(redisUrl != null,
            "No Redis available — set TEST_REDIS_URL or run with Docker for Testcontainers");
        testRedis = RedisClient.create(redisUrl);
    }

    @AfterAll
    static void stopInfra() {
        if (testRedis != null) testRedis.shutdown();
        if (fakeAgent != null) fakeAgent.stop();
        if (redisContainer != null) redisContainer.stop();
    }

    @BeforeEach
    void setUp() {
        service = new AgentClientService(fakeAgent.baseUrl(), 300_000, redisUrl);
        fakeAgent.requests.clear();
    }

    private void xadd(String channel, String eventJson) {
        try (StatefulRedisConnection<String, String> conn = testRedis.connect()) {
            conn.sync().xadd(channel, Map.of("data", eventJson));
        }
    }

    private AgentChatRequest req(String sessionId) {
        return new AgentChatRequest(sessionId, "hi", null, null, null, null);
    }

    // ── Tests ───────────────────────────────────────────────────

    @Test
    void replaysEventsPublishedBeforeRelayStarts() throws Exception {
        // Simulate the fast-path race: the run already finished and published
        // progress + done BEFORE the BFF even got the channel back. Pub/Sub
        // would have lost these; the stream must replay them from 0-0.
        String channel = "agent:stream:u:pre-finished";
        xadd(channel, "{\"event\":\"progress\",\"node\":\"direct_tool\"}");
        xadd(channel, "{\"event\":\"done\",\"response\":\"ok\",\"thread_id\":\"u:pre-finished\"}");
        fakeAgent.enqueue(200, "application/json",
            "{\"thread_id\":\"u:pre-finished\",\"channel\":\"" + channel + "\"}");

        RecordingSink sink = new RecordingSink();
        service.chatStream(req("pre-finished"), "Bearer t", sink);

        assertTrue(sink.awaitComplete(15), "sink should complete on done");
        assertEquals(List.of("progress", "done"), sink.eventNames());
    }

    @Test
    void forwardsTokenAndPausedEvents() throws Exception {
        String channel = "agent:stream:u:hitl";
        fakeAgent.enqueue(200, "application/json",
            "{\"thread_id\":\"u:hitl\",\"channel\":\"" + channel + "\"}");

        RecordingSink sink = new RecordingSink();
        service.chatStream(req("hitl"), "Bearer t", sink);

        // Publish after the relay is running — exercises the live XREAD path.
        Thread.sleep(300);
        xadd(channel, "{\"event\":\"progress\",\"node\":\"plan\"}");
        xadd(channel, "{\"event\":\"token\",\"delta\":\"Saving \"}");
        xadd(channel, "{\"event\":\"paused\",\"thread_id\":\"u:hitl\",\"action\":{\"tool\":\"save_numbers\",\"args\":{\"numbers\":[1,2,3]}}}");

        assertTrue(sink.awaitComplete(15), "sink should complete on paused");
        assertEquals(List.of("progress", "token", "paused"), sink.eventNames());
        assertTrue(sink.events.get(2).data().contains("save_numbers"),
            "paused must carry the action payload");
    }

    @Test
    void missingChannelEmitsErrorWithoutRepost() throws Exception {
        // 200 with no channel — the graph is already running upstream. The BFF
        // must emit a terminal error and MUST NOT re-POST (would run twice).
        fakeAgent.enqueue(200, "application/json", "{}");

        RecordingSink sink = new RecordingSink();
        service.chatStream(req("no-channel"), "Bearer t", sink);

        assertTrue(sink.awaitComplete(15), "sink should complete with error");
        assertEquals(List.of("error"), sink.eventNames());
        Thread.sleep(300); // give a hypothetical re-POST a chance to happen
        assertEquals(1, fakeAgent.requests.size(), "BFF must never re-POST after a 200");
    }

    @Test
    void upstreamErrorEmitsTerminalError() throws Exception {
        fakeAgent.enqueue(503, "application/json", "{\"detail\":\"Stream relay unavailable\"}");

        RecordingSink sink = new RecordingSink();
        service.chatStream(req("upstream-err"), "Bearer t", sink);

        assertTrue(sink.awaitComplete(15), "sink should complete with error");
        assertEquals(List.of("error"), sink.eventNames());
        assertTrue(sink.events.get(0).data().contains("503"));
    }

    @Test
    void idleTimeoutEmitsTerminalError() throws Exception {
        // A stream that never produces events must not leave the UI loading
        // forever — the idle watchdog emits a terminal error.
        String channel = "agent:stream:u:silent";
        fakeAgent.enqueue(200, "application/json",
            "{\"thread_id\":\"u:silent\",\"channel\":\"" + channel + "\"}");
        service = new AgentClientService(fakeAgent.baseUrl(), 300_000, redisUrl, 1_000);

        RecordingSink sink = new RecordingSink();
        service.chatStream(req("silent"), "Bearer t", sink);

        assertTrue(sink.awaitComplete(20), "idle watchdog should complete the sink");
        assertEquals(List.of("error"), sink.eventNames());
        assertTrue(sink.events.get(0).data().contains("idle"),
            "expected idle-timeout error, got: " + sink.events);
    }

    @Test
    void heartbeatResetsIdleWatchdog() throws Exception {
        String channel = "agent:stream:u:heartbeat";
        fakeAgent.enqueue(200, "application/json",
            "{\"thread_id\":\"u:heartbeat\",\"channel\":\"" + channel + "\"}");
        service = new AgentClientService(fakeAgent.baseUrl(), 300_000, redisUrl, 1_500);

        RecordingSink sink = new RecordingSink();
        service.chatStream(req("heartbeat"), "Bearer t", sink);

        // Keep heartbeating past the idle timeout, then send done.
        Thread.sleep(600);
        xadd(channel, "{\"event\":\"heartbeat\"}");
        Thread.sleep(600);
        xadd(channel, "{\"event\":\"heartbeat\"}");
        Thread.sleep(600);
        xadd(channel, "{\"event\":\"done\",\"response\":\"ok\",\"thread_id\":\"u:heartbeat\"}");

        assertTrue(sink.awaitComplete(15), "sink should complete on done");
        List<String> names = sink.eventNames();
        assertEquals("done", names.get(names.size() - 1), "last event must be done, got: " + names);
        assertTrue(names.contains("heartbeat"), "heartbeats are forwarded");
    }

    @Test
    void fallsBackToInlineSseWhenRedisUnset() throws Exception {
        service = new AgentClientService(fakeAgent.baseUrl(), 300_000, "");
        fakeAgent.enqueue(200, "text/event-stream",
            "event: progress\n"
            + "data: {\"event\":\"progress\",\"node\":\"generate\"}\n\n"
            + "event: done\n"
            + "data: {\"event\":\"done\",\"response\":\"ok\"}\n\n");

        RecordingSink sink = new RecordingSink();
        service.chatStream(req("inline"), "Bearer t", sink);

        assertTrue(sink.awaitComplete(15), "inline relay should complete at end of stream");
        assertEquals(List.of("progress", "done"), sink.eventNames());
        // Inline path must request SSE, not the JSON channel contract.
        assertEquals("text/event-stream", fakeAgent.requests.get(0).accept());
    }
}
