package com.example.auditlog.repository;

import com.example.auditlog.entity.KeyValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KeyValueRepository extends JpaRepository<KeyValue, String> {

    Optional<KeyValue> findFirstByOrderById();
}
