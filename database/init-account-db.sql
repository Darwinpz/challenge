-- =====================================================
-- Account Service Database Schema
-- Technical Assessment - Senior Level
-- =====================================================

-- Extension for UUIDs
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =====================================================
-- Sequence: account_number_seq
-- Description: Auto-increment sequence for account numbers
-- =====================================================
CREATE SEQUENCE IF NOT EXISTS account_number_seq
    START WITH 1000000001
    INCREMENT BY 1
    NO MAXVALUE
    CACHE 1;

-- =====================================================
-- Table: account
-- Description: Bank accounts for customers
-- =====================================================
CREATE TABLE IF NOT EXISTS account (
    account_number BIGINT PRIMARY KEY DEFAULT nextval('account_number_seq'),
    account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('AHORRO', 'CORRIENTE')),
    balance DECIMAL(15, 2) NOT NULL DEFAULT 0.00 CHECK (balance >= 0),
    state BOOLEAN NOT NULL DEFAULT TRUE,
    customer_id UUID NOT NULL, -- Reference to customer_id from customer-service
    customer_name VARCHAR(100) NOT NULL, -- Denormalized for performance
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0 -- For Optimistic Locking
);

-- Link the sequence to the column (for SERIAL-like behavior)
ALTER SEQUENCE account_number_seq OWNED BY account.account_number;

-- Indexes for account
CREATE INDEX idx_account_customer_id ON account(customer_id);
CREATE INDEX idx_account_type ON account(account_type);
CREATE INDEX idx_account_state ON account(state);
CREATE INDEX idx_account_created_at ON account(created_at DESC);

-- =====================================================
-- Table: movement
-- Description: Transactional movements for accounts
-- =====================================================
CREATE TABLE IF NOT EXISTS movement (
    movement_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    account_number BIGINT NOT NULL REFERENCES account(account_number) ON DELETE RESTRICT,
    movement_type VARCHAR(20) NOT NULL CHECK (movement_type IN ('DEBITO', 'CREDITO', 'REVERSA')),
    amount DECIMAL(15, 2) NOT NULL CHECK (amount > 0),
    balance_before DECIMAL(15, 2) NOT NULL,
    balance_after DECIMAL(15, 2) NOT NULL,
    description VARCHAR(200),
    reference VARCHAR(100),
    transaction_id VARCHAR(50) NOT NULL UNIQUE, -- Unique ID for traceability
    reversed_movement_id UUID REFERENCES movement(movement_id), -- Reference to original movement if reversal
    reversed BOOLEAN NOT NULL DEFAULT FALSE, -- Indicates if movement has been reversed
    idempotency_key UUID, -- To prevent duplicates
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    correlation_id UUID,
    request_id UUID
);

