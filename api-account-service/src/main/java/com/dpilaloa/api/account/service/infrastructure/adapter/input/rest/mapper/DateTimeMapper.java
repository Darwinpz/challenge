package com.dpilaloa.api.account.service.infrastructure.adapter.input.rest.mapper;

import org.mapstruct.Mapper;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MAPPER: DateTimeMapper
 * <p>
 * Provides conversion methods for date/time types used by MapStruct.
 * This is a utility mapper that can be referenced by other mappers.
 *
 */
@Mapper(componentModel = "spring")
public class DateTimeMapper {

    /**
     * Convert String (ISO 8601) to OffsetDateTime
     * MapStruct will automatically discover and use this method
     * <p>
     * Handles various ISO-8601 formats including edge cases with inconsistent nanosecond precision
     * and LocalDateTime without timezone offset
     */
    public OffsetDateTime map(String dateString) {
        if (dateString == null || dateString.isEmpty()) {
            return null;
        }
        try {
            // Try standard ISO format with offset first (e.g., "2025-11-10T22:07:45.123+00:00")
            return OffsetDateTime.parse(dateString, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            // Fallback 1: Try parsing as OffsetDateTime with relaxed formatter
            try {
                return OffsetDateTime.parse(dateString);
            } catch (Exception e2) {
                // Fallback 2: Try parsing as LocalDateTime (no timezone) and convert to OffsetDateTime with system default offset
                try {
                    java.time.LocalDateTime localDateTime = java.time.LocalDateTime.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    // Use system default zone offset (e.g., -05:00 for America/Bogota)
                    return localDateTime.atOffset(java.time.ZoneOffset.systemDefault().getRules().getOffset(localDateTime));
                } catch (Exception e3) {
                    // Last resort: Throw exception with original string
                    throw new IllegalArgumentException("Cannot parse date string: " + dateString, e3);
                }
            }
        }
    }

    /**
     * Convert OffsetDateTime to String (ISO 8601)
     */
    public String map(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
