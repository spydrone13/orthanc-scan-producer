package com.spydrone.orthanc_scan_producer.scan;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Looks up a lot in orthanc-scan-consumer. Lookups fail open: if the consumer can't answer quickly,
 * the scan is accepted and the consumer's own hold check applies when it processes the scan.
 */
@Component
public class LotClient {

	private static final Logger log = LoggerFactory.getLogger(LotClient.class);
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

	/** The lot, or empty if the consumer doesn't know it or couldn't be asked. */
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
		catch (RestClientException e) {
			log.warn("Could not look up lot {}; accepting scan without a hold check: {}", lotId, e.getMessage());
			return Optional.empty();
		}
	}
}
