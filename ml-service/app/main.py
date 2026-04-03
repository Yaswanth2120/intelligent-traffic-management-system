from contextlib import asynccontextmanager

from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator

from .kafka_pipeline import KafkaPredictionPipeline
from .metrics import prediction_latency_seconds, prediction_requests_total
from .models import AggregatedFeaturesRequest, PredictionRequest, PredictionResponse
from .predictor import predict_from_aggregate, predict_from_legacy


pipeline = KafkaPredictionPipeline()


@asynccontextmanager
async def lifespan(_: FastAPI):
    await pipeline.start()
    try:
        yield
    finally:
        await pipeline.stop()


app = FastAPI(title="traffic-ml-service", version="0.2.0", lifespan=lifespan)
Instrumentator().instrument(app).expose(app)


@app.get("/health")
def health() -> dict[str, str | bool]:
    return {"status": "ok", "kafka_pipeline_enabled": pipeline.enabled}


@app.post("/predict", response_model=PredictionResponse)
def predict(request: PredictionRequest) -> PredictionResponse:
    prediction_requests_total.labels(route=request.route, source="legacy").inc()
    with prediction_latency_seconds.labels(source="legacy").time():
        return predict_from_legacy(request)


@app.post("/predict/aggregate", response_model=PredictionResponse)
def predict_aggregate(request: AggregatedFeaturesRequest) -> PredictionResponse:
    prediction_requests_total.labels(route=request.route, source="aggregate").inc()
    with prediction_latency_seconds.labels(source="aggregate").time():
        return predict_from_aggregate(request)
