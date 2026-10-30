package com.spydrone.orthanc_scan_producer;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * Circuit breakers on the calls to orthanc-scan-consumer, one per endpoint. Once a call keeps failing
 * (unreachable, timing out, 5xx), it stops being made until the wait is over, and callers fall back right
 * away instead of waiting on the timeout; the next call after that probes the consumer. 4xx answers mean
 * the consumer is up, so they don't count as failures.
 */
@Configuration
@EnableConfigurationProperties(ConsumerCircuitBreakerConfig.Settings.class)
public class ConsumerCircuitBreakerConfig {

	private static final Logger log = LoggerFactory.getLogger(ConsumerCircuitBreakerConfig.class);

	/** Shared by every consumer breaker; see app.consumer.circuit-breaker.* in application.properties. */
	@ConfigurationProperties("app.consumer.circuit-breaker")
	public record Settings(float failureRateThreshold, int slidingWindowSize, int minimumNumberOfCalls,
			Duration waitDurationInOpenState, int permittedCallsInHalfOpenState) {
	}

	/** Lot lookups; while open, scans skip the lot checks. */
	@Bean
	CircuitBreaker lotLookupCircuitBreaker(Settings settings) {
		return breaker("lot-lookup", settings);
	}

	/** The lot-stage catalog; while open, the last copy fetched is served. */
	@Bean
	CircuitBreaker lotStagesCircuitBreaker(Settings settings) {
		return breaker("lot-stages", settings);
	}

	public static CircuitBreaker breaker(String name, Settings settings) {
		CircuitBreaker breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
				.failureRateThreshold(settings.failureRateThreshold())
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(settings.slidingWindowSize())
				.minimumNumberOfCalls(settings.minimumNumberOfCalls())
				.waitDurationInOpenState(settings.waitDurationInOpenState())
				.permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState())
				.automaticTransitionFromOpenToHalfOpenEnabled(false)
				.recordExceptions(RestClientException.class)
				.ignoreExceptions(HttpClientErrorException.class)
				.build());
		breaker.getEventPublisher().onStateTransition(event -> log.warn(
				"Circuit {} {}; while open, the consumer isn't called and the fallback is used",
				name, event.getStateTransition()));
		return breaker;
	}
}
