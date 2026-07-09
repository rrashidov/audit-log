package com.example.auditlog.repository;

import com.example.auditlog.entity.AuditLog;
import com.example.auditlog.enums.EntryType;
import com.example.auditlog.enums.OperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AuditLogRepositoryGridTest {

    @Autowired
    AuditLogRepository repo;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
    }

    @Test
    void countGroupedByEntryType_returnsCorrectCountsPerType() {
        repo.save(auditLog(EntryType.REGULAR));
        repo.save(auditLog(EntryType.REGULAR));
        repo.save(auditLog(EntryType.MINUTE_CONSOLIDATION));
        repo.save(auditLog(EntryType.HOUR_CONSOLIDATION));

        List<Object[]> results = repo.countGroupedByEntryType();

        assertThat(results).hasSize(3);

        Map<EntryType, Long> counts = results.stream()
                .collect(Collectors.toMap(
                        row -> (EntryType) row[0],
                        row -> (Long) row[1]
                ));

        assertThat(counts.get(EntryType.REGULAR)).isEqualTo(2L);
        assertThat(counts.get(EntryType.MINUTE_CONSOLIDATION)).isEqualTo(1L);
        assertThat(counts.get(EntryType.HOUR_CONSOLIDATION)).isEqualTo(1L);
        assertThat(counts).doesNotContainKey(EntryType.DAY_CONSOLIDATION);
    }

    @Test
    void countGroupedByEntryType_emptyTable_returnsEmptyList() {
        List<Object[]> results = repo.countGroupedByEntryType();
        assertThat(results).isEmpty();
    }

    private AuditLog auditLog(EntryType type) {
        return AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .entryType(type)
                .operationType(OperationType.CREATE)
                .dateTime(LocalDateTime.now())
                .count(1)
                .duration(10)
                .build();
    }
}
