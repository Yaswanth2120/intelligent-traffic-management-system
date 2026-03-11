from fastapi import FastAPI
from pydantic import BaseModel, Field
from prometheus_fastapi_instrumentator import Instrumentator


class PredictionRequest(BaseModel):
    route: str = Field(..., min_length=1)
    time_of_day: int = Field(..., ge=0, le=23)
    day_of_week: int = Field(..., ge=0, le=6)
    recent_rps: float = Field(..., ge=0)
    error_rate: float = Field(..., ge=0, le=1)


class PredictionResponse(BaseModel):
    route: str
    model_version: str
    predicted_rps: float
    spike_probability: float
    risk_level: str
    prediction_horizon_sec: int


app = FastAPI(title="traffic-ml-service", version="0.1.0")
Instrumentator().instrument(app).expose(app)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/predict", response_model=PredictionResponse)
def predict(request: PredictionRequest) -> PredictionResponse:
    predicted_rps = round(request.recent_rps * 1.15, 2)
    spike_probability = min(0.99, round(request.error_rate + 0.25, 2))
    risk_level = "high" if spike_probability >= 0.8 else "medium" if spike_probability >= 0.5 else "low"
    return PredictionResponse(
        route=request.route,
        model_version="baseline-v1",
        predicted_rps=predicted_rps,
        spike_probability=spike_probability,
        risk_level=risk_level,
        prediction_horizon_sec=60,
    )
