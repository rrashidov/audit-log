package com.example.auditlog.repository;

import com.example.auditlog.entity.AppSetting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// Uses @SpringBootTest (not @DataJpaTest) so we pick up the datasource wired
// via src/test/resources/application.properties — including NON_KEYWORDS=VALUE,
// without which H2 rejects the "value" column as a reserved keyword. This
// matches the pattern SettingsServiceTest will use in Task 3.
@SpringBootTest
class AppSettingRepositoryTest {

    @Autowired
    AppSettingRepository repo;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
    }

    @AfterEach
    void tearDown() {
        repo.deleteAll();
    }

    @Test
    void saveAndFindByKey() {
        repo.save(new AppSetting("load.delay.ms", "250"));

        Optional<AppSetting> found = repo.findById("load.delay.ms");

        assertThat(found).isPresent();
        assertThat(found.get().getValue()).isEqualTo("250");
    }

    @Test
    void saveWithExistingKeyUpserts() {
        repo.save(new AppSetting("warmup.target", "1000"));
        repo.save(new AppSetting("warmup.target", "2000"));

        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findById("warmup.target")).map(AppSetting::getValue).contains("2000");
    }
}
