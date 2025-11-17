package com.dpilaloa.api.customer.service.application.dto;

import com.dpilaloa.api.customer.service.domain.model.Customer;

/**
 * Result object containing created customer and JWT token.
 * <p>
 * DESIGN PATTERN: Result Object Pattern
 * Encapsulates multiple return values in a single object.
 */
public record CustomerCreationResult (Customer customer, String jwtToken){}
