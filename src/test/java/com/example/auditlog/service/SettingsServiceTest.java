package com.example.auditlog.service;

import com.example.auditlog.entity.AppSetting;
import com.example.auditlog.repository.AppSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "app.warmup.target=123",
        "app.load.delay.ms=45"
})
class SettingsServiceTest {

    @Autowired SettingsService service;
    @Autowired AppSettingRepository repo;

    @Test
    void getLoadDelayMs_seedsFromPropertyWhenRowMissing() {
        repo.deleteAll();
        service.resetCacheForTests();

        long value = service.getLoadDelayMs();

        assertThat(value).isEqualTo(45L);
        assertThat(repo.findById(SettingsService.KEY_LOAD_DELAY_MS))
                .isPresent()
                .map(AppSetting::getValue)
                .contains("45");
    }

    @Test
    void getWarmupTarget_returnsStoredValueWhenRowPresent() {
        repo.deleteAll();
        service.resetCacheForTests();
        repo.save(new AppSetting(SettingsService.KEY_WARMUP_TARGET, "999"));

        long value = service.getWarmupTarget();

        assertThat(value).isEqualTo(999L);
    }

    @Test
    void getLoadDelayMs_returnsSeededDefaultWhenStoredValueIsCorrupt() {
        repo.deleteAll();
        service.resetCacheForTests();
        repo.save(new AppSetting(SettingsService.KEY_LOAD_DELAY_MS, "not-a-number"));

        long value = service.getLoadDelayMs();

        // Falls back to the property default; does not throw, does not
        // overwrite the corrupt row.
        assertThat(value).isEqualTo(45L);
        assertThat(repo.findById(SettingsService.KEY_LOAD_DELAY_MS))
                .map(AppSetting::getValue)
                .contains("not-a-number");
    }

    @org.junit.jupiter.api.Test
    void setLoadDelayMs_persistsAndUpdatesCache() {
        repo.deleteAll();
        service.resetCacheForTests();

        service.setLoadDelayMs(750L);

        assertThat(repo.findById(SettingsService.KEY_LOAD_DELAY_MS))
                .map(AppSetting::getValue)
                .contains("750");
        // Next read must return the new value without needing a cache miss.
        assertThat(service.getLoadDelayMs()).isEqualTo(750L);
    }

    @org.junit.jupiter.api.Test
    void setWarmupTarget_persistsAndUpdatesCache() {
        repo.deleteAll();
        service.resetCacheForTests();

        service.setWarmupTarget(5000L);

        assertThat(repo.findById(SettingsService.KEY_WARMUP_TARGET))
                .map(AppSetting::getValue)
                .contains("5000");
        assertThat(service.getWarmupTarget()).isEqualTo(5000L);
    }

    @org.junit.jupiter.api.Test
    void setLoadDelayMs_rejectsNegative() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.setLoadDelayMs(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
    }

    @org.junit.jupiter.api.Test
    void setWarmupTarget_rejectsNegative() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.setWarmupTarget(-42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
    }
}
