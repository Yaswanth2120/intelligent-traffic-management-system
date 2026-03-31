from pydantic import BaseModel, Field


class PredictionRequest(BaseModel):
    route: str = Field(..., min_length=1)
    time_of_day: int = Field(..., ge=0, le=23)
    day_of_week: int = Field(..., ge=0, le=6)
    recent_rps: float = Field(..., ge=0)
    error_rate: float = Field(..., ge=0, le=1)


class AggregatedFeaturesRequest(BaseModel):
    route: str = Field(..., min_length=1)
    window_start: int = Field(..., ge=0)
    window_size_sec: int = Field(..., ge=1)
    requests_per_sec: float = Field(..., ge=0)
    error_rate: float = Field(..., ge=0, le=1)
    avg_latency_ms: float = Field(..., ge=0)
    p95_latency_ms: float = Field(..., ge=0)
    unique_clients: int = Field(..., ge=0)


class PredictionResponse(BaseModel):
    route: str
    model_version: str
    predicted_rps: float
    spike_probability: float
    risk_level: str
    prediction_horizon_sec: int
