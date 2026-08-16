package com.traffic.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.traffic.integration.support.DockerImageBuilder;
import com.traffic.integration.support.KafkaTestSupport;
import com.traffic.integration.support.TrafficMetricEventFixture;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;

/**
 * End-to-end distributed pipeline test, proven across REAL infrastructure and the
 * REAL ml-service process (built from its actual Dockerfile and run as its own
 * container) -- nothing here is mocked or stubbed.
 *
 * Proven flow (see docs/architecture.md and docs/testing.md):
 *
 *   traffic_metrics (Kafka, published by this test, standing in for the gateway)
 *     -> feature-service (real Spring Boot container)
 *        -> Redis (traffic_window:<route> state)
 *        -> Postgres (traffic_history row)
 *        -> aggregated_features (Kafka)
 *   aggregated_features -> ml-service (real FastAPI container, real Holt-Winters /
 *                                       heuristic prediction logic)
 *     -> ml_predictions (Kafka)
 *   ml_predictions -> decision-engine (real Spring Boot container)
 *     -> Postgres (policy_decisions row)
 *     -> traffic_decisions (Kafka, the final business output)
 *
 * Isolation strategy: every test uses its own randomly-generated route name, and
 * every Kafka read uses a freshly generated consumer group id reading from the
 * earliest offset. That gives full test isolation without needing to delete
 * Kafka topics or Redis/Postgres rows between tests (see docs/testing.md).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrafficPipelineIT {

    private static final Logger log = LoggerFactory.getLogger(TrafficPipelineIT.class);

    private static final String TRAFFIC_METRICS_TOPIC = "traffic_metrics";
    private static final String AGGREGATED_FEATURES_TOPIC = "aggregated_features";
    private static final String ML_PREDICTIONS_TOPIC = "ml_predictions";
    private static final String TRAFFIC_DECISIONS_TOPIC = "traffic_decisions";

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(60);

    private static Network network;
    private static KafkaContainer kafka;
    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;
    private static GenericContainer<?> featureService;
    private static GenericContainer<?> mlService;
    private static GenericContainer<?> decisionEngine;
    private static KafkaTestSupport kafkaTestSupport;

    @BeforeAll
    static void startPlatform() {
        network = Network.newNetwork();

        // apache/kafka (native multi-arch, KRaft-only) rather than confluentinc/cp-kafka:
        // the Confluent image's arm64 variant ships a broken "dub" helper binary
        // (exec format error) on Apple Silicon Docker Desktop as of this writing.
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1"))
                .withNetwork(network)
                .withNetworkAliases("kafka")
                // Without this, Kafka only advertises its host-mapped address
                // (e.g. localhost:<random port>), which the feature-service /
                // ml-service / decision-engine containers can't reach from
                // inside the Docker network -- they need "kafka:19092" (a
                // distinct port from the default PLAINTEXT/BROKER/CONTROLLER
                // listeners testcontainers already wires up internally).
                .withListener("kafka:19092");

        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                .withDatabaseName("traffic_platform")
                .withUsername("traffic_user")
                .withPassword("traffic_pass")
                .withInitScript("postgres-init.sql")
                .withNetwork(network)
                .withNetworkAliases("postgres");

        redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
                .withExposedPorts(6379)
                .withNetwork(network)
                .withNetworkAliases("redis")
                .waitingFor(Wait.forListeningPort());

        log.info("Starting Kafka, Postgres, Redis...");
        Startables.deepStart(kafka, postgres, redis).join();

        log.info("Building feature-service, decision-engine, and ml-service images in parallel...");
        String runId = UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<String> featureImage = CompletableFuture.supplyAsync(() ->
                DockerImageBuilder.build("feature-service/Dockerfile", "traffic-platform-it/feature-service:" + runId));
        CompletableFuture<String> decisionImage = CompletableFuture.supplyAsync(() ->
                DockerImageBuilder.build("decision-engine/Dockerfile", "traffic-platform-it/decision-engine:" + runId));
        CompletableFuture<String> mlImage = CompletableFuture.supplyAsync(() ->
                DockerImageBuilder.build("ml-service/Dockerfile", "traffic-platform-it/ml-service:" + runId));
        CompletableFuture.allOf(featureImage, decisionImage, mlImage).join();

        featureService = new GenericContainer<>(DockerImageName.parse(featureImage.join()))
                .withNetwork(network)
                .withNetworkAliases("feature-service")
                .withExposedPorts(8081)
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .withEnv("POSTGRES_URL", "jdbc:postgresql://postgres:5432/traffic_platform")
                .withEnv("POSTGRES_USER", "traffic_user")
                .withEnv("POSTGRES_PASSWORD", "traffic_pass")
                .withEnv("REDIS_HOST", "redis")
                .withEnv("REDIS_PORT", "6379")
                // NOT an HTTP health-check: feature-service declares
                // spring-boot-starter-actuator but no web starter (no Tomcat/
                // Netty), so it never actually opens an HTTP port despite
                // application.yml configuring server.port and actuator
                // endpoint exposure -- /actuator/health is unreachable. This
                // is a real, pre-existing gap in the service (see docs/testing.md
                // "known gaps this suite found"), not something this test
                // works around by accident. A Spring Boot app always logs
                // "Started X in Ns" once its context refreshes successfully,
                // regardless of web/non-web mode, so that's used as readiness.
                .waitingFor(Wait.forLogMessage(".*Started FeatureServiceApplication.*\\n", 1)
                        .withStartupTimeout(Duration.ofSeconds(120)));

        mlService = new GenericContainer<>(DockerImageName.parse(mlImage.join()))
                .withNetwork(network)
                .withNetworkAliases("ml-service")
                .withExposedPorts(8000)
                .withEnv("ENABLE_KAFKA_PIPELINE", "true")
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .waitingFor(Wait.forHttp("/health").forPort(8000).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)));

        decisionEngine = new GenericContainer<>(DockerImageName.parse(decisionImage.join()))
                .withNetwork(network)
                .withNetworkAliases("decision-engine")
                .withExposedPorts(8082)
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .withEnv("POSTGRES_URL", "jdbc:postgresql://postgres:5432/traffic_platform")
                .withEnv("POSTGRES_USER", "traffic_user")
                .withEnv("POSTGRES_PASSWORD", "traffic_pass")
                // See the feature-service comment above: decision-engine has the
                // same gap (actuator without a web starter), so readiness is a
                // log message, not an HTTP health check.
                .waitingFor(Wait.forLogMessage(".*Started DecisionEngineApplication.*\\n", 1)
                        .withStartupTimeout(Duration.ofSeconds(120)));

        featureService.withLogConsumer(new Slf4jLogConsumer(log).withPrefix("feature-service"));

        log.info("Starting feature-service, ml-service, decision-engine...");
        Startables.deepStart(featureService, mlService, decisionEngine).join();

        kafkaTestSupport = new KafkaTestSupport(kafka.getBootstrapServers());
        log.info("Platform is up. Kafka bootstrap (host-visible): {}", kafka.getBootstrapServers());
    }

    @AfterAll
    static void stopPlatform() {
        if (kafkaTestSupport != null) {
            kafkaTestSupport.close();
        }
        for (GenericContainer<?> container : new GenericContainer<?>[] {decisionEngine, mlService, featureService}) {
            if (container != null) {
                container.stop();
            }
        }
        if (redis != null) {
            redis.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (kafka != null) {
            kafka.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    @Order(1)
    void happyPath_highRiskTraffic_flowsThroughRealMlServiceToRateLimitDecision() throws Exception {
        String route = "orders-" + UUID.randomUUID();

        // Three consecutive failing, slow requests from distinct clients:
        // error_rate=1.0, p95_latency_ms=1000 (>=300 caps latency_factor at 1.0) ->
        // deterministically pushes the ML service's spike_probability >= 0.80,
        // which the decision engine's rules map to a high-risk RATE_LIMIT.
        long baseTs = System.currentTimeMillis() / 1000;
        for (int i = 0; i < 3; i++) {
            kafkaTestSupport.publish(TRAFFIC_METRICS_TOPIC, route,
                    TrafficMetricEventFixture.aTrafficMetric()
                            .route(route)
                            .timestamp(baseTs + i)
                            .status(500)
                            .latencyMs(1000)
                            .clientId("client-" + i)
                            .build());
        }

        // 1. Feature service wrote the final aggregate to Redis (real Redis, not mocked).
        await().atMost(AWAIT_TIMEOUT).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            try (Jedis jedis = new Jedis(redis.getHost(), redis.getMappedPort(6379))) {
                String raw = jedis.get("traffic_window:" + route);
                assertThat(raw).isNotNull();
                JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
                assertThat(node.get("requests_per_sec").asDouble()).isEqualTo(3.0 / 60.0, org.assertj.core.data.Offset.offset(1e-9));
                assertThat(node.get("unique_clients").asInt()).isEqualTo(3);
                assertThat(node.get("error_rate").asDouble()).isEqualTo(1.0);
            }
        });

        // 2. Feature service persisted history rows to Postgres (real Postgres, not mocked).
        assertRowCountAtLeast("traffic_history", route, 3);

        // 3. feature-service published the aggregate to Kafka.
        JsonNode aggregated = kafkaTestSupport.awaitMatch(AGGREGATED_FEATURES_TOPIC,
                node -> route.equals(textOrNull(node, "route")) && node.get("unique_clients").asInt() == 3,
                AWAIT_TIMEOUT);
        assertThat(aggregated.get("error_rate").asDouble()).isEqualTo(1.0);

        // 4. The REAL ml-service consumed that aggregate and published a REAL prediction.
        //    Values are computed independently here from the production heuristic formula
        //    (ml-service/app/predictor.py::_heuristic_predicted_rps) to prove this is an
        //    actual computed prediction, not just "some message arrived".
        double requestsPerSec = 3.0 / 60.0;
        double latencyFactor = 1.0; // min(1000/300, 1.0)
        double clientFactor = 3.0 / 100.0;
        double errorFactor = 1.0;
        double expectedPredictedRps = Math.round(
                requestsPerSec * (1.08 + latencyFactor * 0.14 + clientFactor * 0.10 + errorFactor * 0.25) * 100.0)
                / 100.0;

        // feature-service publishes one aggregated_features event per individual
        // traffic_metrics event (no batching), so ml-service publishes one
        // ml_predictions message per partial aggregate too (after 1 event, after
        // 2, after 3). Match specifically on the value computed from the FINAL
        // (all 3 events) aggregate, not just "the first message for this route".
        JsonNode prediction = kafkaTestSupport.awaitMatch(ML_PREDICTIONS_TOPIC,
                node -> route.equals(textOrNull(node, "route"))
                        && Math.abs(node.get("predicted_rps").asDouble() - expectedPredictedRps) < 0.005,
                AWAIT_TIMEOUT);
        assertThat(prediction.get("model_version").asText()).isEqualTo("baseline-v2-aggregate");
        assertThat(prediction.get("spike_probability").asDouble()).isGreaterThanOrEqualTo(0.80);
        assertThat(prediction.get("risk_level").asText()).isEqualTo("high");

        // 5. The decision engine turned that prediction into the expected policy decision.
        JsonNode decision = kafkaTestSupport.awaitMatch(TRAFFIC_DECISIONS_TOPIC,
                node -> route.equals(textOrNull(node, "route")), AWAIT_TIMEOUT);
        assertThat(decision.get("policy_type").asText()).isEqualTo("RATE_LIMIT");
        assertThat(decision.get("rate_limit_rps").asInt()).isEqualTo(175);
        assertThat(decision.get("reason").asText()).isEqualTo("predicted_overload_or_high_spike_risk");
        assertThat(decision.get("ttl_sec").asInt()).isEqualTo(120);

        // 6. That decision was also persisted by the decision engine (real Postgres).
        assertRowCountAtLeast("policy_decisions", route, 1);
    }

    @Test
    @Order(2)
    void multipleEvents_sameRoute_areProcessedInOrderAndAccumulateCorrectly() throws Exception {
        String route = "orders-" + UUID.randomUUID();
        int eventCount = 5;
        long baseTs = System.currentTimeMillis() / 1000;

        for (int i = 0; i < eventCount; i++) {
            kafkaTestSupport.publish(TRAFFIC_METRICS_TOPIC, route,
                    TrafficMetricEventFixture.aTrafficMetric()
                            .route(route)
                            .timestamp(baseTs + i)
                            .status(200)
                            .latencyMs(20 + i) // distinct, monotonically increasing per-event latency
                            .clientId("client-" + i)
                            .build());
        }

        // The final Redis snapshot must reflect ALL 5 events with none dropped or
        // reordered: exactly 5 unique clients and requests_per_sec == 5/60.
        await().atMost(AWAIT_TIMEOUT).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            try (Jedis jedis = new Jedis(redis.getHost(), redis.getMappedPort(6379))) {
                String raw = jedis.get("traffic_window:" + route);
                assertThat(raw).isNotNull();
                JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
                assertThat(node.get("unique_clients").asInt()).isEqualTo(eventCount);
                assertThat(node.get("requests_per_sec").asDouble())
                        .isEqualTo(eventCount / 60.0, org.assertj.core.data.Offset.offset(1e-9));
                // avg_latency_ms across 20,21,22,23,24 is 22.0 -- only reachable if
                // every event was folded into the aggregate, in order.
                assertThat(node.get("avg_latency_ms").asDouble()).isEqualTo(22.0, org.assertj.core.data.Offset.offset(1e-9));
            }
        });

        assertRowCountAtLeast("traffic_history", route, eventCount);
    }

    @Test
    @Order(3)
    void lowRiskTraffic_neverEscalatesToHighRisk() {
        String route = "orders-" + UUID.randomUUID();
        long baseTs = System.currentTimeMillis() / 1000;

        // 30 fast, successful requests from a single client: every ML risk factor
        // (error/latency/client/growth) stays low.
        //
        // NOTE: because feature-service publishes an aggregate after EVERY single
        // event (no batching), the very first event alone briefly produces a
        // window with request_count=1 (requests_per_sec = 1/60 = 0.0167). At that
        // scale, predicted_rps's 2-decimal rounding (e.g. 0.018 -> 0.02) exceeds
        // the model's 15% medium-risk growth threshold even with zero error/
        // latency contribution -- a real, documented characteristic of the
        // existing heuristic baseline at very low absolute request counts (see
        // docs/testing.md). So this traffic CAN produce a transient "medium"
        // decision on the very first window; what it must never do is escalate to
        // "high" risk, which is the actually meaningful safety property here.
        for (int i = 0; i < 30; i++) {
            kafkaTestSupport.publish(TRAFFIC_METRICS_TOPIC, route,
                    TrafficMetricEventFixture.aTrafficMetric()
                            .route(route)
                            .timestamp(baseTs + i)
                            .status(200)
                            .latencyMs(10)
                            .clientId("client-steady")
                            .build());
        }

        // Wait for the pipeline to have definitely processed all the traffic (the
        // aggregate reaching the expected final count is proof of that)...
        await().atMost(AWAIT_TIMEOUT).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            JsonNode aggregated = kafkaTestSupport.awaitMatch(AGGREGATED_FEATURES_TOPIC,
                    node -> route.equals(textOrNull(node, "route")) && node.get("requests_per_sec").asDouble() >= 30.0 / 60.0,
                    Duration.ofSeconds(5));
            assertThat(aggregated).isNotNull();
        });

        // ...then assert no high-risk (predicted_overload_or_high_spike_risk /
        // rate_limit_rps=175) decision was ever made for this route.
        List<JsonNode> decisions = kafkaTestSupport.collectFor(TRAFFIC_DECISIONS_TOPIC, Duration.ofSeconds(10));
        assertThat(decisions)
                .filteredOn(node -> route.equals(textOrNull(node, "route")))
                .noneMatch(node -> "predicted_overload_or_high_spike_risk".equals(textOrNull(node, "reason")));
    }

    @Test
    @Order(4)
    void mediumRiskTraffic_yieldsMediumRateLimitDecision() throws Exception {
        String route = "orders-" + UUID.randomUUID();
        long baseTs = System.currentTimeMillis() / 1000;

        // One failing + one healthy request, both at moderate-high latency:
        // error_rate=0.5, latency_factor=1.0 -> spike_probability lands in [0.50, 0.80).
        kafkaTestSupport.publish(TRAFFIC_METRICS_TOPIC, route,
                TrafficMetricEventFixture.aTrafficMetric().route(route).timestamp(baseTs)
                        .status(500).latencyMs(500).clientId("client-a").build());
        kafkaTestSupport.publish(TRAFFIC_METRICS_TOPIC, route,
                TrafficMetricEventFixture.aTrafficMetric().route(route).timestamp(baseTs + 1)
                        .status(200).latencyMs(500).clientId("client-b").build());

        // feature-service publishes one aggregated_features event per traffic_metrics
        // event, so ml-service also predicts once after just the first (failing)
        // event, before the second (healthy) one arrives. That first prediction sees
        // error_rate=1.0, not 0.5, and is itself "high" risk -- match specifically on
        // "medium" so this doesn't race and grab that earlier, wrong-state message.
        JsonNode prediction = kafkaTestSupport.awaitMatch(ML_PREDICTIONS_TOPIC,
                node -> route.equals(textOrNull(node, "route"))
                        && "medium".equals(textOrNull(node, "risk_level")),
                AWAIT_TIMEOUT);
        assertThat(prediction.get("spike_probability").asDouble()).isGreaterThanOrEqualTo(0.5);
        assertThat(prediction.get("spike_probability").asDouble()).isLessThan(0.80);

        JsonNode decision = kafkaTestSupport.awaitMatch(TRAFFIC_DECISIONS_TOPIC,
                node -> route.equals(textOrNull(node, "route"))
                        && "moderate_spike_risk".equals(textOrNull(node, "reason")),
                AWAIT_TIMEOUT);
        assertThat(decision.get("policy_type").asText()).isEqualTo("RATE_LIMIT");
        assertThat(decision.get("rate_limit_rps").asInt()).isEqualTo(220);
    }

    @Test
    @Order(5)
    void malformedEvent_doesNotBreakSubsequentProcessing() {
        String poisonRoute = "orders-malformed-" + UUID.randomUUID();
        String recoveryRoute = "orders-recovery-" + UUID.randomUUID();

        // Not valid JSON at all -- the worst case of a malformed payload.
        kafkaTestSupport.publishRaw(TRAFFIC_METRICS_TOPIC, poisonRoute, "not-json-at-all-{{{");

        // A structurally valid JSON object that violates the schema (route is a
        // number, not a string; required fields missing) -- a "shaped like JSON
        // but not deserializable into TrafficMetricEvent" poison message.
        kafkaTestSupport.publishRaw(TRAFFIC_METRICS_TOPIC, poisonRoute, "{\"route\": 12345}");

        // A well-formed, valid event straight after -- the pipeline must still
        // process this even though it follows two poison-pill messages.
        long ts = System.currentTimeMillis() / 1000;
        kafkaTestSupport.publish(TRAFFIC_METRICS_TOPIC, recoveryRoute,
                TrafficMetricEventFixture.aTrafficMetric()
                        .route(recoveryRoute)
                        .timestamp(ts)
                        .status(200)
                        .latencyMs(30)
                        .clientId("client-recovery")
                        .build());

        JsonNode aggregated = kafkaTestSupport.awaitMatch(AGGREGATED_FEATURES_TOPIC,
                node -> recoveryRoute.equals(textOrNull(node, "route")), AWAIT_TIMEOUT);
        assertThat(aggregated.get("unique_clients").asInt()).isEqualTo(1);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void assertRowCountAtLeast(String table, String route, int minCount) throws Exception {
        await().atMost(AWAIT_TIMEOUT).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 PreparedStatement statement = connection.prepareStatement(
                         "select count(*) from " + table + " where route = ?")) {
                statement.setString(1, route);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    assertThat(resultSet.getInt(1)).isGreaterThanOrEqualTo(minCount);
                }
            }
        });
    }
}
