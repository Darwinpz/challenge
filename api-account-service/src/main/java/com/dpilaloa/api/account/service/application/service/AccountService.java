package com.dpilaloa.api.account.service.application.service;

import com.dpilaloa.api.account.service.application.dto.*;
import com.dpilaloa.api.account.service.application.port.input.*;
import com.dpilaloa.api.account.service.application.port.output.AccountRepositoryPort;
import com.dpilaloa.api.account.service.application.port.output.CustomerServiceClientPort;
import com.dpilaloa.api.account.service.application.port.output.EventPublisherPort;
import com.dpilaloa.api.account.service.application.port.output.MovementRepositoryPort;
import com.dpilaloa.api.account.service.domain.exception.*;
import com.dpilaloa.api.account.service.domain.model.Account;
import com.dpilaloa.api.account.service.domain.model.Customer;
import com.dpilaloa.api.account.service.domain.model.Movement;
import com.dpilaloa.api.account.service.domain.model.MovementType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * APPLICATION SERVICE: AccountService
 * <p>
 * DESIGN PATTERNS APPLIED:
 * - Service Pattern: Orchestrates use cases and domain logic
 * - Facade Pattern: Single entry point for all account operations
 * - Repository Pattern: Uses repositories for data access
 * - Event-Driven Pattern: Publishes events to Kafka
 * - Circuit Breaker Pattern: Resilient customer validation with fallback
 * - Dependency Injection Pattern: Constructor injection
 * <p>
 * SOLID PRINCIPLES:
 * - Single Responsibility: Orchestrates account use cases only
 * - Open/Closed: Open for extension (new use cases), closed for modification
 * - Liskov Substitution: Implements all use case interfaces
 * - Interface Segregation: Implements multiple focused interfaces
 * - Dependency Inversion: Depends on abstractions (ports), not implementations
 * <p>
 * BUSINESS CAPABILITIES:
 * - Create Account (with customer validation)
 * - Create Movement (deposit/withdrawal with balance update)
 * - Get Account by number
 * - Get Accounts by customer
 * - Update Account (administrative changes)
 * - Delete Account (cascade delete movements)
 * - Generate Account Statement (comprehensive report)
 * <p>
 * RESILIENCE STRATEGY:
 * - Hybrid customer validation (WebClient + Cache fallback)
 * - Optimistic locking for concurrent updates
 * - Idempotency for movements (transactionId)
 * - Fire-and-forget event publishing
 * <p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService implements
        CreateAccountUseCase,
        CreateMovementUseCase,
        QueryAccountUseCase,
        UpdateAccountUseCase,
        DeleteAccountUseCase,
        GenerateAccountStatementUseCase {

    // ========================================================================
    // DEPENDENCIES (Output Ports) - Constructor Injection
    // ========================================================================

    /**
     * PATTERN: Dependency Injection via Constructor
     * SOLID: Dependency Inversion Principle - depend on abstractions
     */
    private final AccountRepositoryPort accountRepositoryPort;
    private final MovementRepositoryPort movementRepositoryPort;
    private final CustomerServiceClientPort customerServiceClientPort;
    private final EventPublisherPort eventPublisherPort;

    // ========================================================================
    // KAFKA TOPICS (Constants)
    // ========================================================================

    private static final String ACCOUNT_EVENTS_TOPIC = "banking.account.events";
    private static final String MOVEMENT_EVENTS_TOPIC = "banking.movement.events";
    private static final BigDecimal OVERDRAFT_LIMIT = new BigDecimal("10000.00");
    private static final int MAX_ACTIVE_ACCOUNTS_PER_CUSTOMER = 5;

    // ========================================================================
    // USE CASE: CreateAccountUseCase
    // ========================================================================

    /**
     * Create a new bank account for a customer
     * <p>
     * BUSINESS RULES:
     * - Customer must exist and be active (hybrid validation strategy)
     * - Initial balance must be >= 0
     * - Maximum 5 active accounts per customer
     * - Account type must be valid (AHORRO or CORRIENTE)
     * - Balance is set to initialBalance
     * - State is set to TRUE by default
     * <p>
     * PATTERN: Command Pattern - executes account creation command
     * SOLID: Single Responsibility - only creates accounts
     *
     * @param account Account to create (customerId, accountType, initialBalance required)
     * @return Mono<Account> Created account with generated accountNumber
     */
    @Override
    public Mono<Account> createAccount(Account account) {
        log.info("Creating new account for customer: {}, type: {}, initialBalance: {}",
                account.getCustomerId(), account.getAccountType(), account.getInitialBalance());

        return validateCustomerResilient(account.getCustomerId())
                .flatMap(customer -> {
                    // Set customer name from validated customer
                    account.setCustomerName(customer.getName());
                    return validateBusinessRulesForCreate(account, customer);
                })
                .flatMap(validatedAccount -> {
                    // BUSINESS RULE: Set account defaults for INSERT
                    validatedAccount.setBalance(validatedAccount.getInitialBalance());
                    validatedAccount.setState(true);

                    return accountRepositoryPort.save(validatedAccount);
                })
                .doOnSuccess(savedAccount -> {
                    log.info("Account created successfully: accountNumber={}, customerId={}",
                            savedAccount.getAccountNumber(), savedAccount.getCustomerId());

                    // PATTERN: Fire-and-Forget Event Publishing
                    publishAccountCreatedEvent(savedAccount);
                })
                .doOnError(error -> log.error("Error creating account for customer {}: {}",
                        account.getCustomerId(), error.getMessage()));
    }

    /**
     * Create account without customer HTTP validation (used by Kafka consumer)
     * <p>
     * DESIGN NOTE:
     * This method is used when creating accounts from Kafka events (customer.created).
     * Since the event itself confirms the customer exists, we skip the HTTP validation
     * to avoid unnecessary network calls and potential timeouts.
     * <p>
     * BUSINESS RULES:
     * - Initial balance must be >= 0
     * - Customer cannot have more than 5 active accounts
     * - Customer existence is assumed (caller responsibility)
     *
     * @param account Account to create (customerId, accountType, initialBalance required)
     * @return Mono<Account> Created account with generated accountNumber
     */
    public Mono<Account> createAccountWithoutCustomerValidation(Account account) {
        log.info("Creating new account (from Kafka event) for customer: {}, type: {}, initialBalance: {}",
                account.getCustomerId(), account.getAccountType(), account.getInitialBalance());

        return validateBusinessRulesForCreateWithoutCustomer(account)
                .flatMap(validatedAccount -> {
                    // BUSINESS RULE: Set account defaults for INSERT
                    validatedAccount.setBalance(validatedAccount.getInitialBalance());
                    validatedAccount.setState(true);

                    return accountRepositoryPort.save(validatedAccount);
                })
                .doOnSuccess(savedAccount -> {
                    log.info("Account created successfully (from Kafka): accountNumber={}, customerId={}",
                            savedAccount.getAccountNumber(), savedAccount.getCustomerId());

                    // PATTERN: Fire-and-Forget Event Publishing
                    publishAccountCreatedEvent(savedAccount);
                })
                .doOnError(error -> log.error("Error creating account (from Kafka) for customer {}: {}",
                        account.getCustomerId(), error.getMessage()));
    }

    /**
     * Validate business rules for account creation
     * <p>
     * BUSINESS RULES:
     * - Initial balance must be >= 0
     * - Customer can only have ONE account per type (AHORRO or CORRIENTE)
     * - Customer cannot have more than 5 active accounts
     *
     * @param account Account to validate
     * @param customer Optional customer object (can be null)
     * @return Mono<Account> Validated account
     */
    private Mono<Account> validateBusinessRulesForCreate(Account account, Customer customer) {
        // BUSINESS RULE: Initial balance must be >= 0
        if (account.getInitialBalance() == null || account.getInitialBalance().compareTo(BigDecimal.ZERO) < 0) {
            log.warn("Invalid initial balance for account creation: {}", account.getInitialBalance());
            return Mono.error(new IllegalArgumentException("Initial balance must be greater than or equal to 0"));
        }

        UUID customerId = account.getCustomerId();
        UUID customerIdentifier = customer != null ? customer.getCustomerId() : customerId;

        // BUSINESS RULE: Customer can only have ONE account per type (AHORRO or CORRIENTE)
        return accountRepositoryPort.existsActiveAccountByCustomerIdAndAccountType(
                        customerId,
                        account.getAccountType().name())
                .flatMap(exists -> {
                    if (exists) {
                        log.warn("Customer {} already has an active {} account", customerIdentifier, account.getAccountType());
                        return Mono.error(new IllegalStateException(
                                String.format("Customer already has an active %s account. " +
                                                "Only one account per type (AHORRO/CORRIENTE) is allowed.",
                                        account.getAccountType())));
                    }
                    log.debug("Customer {} does not have an active {} account, validation passed", customerIdentifier, account.getAccountType());

                    // BUSINESS RULE: Maximum 5 active accounts per customer
                    return accountRepositoryPort.countActiveAccountsByCustomerId(customerId);
                })
                .flatMap(activeCount -> {
                    if (activeCount >= MAX_ACTIVE_ACCOUNTS_PER_CUSTOMER) {
                        log.warn("Customer {} already has {} active accounts (max: {})", customerIdentifier, activeCount, MAX_ACTIVE_ACCOUNTS_PER_CUSTOMER);
                        return Mono.error(new IllegalStateException(
                                String.format("Customer cannot have more than %d active accounts. Current: %d", MAX_ACTIVE_ACCOUNTS_PER_CUSTOMER, activeCount)));
                    }
                    log.debug("Customer {} has {} active accounts, validation passed", customerIdentifier, activeCount);
                    return Mono.just(account);
                });
    }

    /**
     * Validate business rules for account creation (without customer object)
     * <p>
     * This method is used when creating accounts from Kafka events where
     * customer validation has already been done by the event itself.
     * <p>
     * BUSINESS RULES:
     * - Initial balance must be >= 0
     * - Customer can only have ONE account per type (AHORRO or CORRIENTE)
     * - Customer cannot have more than 5 active accounts
     *
     * @param account Account to validate
     * @return Mono<Account> Validated account
     */
    private Mono<Account> validateBusinessRulesForCreateWithoutCustomer(Account account) {
        return validateBusinessRulesForCreate(account, null);
    }

    // ========================================================================
    // USE CASE: CreateMovementUseCase
    // ========================================================================

    /**
     * Create a new transaction movement (deposit or withdrawal)
     * <p>
     * BUSINESS RULES:
     * - Account must exist and be active
     * - Transaction ID must be unique (idempotency)
     * - Amount must be > 0
     * - For withdrawals: Balance must be sufficient (including overdraft)
     * - Balance is updated atomically
     * - Movement stores balanceBefore and balanceAfter
     * <p>
     * PATTERN: Command Pattern - executes movement creation command
     * PATTERN: Idempotency Pattern - uses transactionId to prevent duplicates
     * SOLID: Single Responsibility - only creates movements
     *
     * @param movement Movement to create (accountNumber, movementType, amount, transactionId required)
     * @return Mono<Movement> Created movement with balanceBefore and balanceAfter
     */
    @Override
    public Mono<Movement> createMovement(Movement movement) {
        log.info("Creating movement: account={}, type={}, amount={}, txId={}, idempotencyKey={}",
                movement.getAccountNumber(), movement.getMovementType(),
                movement.getAmount(), movement.getTransactionId(), movement.getIdempotencyKey());

        return validateMovementIdempotency(movement.getTransactionId())
                .then(validateIdempotencyKey(movement.getIdempotencyKey()))
                .then(validateMovementAmount(movement))
                .then(accountRepositoryPort.findByAccountNumber(movement.getAccountNumber()))
                .switchIfEmpty(Mono.error(new AccountNotFoundException(movement.getAccountNumber())))
                .flatMap(this::validateAccountIsActive)
                .flatMap(account -> processMovement(account, movement))
                .doOnSuccess(savedMovement -> {
                    log.info("Movement created successfully: movementId={}, accountNumber={}, balanceAfter={}",
                            savedMovement.getMovementId(), savedMovement.getAccountNumber(),
                            savedMovement.getBalanceAfter());

                    // PATTERN: Fire-and-Forget Event Publishing
                    publishMovementCreatedEvent(savedMovement);
                })
                .doOnError(error -> log.error("Error creating movement for account {}: {}",
                        movement.getAccountNumber(), error.getMessage()));
    }

    /**
     * Validate transaction ID is unique (idempotency check)
     * <p>
     * PATTERN: Idempotency Pattern
     * BUSINESS RULE: Each transaction ID must be unique
     *
     * @param transactionId Transaction ID to validate
     * @return Mono<Void> Completes if valid, error if duplicate
     */
    private Mono<Void> validateMovementIdempotency(String transactionId) {
        return movementRepositoryPort.existsByTransactionId(transactionId)
                .flatMap(exists -> {
                    if (exists) {
                        log.warn("Duplicate transaction ID detected: {}", transactionId);
                        return Mono.error(new DuplicateTransactionException(transactionId));
                    }
                    log.debug("Transaction ID {} is unique, validation passed", transactionId);
                    return Mono.empty();
                });
    }

    /**
     * Validate idempotency key is unique (prevents duplicate requests)
     * <p>
     * PATTERN: Idempotency Pattern
     * BUSINESS RULE: Each idempotency key must be unique (if provided)
     * <p>
     * This ensures exactly-once semantics in distributed systems:
     * - Client retries with same idempotency key return existing transaction
     * - Different idempotency keys create new transactions
     * - Null idempotency keys are allowed (no validation)
     *
     * @param idempotencyKey Idempotency key to validate (nullable)
     * @return Mono<Void> Completes if valid, error if duplicate
     */
    private Mono<Void> validateIdempotencyKey(UUID idempotencyKey) {
        // If idempotency key is not provided, skip validation
        if (idempotencyKey == null) {
            log.debug("No idempotency key provided, skipping validation");
            return Mono.empty();
        }

        return movementRepositoryPort.findByIdempotencyKey(idempotencyKey)
                .flatMap(existingMovement -> {
                    log.warn("Duplicate idempotency key detected: {} (existing movementId: {})",
                            idempotencyKey, existingMovement.getMovementId());
                    return Mono.error(new com.dpilaloa.api.account.service.domain.exception.DuplicateIdempotencyKeyException(
                            idempotencyKey, existingMovement.getMovementId()));
                })
                .then()
                .doOnSuccess(v -> log.debug("Idempotency key {} is unique, validation passed", idempotencyKey));
    }

    /**
     * Validate movement amount is positive
     * <p>
     * BUSINESS RULE: Amount must be > 0
     *
     * @param movement Movement to validate
     * @return Mono<Void> Completes if valid, error if invalid
     */
    private Mono<Void> validateMovementAmount(Movement movement) {
        if (movement.getAmount() == null || movement.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid movement amount: {}", movement.getAmount());
            return Mono.error(new IllegalArgumentException("Movement amount must be greater than 0"));
        }
        return Mono.empty();
    }

    /**
     * Validate account is active
     * <p>
     * BUSINESS RULE: Movements can only be created for active accounts
     *
     * @param account Account to validate
     * @return Mono<Account> Account if active, error if inactive
     */
    private Mono<Account> validateAccountIsActive(Account account) {
        if (!account.isActive()) {
            log.warn("Account {} is not active, cannot create movement", account.getAccountNumber());
            return Mono.error(new AccountNotActiveException(account.getAccountNumber()));
        }
        log.debug("Account {} is active, validation passed", account.getAccountNumber());
        return Mono.just(account);
    }

    /**
     * Process movement: update balance and save
     * <p>
     * PATTERN: Domain Logic in Aggregate Root
     * BUSINESS RULE: Balance is updated atomically
     *
     * @param account  Account to update
     * @param movement Movement to create
     * @return Mono<Movement> Saved movement
     */
    private Mono<Movement> processMovement(Account account, Movement movement) {
        // Capture balance BEFORE movement
        BigDecimal balanceBefore = account.getBalance();
        log.debug("Processing movement: balanceBefore={}, type={}, amount={}",
                balanceBefore, movement.getMovementType(), movement.getAmount());

        try {
            // BUSINESS LOGIC: Update account balance based on movement type
            BigDecimal balanceAfter;

            if (movement.getMovementType() == MovementType.CREDITO) {

                balanceAfter = account.credit(movement.getAmount());
                log.debug("Credit processed: balanceAfter={}", balanceAfter);

            } else if (movement.getMovementType() == MovementType.DEBITO) {

                balanceAfter = account.debit(movement.getAmount());
                log.debug("Debit processed: balanceAfter={}", balanceAfter);

            } else if (movement.getMovementType() == MovementType.REVERSA) {
                // We just set balanceAfter to current balance (trigger will update it)
                balanceAfter = balanceBefore;
                log.debug("Reversal movement - trigger will handle balance update: reversedMovementId={}", movement.getReversedMovementId());

            } else {
                return Mono.error(new IllegalArgumentException("Invalid movement type: " + movement.getMovementType()));
            }

            // Set movement balance tracking
            movement.setBalanceBefore(balanceBefore);
            movement.setBalanceAfter(balanceAfter);

            // Saving movement will trigger the database function that updates account balance
            return movementRepositoryPort.save(movement)
                    .doOnSuccess(savedMovement -> log.debug("Movement saved: balanceBefore={}, balanceAfter={}",
                            savedMovement.getBalanceBefore(), savedMovement.getBalanceAfter()));

        } catch (IllegalStateException e) {
            // Thrown by Account.debit() when insufficient balance
            log.warn("Insufficient balance for withdrawal: account={}, balance={}, requestedAmount={}", account.getAccountNumber(), balanceBefore, movement.getAmount());
            return Mono.error(new InsufficientBalanceException( balanceBefore, movement.getAmount(), OVERDRAFT_LIMIT));
        } catch (IllegalArgumentException e) {
            log.error("Invalid movement: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    // ========================================================================
    // USE CASE: GetAccountUseCase
    // ========================================================================

    /**
     * Get an account by its account number
     * <p>
     * PATTERN: Query Pattern - read-only operation
     * SOLID: Single Responsibility - only retrieves account
     *
     * @param accountNumber Account number to retrieve
     * @return Mono<Account> Account if found
     */
    @Override
    public Mono<Account> getAccount(Long accountNumber) {
        log.debug("Retrieving account: accountNumber={}", accountNumber);

        return accountRepositoryPort.findByAccountNumber(accountNumber)
                .switchIfEmpty(Mono.error(new AccountNotFoundException(accountNumber)))
                .doOnSuccess(account -> log.debug("Account retrieved: accountNumber={}, customerId={}, balance={}", account.getAccountNumber(), account.getCustomerId(), account.getBalance()))
                .doOnError(error -> log.error("Error retrieving account {}: {}", accountNumber, error.getMessage()));
    }

    // ========================================================================
    // USE CASE: GetAccountsByCustomerUseCase
    // ========================================================================

    /**
     * Get all accounts belonging to a customer
     * <p>
     * PATTERN: Query Pattern - read-only operation
     * SOLID: Single Responsibility - only retrieves customer accounts
     *
     * @param customerId Customer ID
     * @return Flux<Account> Stream of accounts (may be empty)
     */
    @Override
    public Flux<Account> getAccountsByCustomer(UUID customerId) {
        log.debug("Retrieving accounts for customer: {}", customerId);

        return accountRepositoryPort.findByCustomerId(customerId)
                .doOnComplete(() -> log.debug("Finished retrieving accounts for customer: {}", customerId))
                .doOnError(error -> log.error("Error retrieving accounts for customer {}: {}", customerId, error.getMessage()));
    }

    // ========================================================================
    // USE CASE: GetAllAccountsUseCase
    // ========================================================================

    /**
     * Get all accounts in the system
     * <p>
     * PATTERN: Query Pattern - read-only operation
     * SOLID: Single Responsibility - only retrieves all accounts
     * <p>
     * USE CASES:
     * - Admin dashboard showing all accounts
     * - Global reports
     * - System monitoring
     *
     * @return Flux<Account> Stream of all accounts (may be empty)
     */
    @Override
    public Flux<Account> getAllAccounts() {
        log.debug("Retrieving all accounts");

        return accountRepositoryPort.findAll()
                .doOnComplete(() -> log.debug("Finished retrieving all accounts"))
                .doOnError(error -> log.error("Error retrieving all accounts: {}", error.getMessage()));
    }

    // ========================================================================
    // USE CASE: UpdateAccountUseCase
    // ========================================================================

    /**
     * Update an existing account
     * <p>
     * BUSINESS RULES:
     * - Account must exist
     * - Cannot update balance directly (only via movements)
     * - Can update: accountType, state
     * - Uses optimistic locking (version)
     * <p>
     * PATTERN: Command Pattern - executes account update command
     * PATTERN: Optimistic Locking - version control for concurrency
     * SOLID: Single Responsibility - only updates accounts
     *
     * @param accountNumber Account number to update
     * @param account       Updated account data
     * @return Mono<Account> Updated account
     */
    @Override
    public Mono<Account> updateAccount(Long accountNumber, Account account) {
        log.info("Updating account: accountNumber={}", accountNumber);

        return accountRepositoryPort.findByAccountNumber(accountNumber)
                .switchIfEmpty(Mono.error(new AccountNotFoundException(accountNumber)))
                .flatMap(existingAccount -> {

                    log.debug("Updating account fields: accountType={}, state={}", account.getAccountType(), account.getState());

                    if (account.getAccountType() != null) {
                        existingAccount.setAccountType(account.getAccountType());
                    }
                    if (account.getState() != null) {
                        existingAccount.setState(account.getState());
                    }

                    return accountRepositoryPort.save(existingAccount);
                })
                .doOnSuccess(updatedAccount -> {
                    log.info("Account updated successfully: accountNumber={}, state={}", updatedAccount.getAccountNumber(), updatedAccount.getState());

                    // PATTERN: Fire-and-Forget Event Publishing
                    publishAccountUpdatedEvent(updatedAccount);
                })
                .doOnError(error -> log.error("Error updating account {}: {}", accountNumber, error.getMessage()));
    }

    // ========================================================================
    // USE CASE: DeleteAccountUseCase
    // ========================================================================

    /**
     * Delete an account (hard delete - permanent removal)
     * <p>
     * BUSINESS RULES:
     * - Account must exist
     * - Balance must be 0 (cannot delete account with funds)
     * - Cascade delete all associated movements
     * - Publish ACCOUNT_DELETED event
     * <p>
     * PATTERN: Command Pattern - executes account deletion command
     * PATTERN: Cascade Delete - remove dependent entities first
     * SOLID: Single Responsibility - only deletes accounts
     *
     * @param accountNumber Account number to delete
     * @return Mono<Void> Completion signal
     */
    @Override
    public Mono<Void> deleteAccount(Long accountNumber) {
        log.info("Deleting account: accountNumber={}", accountNumber);

        return accountRepositoryPort.findByAccountNumber(accountNumber)
                .switchIfEmpty(Mono.error(new AccountNotFoundException(accountNumber)))
                .flatMap(account -> {
                    // BUSINESS RULE: Cannot delete account with balance
                    if (account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
                        log.warn("Cannot delete account {} with non-zero balance: {}", accountNumber, account.getBalance());
                        return Mono.error(new IllegalStateException(
                                String.format("Cannot delete account with non-zero balance. Current balance: %s", account.getBalance())));
                    }

                    log.debug("Account balance is zero, proceeding with deletion");

                    // PATTERN: Cascade Delete - delete movements first
                    return movementRepositoryPort.deleteByAccountNumber(accountNumber)
                            .doOnSuccess(v -> log.debug("Movements deleted for account: {}", accountNumber))
                            .then(accountRepositoryPort.deleteByAccountNumber(accountNumber))
                            .doOnSuccess(v -> log.info("Account deleted successfully: accountNumber={}", accountNumber))
                            .then(Mono.fromRunnable(() -> publishAccountDeletedEvent(account)))
                            .then();
                })
                .doOnError(error -> log.error("Error deleting account {}: {}",
                        accountNumber, error.getMessage()));
    }

    /**
     * Delete all accounts for a customer (cascade delete movements)
     * <p>
     * BUSINESS FLOW:
     * 1. Find all accounts for the customer
     * 2. For each account:
     * - Delete associated movements (cascade)
     * - Delete account record
     * - Publish ACCOUNT_DELETED event to Kafka
     * 3. Return count of deleted accounts
     * <p>
     * USE CASE:
     * - Called from Kafka consumer when CUSTOMER_DELETED event is received
     * - Maintains referential integrity across microservices
     * - NO BALANCE CHECK - force delete even with balance
     * <p>
     * PATTERN: Cascade Delete Pattern
     * SOLID: Single Responsibility - only deletes accounts for customer
     *
     * @param customerId Customer ID (UUID)
     * @return Mono<Long> Number of accounts deleted
     */
    @Override
    public Mono<Long> deleteAccountsByCustomerId(UUID customerId) {
        log.info("Deleting all accounts for customer: customerId={}", customerId);

        return accountRepositoryPort.findByCustomerId(customerId)
                .collectList()
                .flatMap(accounts -> {
                    if (accounts.isEmpty()) {
                        log.info("No accounts found for customer: customerId={}", customerId);
                        return Mono.just(0L);
                    }

                    log.info("Found {} accounts for customer: customerId={}", accounts.size(), customerId);

                    // Delete each account with its movements
                    return Flux.fromIterable(accounts)
                            .flatMap(account -> {
                                Long accountNumber = account.getAccountNumber();
                                log.debug("Deleting account: accountNumber={}, balance={}",
                                        accountNumber, account.getBalance());

                                // PATTERN: Cascade Delete - delete movements first, then account
                                return movementRepositoryPort.deleteByAccountNumber(accountNumber)
                                        .doOnSuccess(v -> log.debug("Movements deleted for account by Number: {}", accountNumber))
                                        .then(accountRepositoryPort.deleteByAccountNumber(accountNumber))
                                        .doOnSuccess(v -> log.debug("Account deleted: accountNumber={}", accountNumber))
                                        .then(Mono.fromRunnable(() -> publishAccountDeletedEvent(account)))
                                        .thenReturn(1L);
                            })
                            .reduce(0L, Long::sum)
                            .doOnSuccess(count -> log.info("Successfully deleted {} accounts for customer: customerId={}", count, customerId))
                            .doOnError(error -> log.error("Error deleting accounts for customer {}: {}", customerId, error.getMessage()));
                });
    }

    // ========================================================================
    // USE CASE: GenerateAccountStatementUseCase
    // ========================================================================

    /**
     * Generate account statement report for a customer
     * <p>
     * BUSINESS FLOW:
     * 1. Validate customer exists (via cache or WebClient)
     * 2. Retrieve all customer accounts
     * 3. For each account, get movements in date range
     * 4. Calculate initial/final balances
     * 5. Generate summary (total credits, debits, net change)
     * 6. Return complete report
     * <p>
     * PATTERN: Query Pattern - read-only reporting operation
     * PATTERN: DTO Pattern - returns structured report DTO
     * SOLID: Single Responsibility - only generates statements
     *
     * @param customerId Customer ID
     * @param startDate  Start date of report period
     * @param endDate    End date of report period
     * @return Mono<AccountStatementReport> Complete statement report
     */
    @Override
    public Mono<AccountStatementReport> generateAccountStatement(UUID customerId, LocalDate startDate, LocalDate endDate) {
        log.info("Generating account statement: customerId={}, period={} to {}", customerId, startDate, endDate);

        // BUSINESS RULE: Validate date range
        if (startDate.isAfter(endDate)) {
            log.warn("Invalid date range: startDate={} is after endDate={}", startDate, endDate);
            return Mono.error(new IllegalArgumentException("Start date must be before or equal to end date"));
        }

        return validateCustomerResilient(customerId)
                .flatMap(customer -> generateStatement(customer, startDate, endDate))
                .doOnSuccess(report -> log.info("Account statement generated: reportId={}, customerId={}, totalAccounts={}",
                        report.reportId(), customerId, report.summary().totalAccounts()))
                .doOnError(error -> log.error("Error generating statement for customer {}: {}", customerId, error.getMessage()));
    }

    /**
     * Generate the complete statement report
     *
     * @param customer  Customer information
     * @param startDate Start date
     * @param endDate   End date
     * @return Mono<AccountStatementReport> Complete report
     */
    private Mono<AccountStatementReport> generateStatement(Customer customer, LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(23, 59, 59);

        return accountRepositoryPort.findByCustomerId(customer.getCustomerId())
                .collectList()
                .flatMap(accounts -> {
                    if (accounts.isEmpty()) {
                        log.debug("No accounts found for customer: {}", customer.getCustomerId());
                        return Mono.just(createEmptyStatement(customer, startDate, endDate));
                    }

                    // For each account, get movements and build statement
                    return Flux.fromIterable(accounts)
                            .flatMap(account -> buildAccountStatement(account, startDateTime, endDateTime))
                            .collectList()
                            .map(accountStatements -> buildCompleteReport(customer, startDate, endDate, accountStatements));
                });
    }

    /**
     * Build statement for a single account
     *
     * @param account       Account
     * @param startDateTime Start date/time
     * @param endDateTime   End date/time
     * @return Mono<AccountStatement> Account statement
     */
    private Mono<AccountStatement> buildAccountStatement(Account account, LocalDateTime startDateTime, LocalDateTime endDateTime) {
        return movementRepositoryPort.findByAccountNumberAndDateRange(
                        account.getAccountNumber(), startDateTime, endDateTime)
                .collectList()
                .map(movements -> {
                    List<MovementDetail> movementDetails = movements.stream()
                            .map(this::buildMovementDetail)
                            .toList();

                    // Calculate initial and final balances for the period
                    BigDecimal initialBalance = calculateInitialBalance(account, movements);
                    BigDecimal finalBalance = account.getBalance();

                    return new AccountStatement(
                            account.getAccountNumber(),
                            account.getAccountType().name(),
                            account.getState(),
                            initialBalance,
                            finalBalance,
                            movementDetails
                    );
                });
    }

    /**
     * Build movement detail DTO
     *
     * @param movement Movement
     * @return MovementDetail DTO
     */
    private MovementDetail buildMovementDetail(Movement movement) {
        // Use ISO_LOCAL_DATE_TIME format for consistency with DateTimeMapper
        // Format: "2025-11-10T22:21:42" (with 'T' separator)
        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        String description = movement.getMovementType() == MovementType.CREDITO
                ? String.format("Crédito de %s", movement.getAmount())
                : String.format("Débito de %s", movement.getAmount());

        return new MovementDetail(
                movement.getCreatedAt().format(formatter),
                movement.getMovementType().name(),
                description,
                movement.getAmount(),
                movement.getBalanceAfter(),
                movement.getTransactionId()
        );
    }

    /**
     * Calculate initial balance for the period
     * Initial balance = current balance - net change during period
     *
     * @param account   Account
     * @param movements Movements in period
     * @return BigDecimal Initial balance
     */
    private BigDecimal calculateInitialBalance(Account account, List<Movement> movements) {
        BigDecimal netChange = movements.stream()
                .map(Movement::getNetEffect)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return account.getBalance().subtract(netChange);
    }

    /**
     * Build complete statement report
     *
     * @param customer          Customer
     * @param startDate         Start date
     * @param endDate           End date
     * @param accountStatements Account statements
     * @return AccountStatementReport Complete report
     */
    private AccountStatementReport buildCompleteReport(
            Customer customer, LocalDate startDate, LocalDate endDate,
            List<AccountStatement> accountStatements) {

        // Calculate summary
        BigDecimal totalCredits = accountStatements.stream()
                .flatMap(as -> as.movements().stream())
                .filter(m -> m.movementType().equals(MovementType.CREDITO.name()))
                .map(MovementDetail::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalDebits = accountStatements.stream()
                .flatMap(as -> as.movements().stream())
                .filter(m -> m.movementType().equals(MovementType.DEBITO.name()))
                .map(MovementDetail::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Integer totalMovements = accountStatements.stream()
                .mapToInt(as -> as.movements().size())
                .sum();

        BigDecimal netChange = totalCredits.subtract(totalDebits);

        StatementSummary summary = new StatementSummary(
                accountStatements.size(),
                totalCredits,
                totalDebits,
                totalMovements,
                netChange
        );

        return new AccountStatementReport(
                UUID.randomUUID(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                new CustomerInfo(customer.getCustomerId(), customer.getName(), customer.getIdentification()),
                new ReportPeriod(startDate.toString(), endDate.toString()),
                accountStatements,
                summary
        );
    }

    /**
     * Create empty statement when customer has no accounts
     *
     * @param customer  Customer
     * @param startDate Start date
     * @param endDate   End date
     * @return AccountStatementReport Empty report
     */
    private AccountStatementReport createEmptyStatement(Customer customer, LocalDate startDate, LocalDate endDate) {

        StatementSummary summary = new StatementSummary(0, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO);

        return new AccountStatementReport(
                UUID.randomUUID(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                new CustomerInfo(customer.getCustomerId(), customer.getName(), customer.getIdentification()),
                new ReportPeriod(startDate.toString(), endDate.toString()),
                List.of(),
                summary
        );
    }

    // ========================================================================
    // CUSTOMER VALIDATION - HYBRID STRATEGY (WebClient + Cache Fallback)
    // ========================================================================

    /**
     * Validate customer exists and is active using WebClient
     * <p>
     * VALIDATION STRATEGY: Direct HTTP call to Customer Service
     * - Calls customer-service via WebClient
     * - Validates customer exists and is active
     * - Timeout of 3 seconds for resilience
     * <p>
     * NOTE: Database cache removed - validation done via HTTP only
     * Optional: Add Caffeine/Redis cache for better performance
     *
     * @param customerId Customer ID to validate
     * @return Mono<Customer> Validated customer
     */
    private Mono<Customer> validateCustomerResilient(UUID customerId) {
        log.debug("Validating customer via WebClient: {}", customerId);

        return customerServiceClientPort.validateCustomer(customerId)
                .flatMap(this::validateCustomerState)
                .doOnSuccess(customer -> log.debug("Customer {} validated successfully", customerId))
                .doOnError(error -> log.error("Error validating customer {}: {}", customerId, error.getMessage()));
    }

    /**
     * Validate customer state (must be active)
     * <p>
     * BUSINESS RULE: Customer must be active (state = TRUE)
     *
     * @param customer Customer to validate
     * @return Mono<Customer> Customer if active
     */
    private Mono<Customer> validateCustomerState(Customer customer) {
        if (!customer.isActive()) {
            log.warn("Customer {} is not active, validation failed", customer.getCustomerId());
            return Mono.error(new CustomerNotActiveException(customer.getCustomerId()));
        }
        log.debug("Customer {} is active, validation passed", customer.getCustomerId());
        return Mono.just(customer);
    }

    // ========================================================================
    // EVENT PUBLISHING - KAFKA (Fire-and-Forget)
    // ========================================================================

    /**
     * Publish ACCOUNT_CREATED event to Kafka
     * <p>
     * PATTERN: Fire-and-Forget Event Publishing
     * PATTERN: Event-Driven Architecture
     * SOLID: Single Responsibility - only publishes event
     *
     * @param account Created account
     */
    private void publishAccountCreatedEvent(Account account) {
        AccountEventDTO event = AccountEventDTO.builder()
                .eventType("account.created")
                .accountNumber(account.getAccountNumber())
                .customerId(account.getCustomerId())
                .accountType(account.getAccountType().name())
                .initialBalance(account.getInitialBalance())
                .balance(account.getBalance())
                .state(account.getState())
                .timestamp(LocalDateTime.now())
                .build();

        eventPublisherPort.publish(ACCOUNT_EVENTS_TOPIC, account.getAccountNumber().toString(), event)
                .subscribe(
                        v -> log.debug("ACCOUNT_CREATED event published: accountNumber={}", account.getAccountNumber()),
                        error -> log.error("Error publishing ACCOUNT_CREATED event: {}", error.getMessage())
                );
    }

    /**
     * Publish ACCOUNT_UPDATED event to Kafka
     *
     * @param account Updated account
     */
    private void publishAccountUpdatedEvent(Account account) {
        AccountEventDTO event = AccountEventDTO.builder()
                .eventType("account.updated")
                .accountNumber(account.getAccountNumber())
                .customerId(account.getCustomerId())
                .accountType(account.getAccountType().name())
                .balance(account.getBalance())
                .state(account.getState())
                .timestamp(LocalDateTime.now())
                .build();

        eventPublisherPort.publish(ACCOUNT_EVENTS_TOPIC, account.getAccountNumber().toString(), event)
                .subscribe(
                        v -> log.debug("ACCOUNT_UPDATED event published: accountNumber={}", account.getAccountNumber()),
                        error -> log.error("Error publishing ACCOUNT_UPDATED event: {}", error.getMessage())
                );
    }

    /**
     * Publish ACCOUNT_DELETED event to Kafka
     *
     * @param account Deleted account
     */
    private void publishAccountDeletedEvent(Account account) {
        AccountEventDTO event = AccountEventDTO.builder()
                .eventType("account.deleted")
                .accountNumber(account.getAccountNumber())
                .customerId(account.getCustomerId())
                .timestamp(LocalDateTime.now())
                .build();

        eventPublisherPort.publish(ACCOUNT_EVENTS_TOPIC, account.getAccountNumber().toString(), event)
                .subscribe(
                        v -> log.debug("ACCOUNT_DELETED event published: accountNumber={}", account.getAccountNumber()),
                        error -> log.error("Error publishing ACCOUNT_DELETED event: {}", error.getMessage())
                );
    }

    /**
     * Publish MOVEMENT_CREATED event to Kafka
     *
     * @param movement Created movement
     */
    private void publishMovementCreatedEvent(Movement movement) {
        MovementEventDTO event = MovementEventDTO.builder()
                .eventType("movement.created")
                .movementId(movement.getMovementId())
                .accountNumber(movement.getAccountNumber())
                .movementType(movement.getMovementType().name())
                .amount(movement.getAmount())
                .balanceBefore(movement.getBalanceBefore())
                .balanceAfter(movement.getBalanceAfter())
                .transactionId(movement.getTransactionId())
                .timestamp(LocalDateTime.now())
                .build();

        eventPublisherPort.publish(MOVEMENT_EVENTS_TOPIC, movement.getAccountNumber().toString(), event)
                .subscribe(
                        v -> log.debug("MOVEMENT_CREATED event published: movementId={}", movement.getMovementId()),
                        error -> log.error("Error publishing MOVEMENT_CREATED event: {}", error.getMessage())
                );
    }
}
