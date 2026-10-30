package com.spydrone.orthanc_scan_producer.scan;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * The circuit breaker on lot lookups. Once the consumer keeps failing (unreachable, timing out, 5xx), scans
 * stop waiting on it and skip the lot checks until the wait is over; the next scan after that probes it.
 * 4xx answers mean the consumer is up, so they don't count as failures.
 */
@Configuration
public class LotLookupConfig {

	private static final Logger log = LoggerFactory.getLogger(LotLookupConfig.class);

	@Bean
	CircuitBreaker lotLookupCircuitBreaker(
			@Value("${app.consumer.circuit-breaker.failure-rate-threshold}") float failureRateThreshold,
			@Value("${app.consumer.circuit-breaker.sliding-window-size}") int slidingWindowSize,
			@Value("${app.consumer.circuit-breaker.minimum-number-of-calls}") int minimumNumberOfCalls,
			@Value("${app.consumer.circuit-breaker.wait-duration-in-open-state}") Duration waitDurationInOpenState,
			@Value("${app.consumer.circuit-breaker.permitted-calls-in-half-open-state}") int permittedCallsInHalfOpenState) {
		CircuitBreaker breaker = CircuitBreaker.of("lot-lookup", CircuitBreakerConfig.custom()
				.failureRateThreshold(failureRateThreshold)
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(slidingWindowSize)
				.minimumNumberOfCalls(minimumNumberOfCalls)
				.waitDurationInOpenState(waitDurationInOpenState)
				.permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpenState)
				.automaticTransitionFromOpenToHalfOpenEnabled(false)
				.recordExceptions(RestClientException.class)
				.ignoreExceptions(HttpClientErrorException.class)
				.build());
		breaker.getEventPublisher().onStateTransition(event -> log.warn(
				"Lot lookup circuit {}; while open, scans skip the lot checks without asking the consumer",
				event.getStateTransition()));
		return breaker;
	}
}
