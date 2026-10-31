package com.moveinsync.roster.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "offices", uniqueConstraints = {
    @UniqueConstraint(name = "uk_offices_tenant_code", columnNames = {"tenant_id", "office_code"})
}, indexes = {
    @Index(name = "idx_offices_tenant_code", columnList = "tenant_id, office_code")
})
public class Office extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "office_code", nullable = false, length = 32)
    private String officeCode;

    @Column(name = "office_name", nullable = false, length = 128)
    private String officeName;

    @Column(name = "city", nullable = false, length = 64)
    private String city;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
