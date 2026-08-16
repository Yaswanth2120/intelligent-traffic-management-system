package com.traffic.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Thin wrapper around raw Kafka clients used to drive/observe the pipeline from
 * outside the JVM-hosted services, exactly like the real gateway/decision-engine
 * would over the wire (plain JSON on the topics, matching docs/architecture.md's
 * contracts).
 */
public final class KafkaTestSupport implements AutoCloseable {

    private final String bootstrapServers;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KafkaProducer<String, String> producer;

    public KafkaTestSupport(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        this.producer = new KafkaProducer<>(producerProps);
    }

    public void publish(String topic, String key, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            producer.send(new ProducerRecord<>(topic, key, json)).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish to " + topic, e);
        }
    }

    public void publishRaw(String topic, String key, String rawValue) {
        try {
            producer.send(new ProducerRecord<>(topic, key, rawValue)).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish raw payload to " + topic, e);
        }
    }

    /**
     * Polls `topic` from the earliest offset (a fresh, uniquely-named consumer
     * group per call so tests never interfere with each other or with the
     * platform services' own consumer groups) until a record whose parsed JSON
     * satisfies `predicate` is found, or `timeout` elapses.
     */
    public JsonNode awaitMatch(String topic, Predicate<JsonNode> predicate, Duration timeout) {
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    JsonNode node = parseOrNull(record.value());
                    if (node != null && predicate.test(node)) {
                        return node;
                    }
                }
            }
            throw new AssertionError("No matching record found on topic '" + topic
                    + "' within " + timeout);
        }
    }

    /** Collects every parseable record seen on `topic` within `timeout`, in arrival order. */
    public List<JsonNode> collectFor(String topic, Duration timeout) {
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        List<JsonNode> collected = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    JsonNode node = parseOrNull(record.value());
                    if (node != null) {
                        collected.add(node);
                    }
                }
            }
        }
        return collected;
    }

    private JsonNode parseOrNull(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
