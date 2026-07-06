package com.example.auditlog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "key_value")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyValue {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "value", nullable = false, length = 256)
    private String value;
}
