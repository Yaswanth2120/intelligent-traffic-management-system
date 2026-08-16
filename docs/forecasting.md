# Traffic Forecasting

## Where prediction fits in the system

Per `docs/architecture.md`: the Feature Service publishes per-route aggregate
windows (`requests_per_sec`, `error_rate`, `avg_latency_ms`, `p95_latency_ms`,
`unique_clients`) to Kafka topic `aggregated_features`. `ml-service` consumes
those (via `KafkaPredictionPipeline`, or synchronously through
`POST /predict` / `POST /predict/aggregate`) and returns a `PredictionResponse`
(`predicted_rps`, `spike_probability`, `risk_level`) that is published to
`ml_predictions`. The Decision Engine (`decision-engine`, Java) consumes both
topics and turns `predicted_rps` / `spike_probability` into rate-limit,
routing, and load-shedding policy on `traffic_decisions`. **The
`PredictionResponse` schema is unchanged by this work** — the Decision
Engine's `MlPredictionEvent` record needed no changes.

## The previous algorithm

`predict_from_aggregate` (`ml-service/app/predictor.py`) was a **stateless
heuristic**, not a forecast: it took the single most-recent aggregate window
and multiplied `requests_per_sec` by a fixed factor built from three
normalized inputs (p95 latency, unique clients, error rate):

```
predicted_rps = requests_per_sec * (1.08 + 0.14*latency_factor + 0.10*client_factor + 0.25*error_factor)
```

It never looked at more than one data point, so it had no concept of
time-of-day, day-of-week, trend, or history — it only reacted to how
"stressed" the *current* window looked. It's kept in the codebase (see
below) both as the fallback path and as the benchmark in the evaluation
below.

## The new algorithm: Holt-Winters (additive, triple exponential smoothing)

`ml-service/app/forecasting.py` implements Holt-Winters from scratch in pure
Python (no new runtime dependency — `ml-service` currently has none of
numpy/pandas/statsmodels, and Holt-Winters doesn't need them to be fast
enough at this data volume).

**Why Holt-Winters over ARIMA/SARIMA or an LSTM:**
- The traffic pattern this system targets (per `docs/architecture.md`'s own
  capacity assumptions and the synthetic dataset below) is dominated by a
  strong, regular daily cycle plus slow trend — exactly what Holt-Winters'
  three components (level, trend, season) are built for.
- It's O(n) to fit, has three tunable smoothing constants, and needs no
  hyperparameter search, differencing/stationarity analysis, or matrix
  library — it fits the "lightweight, integrates with the existing stack"
  requirement much better than SARIMA (needs (p,d,q)(P,D,Q,m) order
  selection) or an LSTM (needs a training framework, GPU/CPU budget, and far
  more data than one service's traffic history to avoid overfitting — not
  justified here).
- It naturally supports an incremental/online update pattern: refit on an
  appended history buffer, which is how it's wired into the live service.

**How it's wired into the live service** (`ml-service/app/predictor.py`):
each route keeps an in-memory bounded history buffer (`deque`, capacity 7
days of hourly points = 168, matching the SLO doc's 7-day staleness bound for
the seasonal fit). Every `predict_from_aggregate` call appends the request's
`requests_per_sec` to that route's buffer. Once a route has at least two full
seasons of history (48 points), the Holt-Winters model is fit on the buffer
and used for `predicted_rps` (`model_version = "holt-winters-v1"`). Before
that point — a new route, or one that hasn't accumulated enough history yet
— the service **falls back** to the original heuristic
(`model_version = "baseline-v2-aggregate"`) so `/predict` and
`/predict/aggregate` never fail or block waiting for history. This fallback
is exercised in `test_predictor.py::test_new_route_falls_back_to_heuristic_baseline`.

