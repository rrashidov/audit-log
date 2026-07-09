package com.example.auditlog.repository;

import com.example.auditlog.entity.AuditLog;
import com.example.auditlog.enums.EntryType;
import com.example.auditlog.enums.OperationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, String>, JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findByEntryTypeAndDateTimeBefore(EntryType entryType, LocalDateTime cutoff);

    Page<AuditLog> findByEntryType(EntryType entryType, Pageable pageable);

    Page<AuditLog> findByOperationType(OperationType operationType, Pageable pageable);

    Page<AuditLog> findByEntryTypeAndOperationType(EntryType entryType, OperationType operationType, Pageable pageable);

    @Query("SELECT a.entryType, COUNT(a) FROM AuditLog a GROUP BY a.entryType")
    List<Object[]> countGroupedByEntryType();
}
