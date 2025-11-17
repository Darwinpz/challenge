package com.dpilaloa.api.account.service.application.port.input;

import com.dpilaloa.api.account.service.domain.model.Account;
import reactor.core.publisher.Mono;

/**
 * APPLICATION PORT (INPUT): CreateAccountUseCase
 * DESIGN PATTERNS APPLIED:
 * - Port Pattern (Hexagonal Architecture): Interface defining business capability
 * - Command Pattern: Represents a command to create an account
 * - Interface Segregation Principle (ISP): Specific interface for one use case
 * <p>
 * SOLID PRINCIPLES:
 * - Interface Segregation: Clients depend only on create operation
 * - Dependency Inversion: Domain depends on abstraction, not concrete implementation
 * - Single Responsibility: Only defines account creation contract
 * <p>
 * IMPLEMENTATION:
 * - Implemented by AccountService in application layer
 * - Called by REST controllers (primary adapters)
 * <p>
 * BUSINESS RULES ENFORCED:
 * - Customer must exist and be active
 * - Initial balance must be >= 0
 * - Account type must be valid (AHORRO or CORRIENTE)
 * <p>
 */
public interface CreateAccountUseCase {

    /**
     * Create a new bank account for a customer
     * <p>
     * BUSINESS FLOW:
     * 1. Validate customer exists and is active (via WebClient + Kafka cache)
     * 2. Create account entity with initial balance
     * 3. Save account to database
     * 4. Publish ACCOUNT_CREATED event to Kafka
     * 5. Return created account
     * <p>
     * @param account Account to create (with customerId, accountType, initialBalance)
     * @return Mono<Account> Created account with generated accountNumber
     * <p>
     */
    Mono<Account> createAccount(Account account);
}
