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
 * The lot-stage catalog from {@link LotStageClient}, kept for a minute so neither scans nor UI loads each
 * wait on the consumer, and so the UI is shown the same catalog the scans are checked against. Served both
 * as the consumer's JSON, passed through as-is for the UI, and parsed into the graph for the scan checks.
 * The client's on-disk copy covers the consumer being unreachable.
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

	/** @param stages the last catalog that could be read; null if none could */
	private record Snapshot(String json, Map<String, Stage> stages, Instant fetchedAt) {
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

	/** The consumer's catalog JSON as-is, or empty if none has ever been fetched. */
	public Optional<String> json() {
		return refresh().map(Snapshot::json);
	}

	/** Stages by id, or empty if no catalog has ever been fetched or none could be read. */
	public Optional<Map<String, Stage>> stages() {
		return refresh().map(Snapshot::stages);
	}

	/**
	 * The snapshot, fetched again once it's a minute old. A catalog that can't be read is still kept for
	 * the UI, while scans keep being checked against the last one that could. If nothing is fetched, the
	 * old snapshot is served and the next call tries again.
	 */
	private Optional<Snapshot> refresh() {
		Snapshot current = snapshot;
		Instant now = clock.instant();
		if (current != null && current.fetchedAt().plus(MAX_AGE).isAfter(now)) {
			return Optional.of(current);
		}
		Optional<Snapshot> fetched = lotStageClient.stages().map(json -> new Snapshot(json,
				parse(json).orElse(current == null ? null : current.stages()), now));
		fetched.ifPresent(fresh -> snapshot = fresh);
		return fetched.or(() -> Optional.ofNullable(current));
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
