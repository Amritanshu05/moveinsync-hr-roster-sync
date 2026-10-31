-- ============================================================================
-- V2__init_offices.sql: Offices Master Table and Known Office Codes
-- ============================================================================

CREATE TABLE offices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    office_code VARCHAR(32) NOT NULL,
    office_name VARCHAR(128) NOT NULL,
    city VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_offices_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uk_offices_tenant_code UNIQUE (tenant_id, office_code)
);

CREATE INDEX idx_offices_tenant_code ON offices(tenant_id, office_code);

-- Seed Known Offices for Demo Tenants
INSERT INTO offices (id, tenant_id, office_code, office_name, city, active)
VALUES 
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'BLR_ECOSPACE', 'Ecospace Business Park', 'Bengaluru', TRUE),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'HYD_HITEC', 'Hitec City Knowledge Park', 'Hyderabad', TRUE),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'DEL_CYBERHUB', 'Cyber City DLF Phase 2', 'Gurugram', TRUE),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'MUM_BKC', 'Bandra Kurla Complex Block G', 'Mumbai', TRUE),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'PUN_MAGARPATTA', 'Magarpatta Cybercity Tower 4', 'Pune', TRUE),
    
    (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'BLR_ECOSPACE', 'Ecospace Business Park Beta', 'Bengaluru', TRUE),
    (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'HYD_HITEC', 'Hitec City Beta Center', 'Hyderabad', TRUE)
ON CONFLICT (tenant_id, office_code) DO NOTHING;
