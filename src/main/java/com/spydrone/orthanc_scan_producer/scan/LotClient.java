package com.spydrone.orthanc_scan_producer.scan;

import java.time.Duration;
import java.util.Optional;

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
 * Looks up a lot in orthanc-scan-consumer, giving up after 2 s. Fails open: if the consumer can't be
 * asked, the lot is reported as not found and the scan's lot checks are skipped. After repeated failures,
 * the circuit breaker (see {@link ConsumerCircuitBreakerConfig}) skips the call for a while so scans don't
 * each wait on the timeout.
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
	 * The lot, or empty if the consumer doesn't know it, couldn't be asked, or the circuit is open.
	 */
	public Optional<LotState> find(String lotId) {
		try {
			return circuitBreaker.executeSupplier(() -> fetch(lotId));
		}
		catch (CallNotPermittedException e) {
			// Circuit open: don't wait on the consumer.
			return Optional.empty();
		}
		catch (RestClientException e) {
			log.warn("Could not look up lot {}; scans skip the lot checks: {}", lotId, e.getMessage());
			return Optional.empty();
		}
	}

	/** A 404 is an answer, not a failure, so it's empty here rather than counted by the breaker. */
	private Optional<LotState> fetch(String lotId) {
		try {
			return Optional.ofNullable(restClient.get()
					.uri("/api/lots/{lotId}", lotId)
					.retrieve()
					.body(LotState.class));
		}
		catch (HttpClientErrorException.NotFound e) {
			return Optional.empty();
		}
	}
}
