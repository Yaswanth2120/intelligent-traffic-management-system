# Integration Validation

This folder documents the end-to-end validation flow for the traffic platform.
See [docs/testing.md](../../docs/testing.md) for the full picture (unit,
integration, and manual E2E tiers, commands, and what each proves).

## Covered Flow

1. Gateway emits `traffic_metrics`
2. Feature service consumes metrics and publishes `aggregated_features`
3. ML service converts aggregates into `ml_predictions`
4. Decision engine converts predictions into `traffic_decisions`
5. Gateway enforces active rate limits

## Validation Layers

- Java module unit tests: gateway-service, feature-service, decision-engine
  (`mvn -pl gateway-service,feature-service,decision-engine test`)
- ML service unit tests + forecast evaluation
  (`cd ml-service && make test && make evaluate-forecast`)
- **Testcontainers-backed distributed pipeline integration tests**: real
  Kafka, Redis, Postgres, and the real feature-service/decision-engine/
  ml-service Docker images, covering steps 1-4 above end to end (gateway
  itself, step 5, is exercised separately by the manual E2E script and its
  own unit tests). `mvn -f ../../integration-tests/pom.xml verify`. See
  `docs/testing.md` for the full list of what is and is not proven.
- Asset validation: Grafana dashboard JSON, Docker Compose YAML, Kubernetes YAML

## Future Expansion

- Cover the gateway leg (step 5) in the same Testcontainers suite
- Add a Kafka broker restart / rebalance resilience test
- Add an end-to-end replay harness for recorded event payloads
