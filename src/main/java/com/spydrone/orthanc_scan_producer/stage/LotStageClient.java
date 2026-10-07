package com.spydrone.orthanc_scan_producer.stage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches the lot-stage catalog from orthanc-scan-consumer and keeps the last good copy on disk, so the
 * plant can keep scanning while the off-site consumer is unreachable.
 */
@Component
public class LotStageClient {

	private static final Logger log = LoggerFactory.getLogger(LotStageClient.class);
	private static final Duration TIMEOUT = Duration.ofSeconds(2);

	private final RestClient restClient;
	private final Path cacheFile;
	private volatile String cached;

	@Autowired
	public LotStageClient(@Value("${app.consumer.base-url}") String baseUrl,
			@Value("${app.lot-stages.cache-file}") Path cacheFile) {
		this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()), cacheFile);
	}

	LotStageClient(RestClient.Builder builder, Path cacheFile) {
		this.restClient = builder.build();
		this.cacheFile = cacheFile;
	}

	private static SimpleClientHttpRequestFactory requestFactory() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(TIMEOUT);
		factory.setReadTimeout(TIMEOUT);
		return factory;
	}

	/** The consumer's catalog as JSON, else the last one it returned, else empty. */
	public Optional<String> stages() {
		try {
			String json = restClient.get().uri("/api/lot-stages").retrieve().body(String.class);
			if (json != null) {
				cached = json;
				save(json);
				return Optional.of(json);
			}
		}
		catch (RestClientException e) {
			log.warn("Could not fetch lot stages; serving the cached copy: {}", e.getMessage());
		}
		return Optional.ofNullable(cached).or(this::load);
	}

	/** Written to a temp file and moved into place, so a crash never leaves a half-written cache. */
	private void save(String json) {
		try {
			Path dir = cacheFile.toAbsolutePath().getParent();
			Files.createDirectories(dir);
			Path tmp = Files.createTempFile(dir, "lot-stages", ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException e) {
			log.warn("Could not save lot stages to {}: {}", cacheFile, e.getMessage());
		}
	}

	private Optional<String> load() {
		try {
			String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
			cached = json;
			return Optional.of(json);
		}
		catch (IOException e) {
			return Optional.empty();
		}
	}
}
