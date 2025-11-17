package com.dpilaloa.api.customer.service.integration;

import com.dpilaloa.api.customer.service.infrastructure.adapter.output.persistence.entity.CustomerEntity;
import com.dpilaloa.api.customer.service.infrastructure.adapter.output.persistence.entity.PersonEntity;
import com.dpilaloa.api.customer.service.infrastructure.adapter.output.persistence.repository.CustomerR2dbcRepository;
import com.dpilaloa.api.customer.service.infrastructure.adapter.output.persistence.repository.PersonR2dbcRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST: Customer database operations with Testcontainers
 */
@DataR2dbcTest
@Import(CustomerIntegrationTest.TestConfig.class)
@Testcontainers
@DisplayName("Customer Integration Test")
class CustomerIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("schema.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () ->
            postgres.getJdbcUrl().replace("jdbc:", "r2dbc:"));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    @Autowired
    private PersonR2dbcRepository personRepository;

    @Autowired
    private CustomerR2dbcRepository customerRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID testPersonId;
    private UUID testCustomerId;

    @AfterEach
    void cleanup() {
        if (testCustomerId != null) {
            customerRepository.deleteById(testCustomerId).block();
        }
        if (testPersonId != null) {
            personRepository.deleteById(testPersonId).block();
        }
    }

    @Test
    @DisplayName("Should save and retrieve customer from database")
    void testSaveAndRetrieveCustomer() {
        // Given: Create person
        PersonEntity person = new PersonEntity();
        testPersonId = UUID.randomUUID();
        person.setPersonId(testPersonId);
        person.setName("Integration Test User");
        person.setGender("M");
        person.setAge(30);
        person.setIdentification("INT-TEST-" + System.currentTimeMillis());
        person.setAddress("Test Address 123");
        person.setPhone("099999999");

        // Save person
        PersonEntity savedPerson = personRepository.save(person).block();
        assertThat(savedPerson).isNotNull();
        assertThat(savedPerson.getPersonId()).isEqualTo(testPersonId);

        // Given: Create customer
        CustomerEntity customer = new CustomerEntity();
        testCustomerId = UUID.randomUUID();
        customer.setCustomerId(testCustomerId);
        customer.setPersonId(savedPerson.getPersonId());
        customer.setPassword(passwordEncoder.encode("testPassword123"));
        customer.setState(true);

        // When: Save customer
        CustomerEntity savedCustomer = customerRepository.save(customer).block();

        // Then: Verify customer was saved
        assertThat(savedCustomer).isNotNull();
        assertThat(savedCustomer.getCustomerId()).isEqualTo(testCustomerId);
        assertThat(savedCustomer.getPersonId()).isEqualTo(testPersonId);
        assertThat(savedCustomer.getState()).isTrue();

        // When: Retrieve customer
        CustomerEntity retrievedCustomer = customerRepository.findById(testCustomerId).block();

        // Then: Verify customer was retrieved correctly
        assertThat(retrievedCustomer).isNotNull();
        assertThat(retrievedCustomer.getCustomerId()).isEqualTo(testCustomerId);
        assertThat(retrievedCustomer.getPassword()).isNotEmpty();
    }
}
