# Contracts

Phase 1 locks the platform around contract-first event definitions so each service can be implemented independently.

## Kafka Topics

- `traffic_metrics`: emitted by gateway, consumed by feature-service
- `aggregated_features`: emitted by feature-service, consumed by ml-service and decision-engine
- `ml_predictions`: emitted by ml-service, consumed by decision-engine
- `traffic_decisions`: emitted by decision-engine, consumed by gateway

## Schema Source

JSON schemas live in `contracts/schemas`.

Files:

- `traffic-metrics.schema.json`
- `aggregated-features.schema.json`
- `ml-prediction.schema.json`
- `traffic-decision.schema.json`

## Compatibility Rule

- Additive fields are allowed only with schema version review.
- Breaking changes require a versioned topic or versioned schema file.
- Producers and consumers should reject payloads that violate required fields.
