# Model Lifecycle

## Current Model

- Active model: `baseline-v2-aggregate`
- Rollback model: `baseline-v1`
- Registry file: `ml-service/model-registry.json`

## Promotion Rules

- Candidate model must define a version, status, prediction horizon, accuracy threshold, and MAPE threshold.
- Candidate must improve or preserve operational safety before promotion.
- Promotion should update `active_model` and set the previous model as `rollback_model`.

## Rollback Rules

- Roll back when prediction error exceeds the accepted threshold during validation.
- Roll back when the decision engine starts issuing unsafe or noisy policies.
- Roll back by switching `active_model` to the registry `rollback_model`.

## Future Production Flow

1. Train candidate from `traffic_history`.
2. Validate against recent holdout windows.
3. Compare against the active model.
4. Promote if thresholds pass.
5. Deploy with version tag.
6. Track prediction quality and policy effects.
