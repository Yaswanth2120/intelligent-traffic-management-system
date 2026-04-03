package com.traffic.decision.consumer;

import com.traffic.decision.metrics.DecisionMetricsRecorder;
import com.traffic.decision.model.MlPredictionEvent;
import com.traffic.decision.persistence.PolicyDecisionRepository;
import com.traffic.decision.publisher.TrafficDecisionPublisher;
import com.traffic.decision.rules.DecisionPolicyService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class MlPredictionConsumer {

    private final DecisionPolicyService policyService;
    private final PolicyDecisionRepository repository;
    private final TrafficDecisionPublisher publisher;
    private final DecisionMetricsRecorder metricsRecorder;

    public MlPredictionConsumer(DecisionPolicyService policyService,
                                PolicyDecisionRepository repository,
                                TrafficDecisionPublisher publisher,
                                DecisionMetricsRecorder metricsRecorder) {
        this.policyService = policyService;
        this.repository = repository;
        this.publisher = publisher;
        this.metricsRecorder = metricsRecorder;
    }

    @KafkaListener(topics = "${traffic.topics.ml-predictions}", groupId = "decision-engine")
    public void consume(MlPredictionEvent prediction) {
        metricsRecorder.recordPredictionConsumed(prediction);
        policyService.evaluate(prediction).ifPresent(decision -> {
            metricsRecorder.recordDecision(decision);
            repository.save(decision);
            publisher.publish(decision);
        });
    }
}
