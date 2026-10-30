package com.spydrone.orthanc_scan_producer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * The consumer circuit breakers' states and counters, at /actuator/health/circuits. Always UP: an open
 * circuit means the producer is falling back as designed, not that it's unhealthy.
 */
@Component("consumerCircuitsHealthIndicator")
public class ConsumerCircuitsHealthIndicator implements HealthIndicator {

	private final List<CircuitBreaker> circuitBreakers;

	public ConsumerCircuitsHealthIndicator(List<CircuitBreaker> circuitBreakers) {
		this.circuitBreakers = circuitBreakers;
	}

	@Override
	public Health health() {
		Health.Builder health = Health.up();
		for (CircuitBreaker breaker : circuitBreakers) {
			CircuitBreaker.Metrics metrics = breaker.getMetrics();
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("state", breaker.getState());
			details.put("failureRate", metrics.getFailureRate());
			details.put("bufferedCalls", metrics.getNumberOfBufferedCalls());
			details.put("failedCalls", metrics.getNumberOfFailedCalls());
			details.put("notPermittedCalls", metrics.getNumberOfNotPermittedCalls());
			health.withDetail(breaker.getName(), details);
		}
		return health.build();
	}
}
