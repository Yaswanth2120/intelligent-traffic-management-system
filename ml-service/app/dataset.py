"""Deterministic synthetic traffic dataset for offline model evaluation.

Generates an hourly request-rate series with the patterns real production
traffic exhibits: a baseline load, daily seasonality (day/night cycle),
weekday/weekend modulation, slow organic growth, occasional multi-hour
spikes, and random noise. Correlated `error_rate` / latency / client-count
fields are derived from load so the series can be fed straight into the
existing `AggregatedFeaturesRequest` shape used by the production baseline.

Fixed seed -> fully reproducible across runs and machines.
"""
from __future__ import annotations

import math
import random
from dataclasses import dataclass

SEASONAL_PERIOD_HOURS = 24
DEFAULT_SEED = 42


@dataclass
class TrafficPoint:
    hour_index: int
    route: str
    requests_per_sec: float
    error_rate: float
    avg_latency_ms: float
    p95_latency_ms: float
    unique_clients: int


def generate_synthetic_series(
    days: int = 60,
    route: str = "/api/orders",
    seed: int = DEFAULT_SEED,
) -> list[TrafficPoint]:
    """Generate `days * 24` hourly points, oldest first (chronological order)."""
    if days < 1:
        raise ValueError("days must be >= 1")

    rng = random.Random(seed)
    n_hours = days * SEASONAL_PERIOD_HOURS

    base_level = 200.0
    daily_amplitude = 120.0
    weekend_factor = 0.6
    growth_per_hour = 0.06  # gradual upward trend over the whole window

    # Pre-pick a handful of spike windows (start hour, duration, magnitude).
    n_spikes = max(1, n_hours // 180)
    spikes = []
    for _ in range(n_spikes):
        start = rng.randint(0, n_hours - 1)
        duration = rng.randint(1, 3)
        magnitude = rng.uniform(1.8, 3.2)
        spikes.append((start, duration, magnitude))

    points: list[TrafficPoint] = []
    for hour in range(n_hours):
        hour_of_day = hour % 24
        day_index = hour // 24
        day_of_week = day_index % 7

        # Daily seasonality: trough overnight, peak mid-afternoon.
        seasonal = daily_amplitude * math.sin(
            2 * math.pi * (hour_of_day - 6) / 24
        )
        seasonal = max(seasonal, -daily_amplitude * 0.85)

        weekday_multiplier = weekend_factor if day_of_week in (5, 6) else 1.0

        trend = growth_per_hour * hour

        value = (base_level + seasonal + trend) * weekday_multiplier

        spike_multiplier = 1.0
        for start, duration, magnitude in spikes:
            if start <= hour < start + duration:
                spike_multiplier = magnitude
                break
        value *= spike_multiplier

        noise = rng.gauss(0, base_level * 0.04)
        value = max(1.0, value + noise)

        # Derived, load-correlated operational features.
        load_ratio = value / base_level
        error_rate = min(0.95, max(0.0, rng.gauss(0.01 * load_ratio, 0.004)))
        avg_latency_ms = max(20.0, 60.0 * load_ratio + rng.gauss(0, 8))
        p95_latency_ms = max(avg_latency_ms, avg_latency_ms * 1.8 + rng.gauss(0, 15))
        unique_clients = max(1, int(value / 3.0 + rng.gauss(0, 4)))

        points.append(
            TrafficPoint(
                hour_index=hour,
                route=route,
                requests_per_sec=round(value, 3),
                error_rate=round(error_rate, 4),
                avg_latency_ms=round(avg_latency_ms, 2),
                p95_latency_ms=round(p95_latency_ms, 2),
                unique_clients=unique_clients,
            )
        )

    return points


def chronological_split(
    points: list[TrafficPoint], test_fraction: float = 0.2
) -> tuple[list[TrafficPoint], list[TrafficPoint]]:
    """Split a chronologically-ordered series into train/test without shuffling."""
    if not 0 < test_fraction < 1:
        raise ValueError("test_fraction must be between 0 and 1")
    split_index = int(len(points) * (1 - test_fraction))
    split_index = max(split_index, 1)
    return points[:split_index], points[split_index:]
