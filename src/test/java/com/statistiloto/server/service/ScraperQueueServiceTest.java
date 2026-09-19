package com.statistiloto.server.service;

import com.statistiloto.server.dto.response.ScraperStatusResponse;
import com.statistiloto.server.dto.response.ScraperTriggerResponse;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScraperQueueServiceTest {

    private static GenericContainer<?> redisContainer;
    private static RedisClient testRedis;
    private static String redisUrl;

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

    @BeforeAll
    static void initRedis() {
        redisUrl = resolveRedisUrl();
        if (redisUrl != null) {
            testRedis = RedisClient.create(redisUrl);
        }
    }

    @AfterAll
    static void teardown() {
        if (testRedis != null) {
            testRedis.shutdown();
        }
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    @Test
    void whenRedisUnavailable_getLatestStatusReturnsIdle() {
        ScraperQueueService service = new ScraperQueueService("");
        ScraperStatusResponse status = service.getLatestStatus();
        assertNotNull(status);
        assertEquals("idle", status.status());
    }

    @Test
    void whenRedisUnavailable_enqueueThrowsException() {
        ScraperQueueService service = new ScraperQueueService("");
        assertThrows(IllegalStateException.class, () -> service.enqueue("req-1", "user-sub"));
    }

    @Test
    void enqueueAndStatus_WithRealRedis() {
        Assumptions.assumeTrue(testRedis != null, "Redis not available");

        ScraperQueueService service = new ScraperQueueService(testRedis);
        ScraperTriggerResponse triggerResp = service.enqueue("test-req-1", "sub-123");
        assertEquals("test-req-1", triggerResp.requestId());
        assertEquals("queued", triggerResp.status());

        ScraperStatusResponse status = service.getLatestStatus();
        assertNotNull(status);
        assertEquals("test-req-1", status.requestId());
        assertEquals("queued", status.status());
    }

    @Test
    void streamEvents_SynthesizesTerminalFromStatusHash() throws Exception {
        Assumptions.assumeTrue(testRedis != null, "Redis not available");

        ScraperQueueService service = new ScraperQueueService(testRedis);
        String reqId = "test-stream-term";
        service.enqueue(reqId, "sub-admin");

        // Set status hash directly to done to test block-slice terminal synthesis
        try (var conn = testRedis.connect()) {
            conn.sync().hset("scraper:status:" + reqId, "status", "done");
            conn.sync().hset("scraper:status:" + reqId, "inserted", "3");
            conn.sync().hset("scraper:status:" + reqId, "prizes_written", "7");
        }

        CountDownLatch completed = new CountDownLatch(1);
        List<String> receivedEvents = new ArrayList<>();

        service.streamEvents(reqId, new ScraperQueueService.ScraperStreamSink() {
            @Override
            public void send(String event, String data) {
                receivedEvents.add(event);
            }

            @Override
            public void complete() {
                completed.countDown();
            }

            @Override
            public void completeWithError(Throwable t) {
                completed.countDown();
            }

            @Override
            public void onClose(Runnable cleanup) {}
        });

        assertTrue(completed.await(10, TimeUnit.SECONDS), "Stream should complete within 10s");
        assertTrue(receivedEvents.contains("done"), "Should have received synthesized 'done' event");
    }
}
