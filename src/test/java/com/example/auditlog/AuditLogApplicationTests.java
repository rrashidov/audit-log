package com.example.auditlog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AuditLogApplicationTests {

    @Test
    void contextLoads() {
        // Passes if the Spring context comes up. The ApplicationRunner in
        // AuditLogApplication runs warmup + catch-up on H2 with target=10.
    }
}
