package com.spydrone.orthanc_scan_producer.scan;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Looks up a lot in orthanc-scan-consumer, giving up after 2 s. Errors are thrown so
 * {@link ScanRejectionService} can count them toward its circuit breaker and fail open.
 */
@Component
public class LotClient {

	private static final Duration TIMEOUT = Duration.ofSeconds(2);

	private final RestClient restClient;

	@Autowired
	public LotClient(@Value("${app.consumer.base-url}") String baseUrl) {
		this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()));
	}

	LotClient(RestClient.Builder builder) {
		this.restClient = builder.build();
	}

	private static SimpleClientHttpRequestFactory requestFactory() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(TIMEOUT);
		factory.setReadTimeout(TIMEOUT);
		return factory;
	}

	/**
	 * The lot, or empty if the consumer doesn't know it.
	 *
	 * @throws RestClientException if the consumer couldn't be asked or answered with another error
	 */
	public Optional<LotState> find(String lotId) {
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
