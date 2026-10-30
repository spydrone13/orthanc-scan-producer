package com.spydrone.orthanc_scan_producer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

class ConsumerCircuitBreakerConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(ConsumerCircuitBreakerConfig.class)
			.withPropertyValues(
					"app.consumer.circuit-breaker.failure-rate-threshold=40",
					"app.consumer.circuit-breaker.sliding-window-size=20",
					"app.consumer.circuit-breaker.minimum-number-of-calls=8",
					"app.consumer.circuit-breaker.wait-duration-in-open-state=45s",
					"app.consumer.circuit-breaker.permitted-calls-in-half-open-state=3");

	@Test
	void oneBreakerPerConsumerEndpointBuiltFromProperties() {
		runner.run(context -> {
			CircuitBreaker lotLookup = context.getBean("lotLookupCircuitBreaker", CircuitBreaker.class);
			CircuitBreaker lotStages = context.getBean("lotStagesCircuitBreaker", CircuitBreaker.class);

			assertThat(lotLookup.getName()).isEqualTo("lot-lookup");
			assertThat(lotStages.getName()).isEqualTo("lot-stages");
			for (CircuitBreaker breaker : new CircuitBreaker[] { lotLookup, lotStages }) {
				CircuitBreakerConfig config = breaker.getCircuitBreakerConfig();
				assertThat(config.getFailureRateThreshold()).isEqualTo(40f);
				assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
				assertThat(config.getSlidingWindowSize()).isEqualTo(20);
				assertThat(config.getMinimumNumberOfCalls()).isEqualTo(8);
				assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
						.isEqualTo(Duration.ofSeconds(45).toMillis());
				assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
				assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
			}
		});
	}

	@Test
	void consumerOutagesCountAsFailuresButClientErrorsDont() {
		CircuitBreakerConfig config = ConsumerCircuitBreakerConfig
				.breaker("test", new ConsumerCircuitBreakerConfig.Settings(50, 10, 5, Duration.ofSeconds(30), 2))
				.getCircuitBreakerConfig();

		assertThat(config.getRecordExceptionPredicate().test(new ResourceAccessException("timed out"))).isTrue();
		assertThat(config.getRecordExceptionPredicate().test(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)))
				.isTrue();
		assertThat(config.getIgnoreExceptionPredicate().test(new HttpClientErrorException(HttpStatus.BAD_REQUEST)))
				.isTrue();
	}
}
