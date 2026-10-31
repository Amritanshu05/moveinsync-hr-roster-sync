-- ============================================================================
-- V1__initial_schema.sql: Initial Relational Database Schema
-- MoveInSync HR Roster Sync - Idempotent, Out-of-Order, Self-Healing System
-- ============================================================================

-- Enable pgcrypto extension for UUID generation if needed
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. Tenants Table
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tenants_tenant_code UNIQUE (tenant_code)
);

-- 2. Users Table (Authentication & RBAC)
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL, -- ROLE_EMPLOYEE, ROLE_TRANSPORT_ADMIN, ROLE_OPERATOR
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uk_users_tenant_username UNIQUE (tenant_id, username)
);
CREATE INDEX idx_users_tenant_username ON users(tenant_id, username);

-- 3. Employees Table (Roster Core)
CREATE TABLE employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    employee_code VARCHAR(64) NOT NULL,
    full_name VARCHAR(128) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone_number VARCHAR(32) NOT NULL,
    manager_employee_code VARCHAR(64),
    office_code VARCHAR(32) NOT NULL,
    shift_code VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, INACTIVE, SUSPENDED
    source_version BIGINT NOT NULL DEFAULT 1,
    source_timestamp TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    record_hash BIGINT NOT NULL DEFAULT 0,
    -- Transport Admin owned fields (segregated ownership)
    pickup_point_id VARCHAR(64),
    special_assistance BOOLEAN NOT NULL DEFAULT FALSE,
    transport_notes VARCHAR(255),
    -- JPA Optimistic locking version
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_employees_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uk_employees_tenant_emp_code UNIQUE (tenant_id, employee_code)
);
CREATE INDEX idx_employees_tenant_emp_code ON employees(tenant_id, employee_code);
CREATE INDEX idx_employees_tenant_status ON employees(tenant_id, status);
CREATE INDEX idx_employees_tenant_office ON employees(tenant_id, office_code);
CREATE INDEX idx_employees_tenant_hash ON employees(tenant_id, record_hash);

-- 4. Employee Events Table (Ingested Event History)
CREATE TABLE employee_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    employee_code VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL, -- CREATE, UPDATE, DEACTIVATE
    source_version BIGINT NOT NULL,
    source_timestamp TIMESTAMPTZ NOT NULL,
    raw_payload TEXT NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    processing_outcome VARCHAR(32), -- APPLIED, STALE_IGNORED, DUPLICATE_NO_OP, VALIDATION_FAILED
    error_message TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_emp_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);
CREATE INDEX idx_emp_events_lookup ON employee_events(tenant_id, employee_code, source_version);
CREATE INDEX idx_emp_events_event_id ON employee_events(tenant_id, event_id);

-- 5. Idempotency Records Table
CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    target_entity_id VARCHAR(64) NOT NULL,
    source_version BIGINT NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status VARCHAR(32) NOT NULL, -- APPLIED, IGNORED_STALE, DUPLICATE_NO_OP
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_idempotency_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uk_idempotency_tenant_key UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX idx_idempotency_tenant_key ON idempotency_records(tenant_id, idempotency_key);

-- 6. Outbox Events Table (Transactional Outbox Pattern)
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL, -- EMPLOYEE, BOOKING
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL, -- JSON event payload
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, PUBLISHED, FAILED, DEAD_LETTER
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_outbox_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);
CREATE INDEX idx_outbox_status_poller ON outbox_events(status, next_retry_at, created_at);

-- 7. Cab Bookings Table
CREATE TABLE cab_bookings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    employee_code VARCHAR(64) NOT NULL,
    cab_id VARCHAR(64) NOT NULL,
    booking_date DATE NOT NULL,
    shift_time VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED', -- CONFIRMED, CANCELLED, COMPLETED
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bookings_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);
CREATE INDEX idx_bookings_tenant_emp ON cab_bookings(tenant_id, employee_code, booking_date, status);
CREATE INDEX idx_bookings_date_status ON cab_bookings(tenant_id, booking_date, status);

