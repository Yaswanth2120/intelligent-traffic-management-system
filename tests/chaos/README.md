# Chaos Drills

These drills exercise graceful degradation.

## Kafka Outage

- Expect gateway request path to keep serving traffic
- Expect metric publish failures to rise
- Expect policy refresh to stall but last active gateway policy to remain usable

## Redis Outage

- Expect feature-service Redis writes to fail
- PostgreSQL history writes should still succeed if database is available

## ML Service Outage

- Expect no new `ml_predictions`
- Decision engine should stop issuing fresh prediction-based policies
- Gateway should continue enforcing cached active decisions until TTL expiry
