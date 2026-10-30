package com.spydrone.orthanc_scan_producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class ConsumerCircuitsHealthIndicatorTest {

	private static final ConsumerCircuitBreakerConfig.Settings SETTINGS =
			new ConsumerCircuitBreakerConfig.Settings(50, 10, 5, Duration.ofSeconds(30), 2);

	private final CircuitBreaker lotLookup = ConsumerCircuitBreakerConfig.breaker("lot-lookup", SETTINGS);
	private final CircuitBreaker lotStages = ConsumerCircuitBreakerConfig.breaker("lot-stages", SETTINGS);
	private final ConsumerCircuitsHealthIndicator indicator =
			new ConsumerCircuitsHealthIndicator(List.of(lotLookup, lotStages));

	@Test
	void reportsEachCircuitClosed() {
		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsOnlyKeys("lot-lookup", "lot-stages");
		assertThat(details(health, "lot-lookup"))
				.containsEntry("state", CircuitBreaker.State.CLOSED)
				.containsEntry("failureRate", -1.0f)
				.containsEntry("bufferedCalls", 0)
				.containsEntry("failedCalls", 0)
				.containsEntry("notPermittedCalls", 0L);
	}

	@Test
	void openCircuitIsShownButStillUp() {
		lotStages.transitionToOpenState();
		assertThatThrownBy(() -> lotStages.executeSupplier(() -> "catalog"))
				.isInstanceOf(CallNotPermittedException.class);

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(details(health, "lot-stages"))
				.containsEntry("state", CircuitBreaker.State.OPEN)
				.containsEntry("notPermittedCalls", 1L);
		assertThat(details(health, "lot-lookup")).containsEntry("state", CircuitBreaker.State.CLOSED);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> details(Health health, String circuit) {
		return (Map<String, Object>) health.getDetails().get(circuit);
	}
}
