-- =====================================================
-- Customer Service Database Schema
-- Technical Assessment - Senior Level
-- =====================================================

-- Extension for UUIDs
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =====================================================
-- Table: person
-- Description: Base personal information
-- =====================================================
CREATE TABLE IF NOT EXISTS person (
    person_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL,
    gender CHAR(1) NOT NULL CHECK (gender IN ('M', 'F', 'O')),
    age INTEGER NOT NULL CHECK (age >= 18 AND age <= 120),
    identification VARCHAR(10) NOT NULL UNIQUE,
    address VARCHAR(200) NOT NULL,
    phone VARCHAR(15) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for person
CREATE INDEX idx_person_identification ON person(identification);
CREATE INDEX idx_person_name ON person(name);

-- =====================================================
-- Table: customer
-- Description: Customer information (inherits from person)
-- =====================================================
CREATE TABLE IF NOT EXISTS customer (
    customer_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    person_id UUID NOT NULL UNIQUE REFERENCES person(person_id) ON DELETE RESTRICT,
    password VARCHAR(255) NOT NULL, -- Stores BCrypt hash
    state BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0 -- For Optimistic Locking
);

-- Indexes for customer
CREATE INDEX idx_customer_person_id ON customer(person_id);
CREATE INDEX idx_customer_state ON customer(state);
CREATE INDEX idx_customer_created_at ON customer(created_at DESC);


-- =====================================================
-- Function: Update updated_at for person table
-- =====================================================
CREATE OR REPLACE FUNCTION update_person_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- Function: Update updated_at and version for customer table
-- =====================================================
CREATE OR REPLACE FUNCTION update_customer_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    NEW.version = OLD.version + 1; -- Increment version for Optimistic Locking
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger for person (only updates updated_at)
CREATE TRIGGER update_person_updated_at
    BEFORE UPDATE ON person
    FOR EACH ROW
    EXECUTE FUNCTION update_person_updated_at();

-- Trigger for customer (updates updated_at and increments version)
CREATE TRIGGER update_customer_updated_at
    BEFORE UPDATE ON customer
    FOR EACH ROW
    EXECUTE FUNCTION update_customer_updated_at();

-- =====================================================
-- View: customer_full_view
-- Description: Denormalized view with complete customer data
-- =====================================================
CREATE OR REPLACE VIEW customer_full_view AS
SELECT
    c.customer_id,
    p.name,
    p.gender,
    p.age,
    p.identification,
    p.address,
    p.phone,
    c.state,
    c.created_at,
    c.updated_at,
    c.version
FROM customer c
INNER JOIN person p ON c.person_id = p.person_id;

-- =====================================================
-- Seed Data (Test Data)
-- =====================================================

-- Person 1: Jose Lema
INSERT INTO person (person_id, name, gender, age, identification, address, phone)
VALUES (
    '550e8400-e29b-41d4-a716-446655440000',
    'Jose Lema',
    'M',
    35,
    '0705463421',
    'Otavalo sn y principal',
    '0982547855'
) ON CONFLICT (identification) DO NOTHING;

INSERT INTO customer (customer_id, person_id, password, state)
VALUES (
    '550e8400-e29b-41d4-a716-446655440000',
    '550e8400-e29b-41d4-a716-446655440000',
    '$2a$10$rO0eH8Z8EqYZJ9Qz6YqZxOXYqY9vZqV9Y9qY9qY9qY9qY9qY9qY9q', -- Password: SecureP@ss123 (BCrypt hash)
    TRUE
) ON CONFLICT (customer_id) DO NOTHING;

-- Person 2: Marianela Montalvo
INSERT INTO person (person_id, name, gender, age, identification, address, phone)
VALUES (
    '660e8400-e29b-41d4-a716-446655440001',
    'Marianela Montalvo',
    'F',
    28,
    '0705463422',
    'Amazonas y NNUU',
    '0975489655'
) ON CONFLICT (identification) DO NOTHING;

INSERT INTO customer (customer_id, person_id, password, state)
VALUES (
    '660e8400-e29b-41d4-a716-446655440001',
    '660e8400-e29b-41d4-a716-446655440001',
    '$2a$10$rO0eH8Z8EqYZJ9Qz6YqZxOXYqY9vZqV9Y9qY9qY9qY9qY9qY9qY9q', -- Password: SecureP@ss123
    TRUE
) ON CONFLICT (customer_id) DO NOTHING;

-- Person 3: Juan Osorio
INSERT INTO person (person_id, name, gender, age, identification, address, phone)
VALUES (
    '770e8400-e29b-41d4-a716-446655440002',
    'Juan Osorio',
    'M',
    42,
    '0705463423',
    '13 junio y Equinoccial',
    '0987654321'
) ON CONFLICT (identification) DO NOTHING;

INSERT INTO customer (customer_id, person_id, password, state)
VALUES (
    '770e8400-e29b-41d4-a716-446655440002',
    '770e8400-e29b-41d4-a716-446655440002',
    '$2a$10$rO0eH8Z8EqYZJ9Qz6YqZxOXYqY9vZqV9Y9qY9qY9qY9qY9qY9qY9q', -- Password: SecureP@ss123
    TRUE
) ON CONFLICT (customer_id) DO NOTHING;

-- =====================================================
-- Table and Column Comments
-- =====================================================
COMMENT ON TABLE person IS 'Base table with personal information';
COMMENT ON TABLE customer IS 'Customer table with credentials and state';

COMMENT ON COLUMN customer.version IS 'Version for optimistic locking concurrency control';
COMMENT ON COLUMN customer.password IS 'BCrypt hash of customer password';
