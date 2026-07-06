package com.example.auditlog.service;

import com.example.auditlog.entity.AppSetting;
import com.example.auditlog.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsService {

    public static final String KEY_LOAD_DELAY_MS = "load.delay.ms";
    public static final String KEY_WARMUP_TARGET = "warmup.target";

    private final AppSettingRepository repo;

    @Value("${app.warmup.target:1000000}")
    private long warmupTargetDefault;

    @Value("${app.load.delay.ms:0}")
    private long loadDelayMsDefault;

    // volatile Long caches: one writer (UI in Task 4), many readers (load loop).
    // Null sentinel means "not yet read"; any actual value is boxed.
    private volatile Long cachedLoadDelayMs;
    private volatile Long cachedWarmupTarget;

    @Transactional
    public long getLoadDelayMs() {
        Long v = cachedLoadDelayMs;
        if (v == null) {
            v = getOrSeed(KEY_LOAD_DELAY_MS, loadDelayMsDefault);
            cachedLoadDelayMs = v;
        }
        return v;
    }

    @Transactional
    public long getWarmupTarget() {
        Long v = cachedWarmupTarget;
        if (v == null) {
            v = getOrSeed(KEY_WARMUP_TARGET, warmupTargetDefault);
            cachedWarmupTarget = v;
        }
        return v;
    }

    /**
     * Returns the stored value for {@code key} parsed as a long, or writes
     * and returns {@code defaultValue} if the row is absent. If a row exists
     * but its value cannot be parsed as a long, logs a warning and returns
     * {@code defaultValue} WITHOUT overwriting the row — an operator can
     * inspect and fix the row manually.
     *
     * Called only via the public getters ({@link #getLoadDelayMs()} and
     * {@link #getWarmupTarget()}), which wrap this method in a transaction.
     * Package-private for direct testing.
     */
    long getOrSeed(String key, long defaultValue) {
        return repo.findById(key)
                .map(s -> parseOrDefault(key, s.getValue(), defaultValue))
                .orElseGet(() -> {
                    repo.save(new AppSetting(key, Long.toString(defaultValue)));
                    return defaultValue;
                });
    }

    @Transactional
    public void setLoadDelayMs(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("load.delay.ms must be >= 0");
        }
        repo.save(new AppSetting(KEY_LOAD_DELAY_MS, Long.toString(ms)));
        cachedLoadDelayMs = ms;
    }

    @Transactional
    public void setWarmupTarget(long target) {
        if (target < 0) {
            throw new IllegalArgumentException("warmup.target must be >= 0");
        }
        repo.save(new AppSetting(KEY_WARMUP_TARGET, Long.toString(target)));
        cachedWarmupTarget = target;
    }

    private long parseOrDefault(String key, String raw, long defaultValue) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("Setting '{}' has non-numeric value '{}'; falling back to default {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Clears both volatile caches so the next read hits the DB. Not called
     * from production code; exists solely for tests that need deterministic
     * cache-miss behaviour within a shared Spring context.
     */
    void resetCacheForTests() {
        cachedLoadDelayMs = null;
        cachedWarmupTarget = null;
    }
}
