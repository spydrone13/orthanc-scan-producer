package com.spydrone.orthanc_scan_producer.scan;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.spydrone.orthanc_scan_producer.ConsumerCircuitBreakerConfig;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Looks up a lot in orthanc-scan-consumer, giving up after 2 s. Never throws: a 404 is reported as
 * {@link LotLookup.NotFound}, and any failure as {@link LotLookup.Unavailable} so scans fail open. After
 * repeated failures, the circuit breaker (see {@link ConsumerCircuitBreakerConfig}) skips the call for a
 * while so scans don't each wait on the timeout.
 */
@Component
public class LotClient {

	private static final Logger log = LoggerFactory.getLogger(LotClient.class);
	private static final Duration TIMEOUT = Duration.ofSeconds(2);

	private final RestClient restClient;
	private final CircuitBreaker circuitBreaker;

	@Autowired
	public LotClient(@Value("${app.consumer.base-url}") String baseUrl,
			@Qualifier("lotLookupCircuitBreaker") CircuitBreaker circuitBreaker) {
		this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()), circuitBreaker);
	}

	LotClient(RestClient.Builder builder, CircuitBreaker circuitBreaker) {
		this.restClient = builder.build();
		this.circuitBreaker = circuitBreaker;
	}

	private static SimpleClientHttpRequestFactory requestFactory() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(TIMEOUT);
		factory.setReadTimeout(TIMEOUT);
		return factory;
	}

	/**
	 * The lot if the consumer has it, {@link LotLookup.NotFound} if it answered 404, or
	 * {@link LotLookup.Unavailable} if it couldn't be asked, gave an empty answer, or its circuit is open.
	 */
	public LotLookup find(String lotId) {
		try {
			return circuitBreaker.executeSupplier(() -> fetch(lotId));
		}
		catch (CallNotPermittedException e) {
			// Circuit open: don't wait on the consumer.
			return new LotLookup.Unavailable();
		}
		catch (RestClientException e) {
			log.warn("Could not look up lot {}; scans skip the lot checks: {}", lotId, e.getMessage());
			return new LotLookup.Unavailable();
		}
	}

	/** A 404 is an answer, not a failure, so it's mapped here rather than counted by the breaker. */
	private LotLookup fetch(String lotId) {
		try {
			LotState lot = restClient.get()
					.uri("/api/lots/{lotId}", lotId)
					.retrieve()
					.body(LotState.class);
			if (lot == null) {
				log.warn("Consumer returned no body for lot {}; scans skip the lot checks", lotId);
				return new LotLookup.Unavailable();
			}
			return new LotLookup.Found(lot);
		}
		catch (HttpClientErrorException.NotFound e) {
			return new LotLookup.NotFound();
		}
	}
}