-- 8. Cab Seats Table
CREATE TABLE cab_seats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    booking_id UUID NOT NULL,
    cab_id VARCHAR(64) NOT NULL,
    seat_number VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RESERVED', -- RESERVED, FREED
    freed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_seats_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT fk_seats_booking FOREIGN KEY (booking_id) REFERENCES cab_bookings(id) ON DELETE CASCADE
);
CREATE INDEX idx_seats_tenant_booking ON cab_seats(tenant_id, booking_id);

-- 9. Reconciliation Jobs Table (Nightly Diff & Safety Brake)
CREATE TABLE reconciliation_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_reference VARCHAR(64) NOT NULL,
    tenant_id UUID NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS', -- IN_PROGRESS, COMPLETED, SAFETY_BRAKE_TRIGGERED, AWAITING_APPROVAL, APPROVED, REJECTED, FAILED
    total_records INT NOT NULL DEFAULT 0,
    unchanged_records INT NOT NULL DEFAULT 0,
    updated_records INT NOT NULL DEFAULT 0,
    created_records INT NOT NULL DEFAULT 0,
    active_db_count INT NOT NULL DEFAULT 0,
    file_employee_count INT NOT NULL DEFAULT 0,
    missing_count INT NOT NULL DEFAULT 0,
    missing_ratio NUMERIC(5, 4) NOT NULL DEFAULT 0.0000,
    safety_brake_threshold NUMERIC(5, 4) NOT NULL DEFAULT 0.3000,
    safety_brake_triggered BOOLEAN NOT NULL DEFAULT FALSE,
    approved_by VARCHAR(64),
    operator_notes TEXT,
    approved_at TIMESTAMPTZ,
    execution_duration_ms BIGINT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_rec_jobs_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uk_rec_jobs_reference UNIQUE (tenant_id, job_reference)
);
CREATE INDEX idx_rec_jobs_tenant_status ON reconciliation_jobs(tenant_id, status);

-- 10. Reconciliation Errors / Flagged Records Table
CREATE TABLE reconciliation_errors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    employee_code VARCHAR(64) NOT NULL,
    row_number INT,
    error_type VARCHAR(32) NOT NULL, -- VALIDATION_ERROR, MISSING_RECORD_FLAGGED
    field VARCHAR(64),
    error_code VARCHAR(64) NOT NULL,
    message VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rec_errors_job FOREIGN KEY (job_id) REFERENCES reconciliation_jobs(id) ON DELETE CASCADE,
    CONSTRAINT fk_rec_errors_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);
CREATE INDEX idx_rec_errors_job ON reconciliation_errors(job_id);

-- ============================================================================
-- Seed Initial Demo Tenants & Users (BCrypt password: "password123")
-- ============================================================================
INSERT INTO tenants (id, tenant_code, name, active)
VALUES 
    ('11111111-1111-1111-1111-111111111111', 'TENANT_ALPHA', 'Alpha Corp Technologies', TRUE),
    ('22222222-2222-2222-2222-222222222222', 'TENANT_BETA', 'Beta Global Solutions', TRUE)
ON CONFLICT (tenant_code) DO NOTHING;

-- BCrypt hash for "password123": $2a$10$w6E1jT7N9s2VzB0pS6aJ8e6rG9lA0tF1hY3jU5kM7oP9qR2tV4xZ6
INSERT INTO users (id, tenant_id, username, password_hash, full_name, role, enabled)
VALUES 
    ('33333333-3333-3333-3333-333333333331', '11111111-1111-1111-1111-111111111111', 'operator_alpha', '$2a$10$w6E1jT7N9s2VzB0pS6aJ8e6rG9lA0tF1hY3jU5kM7oP9qR2tV4xZ6', 'Alpha Operator', 'ROLE_OPERATOR', TRUE),
    ('33333333-3333-3333-3333-333333333332', '11111111-1111-1111-1111-111111111111', 'admin_transport_alpha', '$2a$10$w6E1jT7N9s2VzB0pS6aJ8e6rG9lA0tF1hY3jU5kM7oP9qR2tV4xZ6', 'Alpha Transport Admin', 'ROLE_TRANSPORT_ADMIN', TRUE),
    ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'emp_john', '$2a$10$w6E1jT7N9s2VzB0pS6aJ8e6rG9lA0tF1hY3jU5kM7oP9qR2tV4xZ6', 'John Doe Employee', 'ROLE_EMPLOYEE', TRUE)
ON CONFLICT DO NOTHING;
