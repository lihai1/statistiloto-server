package com.statistiloto.server.controller;

import com.statistiloto.server.dto.response.ScraperStatusResponse;
import com.statistiloto.server.dto.response.ScraperTriggerResponse;
import com.statistiloto.server.security.SecurityConfig;
import com.statistiloto.server.service.ScraperQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminScraperController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/auth/realms/statistiloto/protocol/openid-connect/certs",
    "spring.security.oauth2.resourceserver.jwt.audiences=statistiloto-ui"
})
class AdminScraperControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ScraperQueueService scraperQueueService;

    @Test
    void trigger_WhenUnauthenticated_Returns401() throws Exception {
        mockMvc.perform(post("/api/admin/scraper/trigger"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void trigger_WhenUserRole_Returns403() throws Exception {
        mockMvc.perform(post("/api/admin/scraper/trigger")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void trigger_WhenAdminRole_Returns200AndQueued() throws Exception {
        when(scraperQueueService.getLatestStatus()).thenReturn(new ScraperStatusResponse(null, "idle", null, 0, 0, null, null));
        when(scraperQueueService.enqueue(any(), any())).thenReturn(new ScraperTriggerResponse("req-abc", "queued"));

        mockMvc.perform(post("/api/admin/scraper/trigger")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")).jwt(jwt -> jwt.subject("admin-sub"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("queued"))
            .andExpect(jsonPath("$.request_id").value("req-abc"));
    }

    @Test
    void trigger_WhenAlreadyRunning_ReturnsExistingIdWithoutEnqueue() throws Exception {
        when(scraperQueueService.getLatestStatus()).thenReturn(new ScraperStatusResponse("active-1", "running", "fetch", 0, 0, null, 123456L));

        mockMvc.perform(post("/api/admin/scraper/trigger")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("running"))
            .andExpect(jsonPath("$.request_id").value("active-1"));
    }

    @Test
    void getStatus_WhenAdminRole_ReturnsStatus() throws Exception {
        when(scraperQueueService.getLatestStatus()).thenReturn(new ScraperStatusResponse("req-123", "done", "completed", 5, 12, null, 1000L));

        mockMvc.perform(get("/api/admin/scraper/status")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("done"))
            .andExpect(jsonPath("$.inserted").value(5))
            .andExpect(jsonPath("$.prizes_written").value(12));
    }

    @Test
    void streamEvents_WhenAdminRole_ReturnsSse() throws Exception {
        when(scraperQueueService.streamEvents(eq("req-123"))).thenReturn(new SseEmitter());

        mockMvc.perform(get("/api/admin/scraper/stream/req-123")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk());
    }
}
