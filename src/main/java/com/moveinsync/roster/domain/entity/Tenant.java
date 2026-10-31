package com.moveinsync.roster.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "tenants", uniqueConstraints = {
    @UniqueConstraint(name = "uk_tenants_tenant_code", columnNames = {"tenant_code"})
})
public class Tenant extends BaseEntity {

    @Column(name = "tenant_code", nullable = false, length = 64)
    private String tenantCode;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
