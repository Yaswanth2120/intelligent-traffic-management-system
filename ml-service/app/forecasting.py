"""Holt-Winters (triple exponential smoothing) forecaster.

Pure-Python implementation with no additional runtime dependencies (numpy/
statsmodels aren't in ml-service's dependency set, and this method doesn't
need them). See docs/forecasting.md for why this method was chosen.
"""
from __future__ import annotations

from dataclasses import dataclass, field


class InsufficientHistoryError(ValueError):
    """Raised when there isn't enough history to fit or forecast."""


@dataclass
class HoltWintersForecaster:
    """Additive Holt-Winters exponential smoothing with a fixed seasonal period.

    alpha/beta/gamma are the level/trend/seasonal smoothing factors. Defaults
    were hand-tuned against the synthetic dataset in app/dataset.py (see
    docs/forecasting.md) and are deliberately conservative (slow-moving) so a
    single noisy observation can't whipsaw the forecast.
    """

    seasonal_periods: int = 24
    alpha: float = 0.3
    beta: float = 0.1
    gamma: float = 0.2

    _level: float = field(default=0.0, init=False, repr=False)
    _trend: float = field(default=0.0, init=False, repr=False)
    _seasonals: list[float] = field(default_factory=list, init=False, repr=False)
    _fitted: bool = field(default=False, init=False, repr=False)
    _last_index: int = field(default=0, init=False, repr=False)

    @property
    def min_history_length(self) -> int:
        return 2 * self.seasonal_periods

    def fit(self, history: list[float]) -> "HoltWintersForecaster":
        if self.seasonal_periods < 2:
            raise ValueError("seasonal_periods must be >= 2")
        if len(history) < self.min_history_length:
            raise InsufficientHistoryError(
                f"need at least {self.min_history_length} observations "
                f"(2 full seasons), got {len(history)}"
            )

        period = self.seasonal_periods
        seasonals = _initial_seasonal_components(history, period)
        level = sum(history[:period]) / period
        trend = _initial_trend(history, period)

        for i, value in enumerate(history):
            seasonal = seasonals[i % period]
            last_level = level
            level = self.alpha * (value - seasonal) + (1 - self.alpha) * (level + trend)
            trend = self.beta * (level - last_level) + (1 - self.beta) * trend
            seasonals[i % period] = self.gamma * (value - level) + (1 - self.gamma) * seasonal

        self._level = level
        self._trend = trend
        self._seasonals = seasonals
        self._last_index = len(history) - 1
        self._fitted = True
        return self

    def forecast(self, steps: int = 1) -> list[float]:
        if not self._fitted:
            raise InsufficientHistoryError("forecaster has not been fit yet")
        if steps < 1:
            raise ValueError("steps must be >= 1")

        period = self.seasonal_periods
        return [
            max(0.0, self._level + m * self._trend + self._seasonals[(self._last_index + m) % period])
            for m in range(1, steps + 1)
        ]


def _initial_trend(series: list[float], period: int) -> float:
    total = 0.0
    for i in range(period):
        total += (series[i + period] - series[i]) / period
    return total / period


def _initial_seasonal_components(series: list[float], period: int) -> list[float]:
    n_seasons = len(series) // period
    season_averages = [
        sum(series[period * j : period * j + period]) / period for j in range(n_seasons)
    ]
    seasonals = [0.0] * period
    for i in range(period):
        seasonals[i] = sum(
            series[period * j + i] - season_averages[j] for j in range(n_seasons)
        ) / n_seasons
    return seasonals


def mae(actual: list[float], predicted: list[float]) -> float:
    _check_same_length(actual, predicted)
    return sum(abs(a - p) for a, p in zip(actual, predicted)) / len(actual)


def rmse(actual: list[float], predicted: list[float]) -> float:
    _check_same_length(actual, predicted)
    return (sum((a - p) ** 2 for a, p in zip(actual, predicted)) / len(actual)) ** 0.5


def mape(actual: list[float], predicted: list[float], epsilon: float = 1.0) -> float:
    """Mean absolute percentage error, safe against near-zero actuals.

    `epsilon` is a floor added to the denominator (in the series' own units,
    e.g. RPS) so that near-zero-traffic windows don't blow up the metric.
    """
    _check_same_length(actual, predicted)
    return 100.0 * sum(
        abs(a - p) / max(abs(a), epsilon) for a, p in zip(actual, predicted)
    ) / len(actual)


def _check_same_length(actual: list[float], predicted: list[float]) -> None:
    if len(actual) != len(predicted):
        raise ValueError("actual and predicted must be the same length")
    if len(actual) == 0:
        raise ValueError("actual/predicted must not be empty")
