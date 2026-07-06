package com.example.auditlog;

import com.example.auditlog.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AuditLogApplicationTests {

    @Autowired SettingsService settingsService;

    @Test
    void contextLoads() {
        // With profile "test", StartupRunner is disabled — this asserts
        // the app context brings up the beans without the runner side-effects.
        assertThat(settingsService.getLoadDelayMs()).isEqualTo(0L);
    }
}