`spike_probability` / `risk_level` keep the same shape as before (operational
factors from latency/clients/errors, plus how far the forecast projects
above the current value) so the Decision Engine's thresholds
(`docs/architecture.md`'s "Decision Rules") continue to apply unchanged.

## Dataset construction

`ml-service/app/dataset.py::generate_synthetic_series` produces a fully
deterministic (fixed seed, default `42`) hourly series combining:

- a baseline load level,
- **daily seasonality** (sinusoidal, trough overnight / peak mid-afternoon),
- **weekday/weekend modulation** (lower multiplier on Sat/Sun),
- **gradual growth** (small linear trend across the whole window),
- a handful of **multi-hour spikes** at random (seeded) positions and
  magnitudes,
- **Gaussian noise**.

It also derives correlated `error_rate`, `avg_latency_ms`, `p95_latency_ms`,
and `unique_clients` from the load level (with their own noise), so the
series can be replayed through the *existing* `AggregatedFeaturesRequest`
shape and, therefore, through the existing heuristic baseline — the baseline
comparison isn't reimplemented from a formula guess, it's the production
code path.

Default: 60 days of hourly points (1,440 points).

## Train/test methodology

`chronological_split` (`app/dataset.py`) splits the series **without
shuffling**: the first 80% (oldest) is train, the last 20% (newest) is test.
Shuffling time-series data before evaluation would let the model "see the
future" during training and produce meaningless metrics.

Evaluation (`ml-service/scripts/evaluate_forecast.py`) then does **walk-forward
one-step-ahead** scoring on the test split, for both models:

- **Existing baseline**: the pure heuristic formula is applied to each test
  point's features to predict the *next* window; scored against that next
  window's actual value. (Calls the heuristic function directly rather than
  the stateful `predict_from_aggregate`, so this measurement isn't
  contaminated by that function's automatic switch-over to Holt-Winters once
  enough history accumulates — see the comment in `evaluate_forecast.py`.)
- **Holt-Winters**: starting from the fitted train history, at each test step
  the model forecasts one step ahead, is scored against the actual, and then
  the actual is appended to its history before the next step (mirroring how
  the live per-route buffer grows in production).

## Evaluation metrics (MAE, RMSE, MAPE)

Command:

```
cd ml-service && make evaluate-forecast
# or: python scripts/evaluate_forecast.py
```

Actual results from this machine (seed=42, 60-day synthetic series, 1,152
train / 288 test hourly points):

| Model | MAE | RMSE | MAPE |
|---|---|---|---|
| Existing baseline (`baseline-v2-aggregate`) | 65.79 | 82.86 | 25.15% |
| Holt-Winters (`holt-winters-v1`) | 28.63 | 41.43 | 12.15% |

The new model roughly **halves** MAE, RMSE, and MAPE versus the existing
heuristic on held-out data. This is expected: the heuristic has no notion of
daily seasonality or trend and simply scales up whatever the current window
looked like, so it's systematically wrong at the daily peaks/troughs the
synthetic dataset (deliberately, like real traffic) contains.

MAPE uses a 1.0 RPS epsilon floor in the denominator
(`forecasting.mape`) so windows with near-zero traffic don't distort the
percentage metric.

## Tests

`ml-service/test_forecasting.py`: deterministic dataset generation, seed
sensitivity, chronological split ordering, insufficient-history rejection,
forecast-before-fit rejection, determinism of repeated fits, non-negative
forecasts, and metric edge cases (mismatched lengths, near-zero MAPE safety).

`ml-service/test_predictor.py`: existing high-risk scenario (unchanged
behavior), fallback to the heuristic for a fresh route, switch-over to
Holt-Winters once a route accumulates enough history, non-negative
predictions under a declining-to-zero series, legacy endpoint still
functions, and per-route history isolation/bounding.

Run: `cd ml-service && make test` (or `python -m pytest -q`).

## Limitations

- **Per-route history is process-local, in-memory state.** It doesn't
  survive a restart and isn't shared across `ml-service` replicas — a route
  handled by replica A won't benefit from history accumulated on replica B,
  and each replica needs to re-accumulate its own 48-point warmup after a
  restart/redeploy. Persisting history externally (Redis/PostgreSQL, per
  `docs/architecture.md`'s storage strategy) would fix both, but is out of
  scope here per the stated scope ceiling (would touch feature-service/infra
  wiring, not just the model).
- **Seasonal period assumes hourly-cadence history.** The synthetic dataset
  and the `seasonal_periods=24` default assume one history point per hour.
  In production, `AggregatedFeaturesRequest.window_size_sec` can be much
  finer (10s/30s/60s per the architecture doc's aggregation windows), so a
  route that's actually polled every 60s would need `seasonal_periods=1440`
  (24h at 60s resolution) to represent a real day, not 24. Today the live
  integration treats "one call = one period step" regardless of the
  request's actual `window_size_sec`; retuning the seasonal period per
  window size is a documented follow-up, not implemented here.
- **No confidence intervals.** The model returns a point forecast only; the
  Decision Engine's thresholds still operate on a single `predicted_rps`
  value, same as before.
- **Fixed smoothing constants** (`alpha=0.3, beta=0.1, gamma=0.2`) are not
  tuned per-route or re-optimized automatically; they were chosen once
  against the synthetic dataset. A route with very different volatility
  characteristics may want different constants.
- **Cold-start fallback quality**: for the first 48 hours of a new route's
  life, predictions come from the original heuristic, which this same
  evaluation shows has roughly double the error of the seasonal model.
