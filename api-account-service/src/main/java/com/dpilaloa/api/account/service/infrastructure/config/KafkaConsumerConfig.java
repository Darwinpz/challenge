package com.dpilaloa.api.account.service.infrastructure.config;

import com.dpilaloa.api.account.service.infrastructure.adapter.input.messaging.kafka.dto.CustomerEventDTO;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * CONFIGURATION: Kafka Consumer Configuration
 * <p>
 * Configures Kafka consumer for receiving customer events from api-customer-service.
 * <p>
 * PATTERN: Configuration Pattern
 * LAYER: Infrastructure
 * <p>
 * FEATURES:
 * - JSON deserialization with error handling
 * - Consumer group configuration
 * - Offset management (auto-commit)
 * - Concurrent message processing
 * <p>
 * TOPICS CONSUMED:
 * - customer-events: Customer creation/update/deletion events
 *
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:account-service-group}")
    private String groupId;

    /**
     * Consumer Factory for CustomerEventDTO
     * <p>
     * Configures JSON deserialization with error handling wrapper.
     */
    @Bean
    public ConsumerFactory<String, CustomerEventDTO> consumerFactory() {
        Map<String, Object> config = new HashMap<>();

        // Kafka bootstrap servers
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Consumer group ID
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        // Key deserializer (String)
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Value deserializer (JSON with error handling)
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, CustomerEventDTO.class.getName());
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.dpilaloa.*");

        // Auto-commit configuration
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        config.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 1000);

        // Offset reset strategy (earliest = start from beginning if no offset found)
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Session timeout and heartbeat
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);

        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * Kafka Listener Container Factory
     * <p>
     * Configures concurrent message processing.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CustomerEventDTO> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CustomerEventDTO> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory());

        // Number of concurrent consumers (adjust based on topic partitions)
        factory.setConcurrency(3);

        // Auto-startup (set to false if you want manual control)
        factory.setAutoStartup(true);

        return factory;
    }
}