-- Indexes for movement
CREATE INDEX idx_movement_account_number ON movement(account_number);
CREATE INDEX idx_movement_type ON movement(movement_type);
CREATE INDEX idx_movement_created_at ON movement(created_at DESC);
CREATE INDEX idx_movement_transaction_id ON movement(transaction_id);
CREATE INDEX idx_movement_idempotency_key ON movement(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_movement_reversed ON movement(reversed);
CREATE INDEX idx_movement_correlation_id ON movement(correlation_id);

-- =====================================================
-- Function: Update updated_at automatically
-- =====================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    NEW.version = OLD.version + 1; -- Increment version for Optimistic Locking
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger for account
CREATE TRIGGER update_account_updated_at
    BEFORE UPDATE ON account
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- =====================================================
-- Function: Generate random account number
-- =====================================================
CREATE OR REPLACE FUNCTION generate_account_number()
RETURNS BIGINT AS $$
DECLARE
    new_account_number BIGINT;
    max_attempts INTEGER := 100;
    attempt INTEGER := 0;
BEGIN
    LOOP
        -- Generate random number between 100000 and 999999999
        new_account_number := floor(random() * (999999999 - 100000 + 1) + 100000)::BIGINT;

        -- Check if number already exists
        IF NOT EXISTS (SELECT 1 FROM account WHERE account_number = new_account_number) THEN
            RETURN new_account_number;
        END IF;

        attempt := attempt + 1;
        IF attempt >= max_attempts THEN
            RAISE EXCEPTION 'Could not generate unique account number after % attempts', max_attempts;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- Function: Validate sufficient balance for debit
-- =====================================================
CREATE OR REPLACE FUNCTION validate_sufficient_balance()
RETURNS TRIGGER AS $$
DECLARE
    current_balance DECIMAL(15, 2);
BEGIN
    -- Only validate for debits
    IF NEW.movement_type = 'DEBITO' THEN
        -- Get current account balance
        SELECT balance INTO current_balance
        FROM account
        WHERE account_number = NEW.account_number;

        -- Validate sufficient balance
        IF current_balance < NEW.amount THEN
            RAISE EXCEPTION 'Saldo no disponible. Current balance: %, Requested amount: %', current_balance, NEW.amount
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to validate balance before inserting movement
CREATE TRIGGER validate_balance_before_movement
    BEFORE INSERT ON movement
    FOR EACH ROW
    EXECUTE FUNCTION validate_sufficient_balance();

-- =====================================================
-- Function: Update account balance after movement
-- =====================================================
CREATE OR REPLACE FUNCTION update_account_balance()
RETURNS TRIGGER AS $$
DECLARE
    balance_change DECIMAL(15, 2);
    new_balance DECIMAL(15, 2);
BEGIN
    -- Calculate balance change
    CASE NEW.movement_type
        WHEN 'CREDITO' THEN
            balance_change := NEW.amount;
        WHEN 'DEBITO' THEN
            balance_change := -NEW.amount;
        WHEN 'REVERSA' THEN
            -- Reversal inverts original movement type
            SELECT CASE WHEN movement_type = 'DEBITO' THEN amount ELSE -amount END
            INTO balance_change
            FROM movement
            WHERE movement_id = NEW.reversed_movement_id;

            -- Mark the original movement as reversed
            UPDATE movement
            SET reversed = TRUE
            WHERE movement_id = NEW.reversed_movement_id;
        ELSE
            RAISE EXCEPTION 'Invalid movement type: %', NEW.movement_type;
    END CASE;

    -- Update account balance and get the new balance
    UPDATE account
    SET balance = balance + balance_change
    WHERE account_number = NEW.account_number
    RETURNING balance INTO new_balance;

    -- Update the balance_after field in the new movement
    UPDATE movement
    SET balance_after = new_balance
    WHERE movement_id = NEW.movement_id;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to update balance after inserting movement
CREATE TRIGGER update_balance_after_movement
    AFTER INSERT ON movement
    FOR EACH ROW
    EXECUTE FUNCTION update_account_balance();

-- =====================================================
-- View: account_summary
-- Description: Account summary with statistics
-- =====================================================
CREATE OR REPLACE VIEW account_summary AS
SELECT
    a.account_number,
    a.account_type,
    a.balance,
    a.state,
    a.customer_id,
    a.customer_name,
    COUNT(m.movement_id) AS total_movements,
    COALESCE(SUM(CASE WHEN m.movement_type = 'CREDITO' THEN m.amount ELSE 0 END), 0) AS total_credits,
    COALESCE(SUM(CASE WHEN m.movement_type = 'DEBITO' THEN m.amount ELSE 0 END), 0) AS total_debits,
    a.created_at,
    a.updated_at
FROM account a
LEFT JOIN movement m ON a.account_number = m.account_number
GROUP BY a.account_number, a.account_type, a.balance, a.state, a.customer_id, a.customer_name, a.created_at, a.updated_at;

-- =====================================================
-- Seed Data (Test Data)
-- =====================================================

-- Account 1: Jose Lema - AHORRO
-- Initial balance will be set by first movement
INSERT INTO account (account_number, account_type, balance, state, customer_id, customer_name)
VALUES (
    478758,
    'AHORRO',
    1400.00,  -- Balance BEFORE the credit movement
    TRUE,
    '550e8400-e29b-41d4-a716-446655440000',
    'Jose Lema'
) ON CONFLICT (account_number) DO NOTHING;

-- Account 2: Marianela Montalvo - CORRIENTE
-- Initial balance will be set by first movement
INSERT INTO account (account_number, account_type, balance, state, customer_id, customer_name)
VALUES (
    225487,
    'CORRIENTE',
    0.00,  -- Starting with zero, will be credited to 600
    TRUE,
    '660e8400-e29b-41d4-a716-446655440001',
    'Marianela Montalvo'
) ON CONFLICT (account_number) DO NOTHING;

-- Account 3: Marianela Montalvo - AHORRO
-- Initial balance will be set by first movement
INSERT INTO account (account_number, account_type, balance, state, customer_id, customer_name)
VALUES (
    496825,
    'AHORRO',
    540.00,  -- Balance BEFORE the debit movement
    TRUE,
    '660e8400-e29b-41d4-a716-446655440001',
    'Marianela Montalvo'
) ON CONFLICT (account_number) DO NOTHING;

-- Account 4: Juan Osorio - CORRIENTE
-- Initial balance will be set by first movement
INSERT INTO account (account_number, account_type, balance, state, customer_id, customer_name)
VALUES (
    585545,
    'CORRIENTE',
    0.00,  -- Balance BEFORE the credit movement
    TRUE,
    '770e8400-e29b-41d4-a716-446655440002',
    'Juan Osorio'
) ON CONFLICT (account_number) DO NOTHING;

-- Account 5: Juan Osorio - AHORRO
INSERT INTO account (account_number, account_type, balance, state, customer_id, customer_name)
VALUES (
    588923,
    'AHORRO',
    0.00,
    FALSE,
    '770e8400-e29b-41d4-a716-446655440002',
    'Juan Osorio'
) ON CONFLICT (account_number) DO NOTHING;

-- =====================================================
-- Test movements (triggers update balance)
-- =====================================================

-- The update_balance_after_movement trigger will update account balance automatically

-- Movement 1: Credit to account 478758 (Jose Lema)
INSERT INTO movement (
    movement_id, account_number, movement_type, amount,
    balance_before, balance_after, description, transaction_id
)
VALUES (
    uuid_generate_v4(),
    478758,
    'CREDITO',
    600.00,
    1400.00,
    2000.00,
    'Initial deposit',
    'TXN-' || to_char(CURRENT_TIMESTAMP, 'YYYYMMDD-HH24MISS') || '-' || substr(md5(random()::text), 1, 4)
) ON CONFLICT (transaction_id) DO NOTHING;

-- Movement 2: Credit to account 225487 (Marianela Montalvo)
INSERT INTO movement (
    movement_id, account_number, movement_type, amount,
    balance_before, balance_after, description, transaction_id
)
VALUES (
    uuid_generate_v4(),
    225487,
    'CREDITO',
    600.00,
    0.00,
    600.00,
    'Cash deposit',
    'TXN-' || to_char(CURRENT_TIMESTAMP, 'YYYYMMDD-HH24MISS') || '-' || substr(md5(random()::text), 1, 4)
) ON CONFLICT (transaction_id) DO NOTHING;

-- Movement 3: Debit from account 496825 (Marianela Montalvo)
INSERT INTO movement (
    movement_id, account_number, movement_type, amount,
    balance_before, balance_after, description, transaction_id
)
VALUES (
    uuid_generate_v4(),
    496825,
    'DEBITO',
    540.00,
    540.00,
    0.00,
    'ATM withdrawal',
    'TXN-' || to_char(CURRENT_TIMESTAMP, 'YYYYMMDD-HH24MISS') || '-' || substr(md5(random()::text), 1, 4)
) ON CONFLICT (transaction_id) DO NOTHING;

-- Movement 4: Debit from account 225487 (Marianela Montalvo) - to reach final balance of 100
INSERT INTO movement (
    movement_id, account_number, movement_type, amount,
    balance_before, balance_after, description, transaction_id
)
VALUES (
    uuid_generate_v4(),
    225487,
    'DEBITO',
    500.00,
    600.00,
    100.00,
    'Bill payment',
    'TXN-' || to_char(CURRENT_TIMESTAMP, 'YYYYMMDD-HH24MISS') || '-' || substr(md5(random()::text), 1, 5)
) ON CONFLICT (transaction_id) DO NOTHING;

-- Movement 5: Credit to account 585545 (Juan Osorio)
INSERT INTO movement (
    movement_id, account_number, movement_type, amount,
    balance_before, balance_after, description, transaction_id
)
VALUES (
    uuid_generate_v4(),
    585545,
    'CREDITO',
    1000.00,
    0.00,
    1000.00,
    'Account opening deposit',
    'TXN-' || to_char(CURRENT_TIMESTAMP, 'YYYYMMDD-HH24MISS') || '-' || substr(md5(random()::text), 1, 4)
) ON CONFLICT (transaction_id) DO NOTHING;


-- =====================================================
-- Table and Column Comments
-- =====================================================
COMMENT ON TABLE account IS 'Bank accounts for customers';
COMMENT ON TABLE movement IS 'Transactional movements (debits, credits, reversals)';

COMMENT ON COLUMN account.version IS 'Version for optimistic locking concurrency control';
COMMENT ON COLUMN account.customer_name IS 'Denormalized to improve query performance';
COMMENT ON COLUMN movement.transaction_id IS 'Unique transaction ID for complete traceability';
COMMENT ON COLUMN movement.idempotency_key IS 'Idempotency key to prevent duplicates';
COMMENT ON COLUMN movement.reversed IS 'Indicates if movement has been reversed';

