package com.example.auditlog.controller;

import com.example.auditlog.service.ConsolidationService;
import com.example.auditlog.service.LoadService;
import com.example.auditlog.service.SettingsService;
import com.example.auditlog.service.WarmupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(SettingsController.class)
class SettingsControllerTest {

    @Autowired MockMvc mvc;
    @MockBean SettingsService settings;
    // AuditLogApplication implements ApplicationRunner and requires these
    // services via @RequiredArgsConstructor; @WebMvcTest excludes @Service
    // beans and non-web @Configuration from the slice, so they must be mocked.
    @MockBean WarmupService warmupService;
    @MockBean ConsolidationService consolidationService;
    @MockBean LoadService loadService;
    @MockBean AtomicBoolean warmupComplete;

    @Test
    void get_rendersBothValues() throws Exception {
        when(settings.getLoadDelayMs()).thenReturn(120L);
        when(settings.getWarmupTarget()).thenReturn(2500L);

        mvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/view"))
                .andExpect(model().attribute("loadDelayMs", 120L))
                .andExpect(model().attribute("warmupTarget", 2500L));
    }

    @Test
    void post_valid_savesAndFlashesSaved() throws Exception {
        mvc.perform(post("/settings")
                        .param("loadDelayMs", "50")
                        .param("warmupTarget", "1000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("saved", equalTo(true)));

        verify(settings).setLoadDelayMs(50L);
        verify(settings).setWarmupTarget(1000L);
    }

    @Test
    void post_negative_flashesError() throws Exception {
        doThrow(new IllegalArgumentException("load.delay.ms must be >= 0"))
                .when(settings).setLoadDelayMs(eq(-1L));

        mvc.perform(post("/settings")
                        .param("loadDelayMs", "-1")
                        .param("warmupTarget", "1000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("error",
                        equalTo("load.delay.ms must be >= 0")));
    }
}
