import asyncio
import contextlib
import json
import logging
import os

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from .metrics import kafka_prediction_publish_total
from .models import AggregatedFeaturesRequest
from .predictor import predict_from_aggregate


logger = logging.getLogger(__name__)


class KafkaPredictionPipeline:
    def __init__(self) -> None:
        self.enabled = os.getenv("ENABLE_KAFKA_PIPELINE", "false").lower() == "true"
        self.bootstrap_servers = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
        self.input_topic = os.getenv("KAFKA_AGGREGATED_FEATURES_TOPIC", "aggregated_features")
        self.output_topic = os.getenv("KAFKA_ML_PREDICTIONS_TOPIC", "ml_predictions")
        self.group_id = os.getenv("KAFKA_GROUP_ID", "ml-service")
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        if not self.enabled:
            logger.info("Kafka prediction pipeline disabled")
            return

        self._task = asyncio.create_task(self._run())
        logger.info("Kafka prediction pipeline started")

    async def stop(self) -> None:
        if self._task is None:
            return

        self._task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self._task

    async def _run(self) -> None:
        consumer = AIOKafkaConsumer(
            self.input_topic,
            bootstrap_servers=self.bootstrap_servers,
            group_id=self.group_id,
            value_deserializer=lambda value: json.loads(value.decode("utf-8")),
        )
        producer = AIOKafkaProducer(
            bootstrap_servers=self.bootstrap_servers,
            value_serializer=lambda value: json.dumps(value).encode("utf-8"),
        )

        await consumer.start()
        await producer.start()
        try:
            async for message in consumer:
                payload = AggregatedFeaturesRequest(**message.value)
                prediction = predict_from_aggregate(payload)
                try:
                    await producer.send_and_wait(self.output_topic, prediction.model_dump())
                    kafka_prediction_publish_total.labels(result="success").inc()
                except Exception:
                    kafka_prediction_publish_total.labels(result="failure").inc()
                    raise
        finally:
            await consumer.stop()
            await producer.stop()
