import json
from pathlib import Path


REGISTRY_PATH = Path(__file__).resolve().parents[1] / "model-registry.json"


def main() -> None:
    registry = json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))
    versions = {model["version"] for model in registry["models"]}

    if registry["active_model"] not in versions:
        raise SystemExit("active_model is not present in models")

    if registry["rollback_model"] not in versions:
        raise SystemExit("rollback_model is not present in models")

    for model in registry["models"]:
        required = {
            "version",
            "status",
            "prediction_horizon_sec",
            "min_validation_accuracy",
            "max_mape",
            "notes",
        }
        missing = required.difference(model)
        if missing:
            raise SystemExit(f"model {model.get('version', '<unknown>')} missing fields: {sorted(missing)}")

        if model["prediction_horizon_sec"] <= 0:
            raise SystemExit(f"model {model['version']} has invalid prediction horizon")

        if not 0 <= model["min_validation_accuracy"] <= 1:
            raise SystemExit(f"model {model['version']} has invalid accuracy threshold")

        if not 0 <= model["max_mape"] <= 1:
            raise SystemExit(f"model {model['version']} has invalid max_mape")

    print("model registry valid")


if __name__ == "__main__":
    main()
