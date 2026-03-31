create table if not exists traffic_history (
    id bigserial primary key,
    route text not null,
    timestamp bigint not null,
    request_count integer not null,
    avg_latency_ms double precision not null,
    error_rate double precision not null
);

create table if not exists prediction_history (
    id bigserial primary key,
    route text not null,
    prediction_time bigint not null,
    predicted_rps double precision not null,
    spike_probability double precision not null,
    model_version text not null
);

create table if not exists policy_decisions (
    id bigserial primary key,
    route text not null,
    decision_time bigint not null,
    policy_type text not null,
    rate_limit_rps integer,
    reason text not null,
    ttl_sec integer not null
);
