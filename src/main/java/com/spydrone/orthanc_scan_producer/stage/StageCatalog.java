package com.spydrone.orthanc_scan_producer.stage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lot-stage graph for checking scans, parsed from {@link LotStageClient}'s catalog. Kept for a
 * minute so scans don't each wait on the consumer; the client's on-disk copy covers the consumer
 * being unreachable.
 */
@Component
public class StageCatalog {

	private static final Logger log = LoggerFactory.getLogger(StageCatalog.class);
	private static final Duration MAX_AGE = Duration.ofMinutes(1);
	private static final JsonMapper JSON = new JsonMapper();

	/** A stage as the scan checks need it; the same field names as GET /api/lot-stages. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Stage(
			String description,
			@JsonProperty("next-stages") List<String> nextStages,
			@JsonProperty("wip-locations") Map<String, WipLocation> wipLocations,
			@JsonProperty("next-wip-locations") Map<String, List<String>> nextWipLocations) {

		public Stage {
			nextStages = nextStages == null ? List.of() : nextStages;
			wipLocations = wipLocations == null ? Map.of() : wipLocations;
			nextWipLocations = nextWipLocations == null ? Map.of() : nextWipLocations;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record WipLocation(String description) {
	}

	private record Snapshot(Map<String, Stage> stages, Instant fetchedAt) {
	}

	private final LotStageClient lotStageClient;
	private final Clock clock;
	private volatile Snapshot snapshot;

	@Autowired
	public StageCatalog(LotStageClient lotStageClient) {
		this(lotStageClient, Clock.systemUTC());
	}

	StageCatalog(LotStageClient lotStageClient, Clock clock) {
		this.lotStageClient = lotStageClient;
		this.clock = clock;
	}

	/** Stages by id, or empty if no catalog has ever been fetched or it can't be read. */
	public Optional<Map<String, Stage>> stages() {
		Snapshot current = snapshot;
		Instant now = clock.instant();
		if (current != null && current.fetchedAt().plus(MAX_AGE).isAfter(now)) {
			return Optional.of(current.stages());
		}
		Optional<Map<String, Stage>> parsed = lotStageClient.stages().flatMap(StageCatalog::parse);
		parsed.ifPresent(stages -> snapshot = new Snapshot(stages, now));
		return parsed.or(() -> Optional.ofNullable(current).map(Snapshot::stages));
	}

	private static Optional<Map<String, Stage>> parse(String json) {
		try {
			return Optional.of(JSON.readValue(json, new TypeReference<LinkedHashMap<String, Stage>>() {}));
		}
		catch (JacksonException e) {
			log.warn("Could not read the lot-stage catalog; scans won't be checked against it: {}", e.getMessage());
			return Optional.empty();
		}
	}
}
