package com.example.auditlog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_setting")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppSetting {

    // Column is named "key_name" because KEY is a reserved word in MySQL.
    // The Java field stays "key" for readability at call sites.
    @Id
    @Column(name = "key_name", length = 64)
    private String key;

    @Column(name = "value", nullable = false, length = 256)
    private String value;
}
