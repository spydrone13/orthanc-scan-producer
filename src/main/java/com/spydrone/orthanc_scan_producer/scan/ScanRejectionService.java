package com.spydrone.orthanc_scan_producer.scan;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.spydrone.orthanc_scan_producer.scan.ScanResponse.RecordedLocation;
import com.spydrone.orthanc_scan_producer.stage.StageCatalog;
import com.spydrone.orthanc_scan_producer.stage.StageCatalog.Stage;

/** Decides whether a scan is rejected before it's published, against the consumer's lot and the stage catalog. */
@Service
public class ScanRejectionService {

	static final String LOT_ON_HOLD = "LOT_ON_HOLD";
	static final String LOT_LOCATION_MISMATCH = "LOT_LOCATION_MISMATCH";
	static final String INVALID_NEXT_STAGE = "INVALID_NEXT_STAGE";
	static final String WIP_LOCATION_NOT_ALLOWED = "WIP_LOCATION_NOT_ALLOWED";
	private static final String ACTIVE = "active";
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
			.withZone(ZoneId.systemDefault());

	private final LotClient lotClient;
	private final StageCatalog stageCatalog;

	public ScanRejectionService(LotClient lotClient, StageCatalog stageCatalog) {
		this.lotClient = lotClient;
		this.stageCatalog = stageCatalog;
	}

	/**
	 * Why the scan is rejected, or empty if it may be published. The checks, in order:
	 * <ol>
	 * <li>the lot isn't active
	 * <li>it's on hold and the scan would move it out of its stage
	 * <li>the records have it at another stage than the scan's, and the operator hasn't confirmed it's
	 * here (see {@link ScanRecord#confirmsLocation()})
	 * <li>the destination isn't a next stage of the scan's stage, or its WIP location isn't allowed there
	 * </ol>
	 * A lot the consumer doesn't know (404) is accepted without any checks. The lot checks are skipped if
	 * the consumer can't be asked or its circuit is open, and the route check if no stage catalog has been
	 * fetched; the consumer then flags what slips through.
	 */
	public Optional<ScanResponse> rejection(ScanRecord record) {
		return switch (lotClient.find(record.lotId())) {
			case LotLookup.NotFound() -> Optional.empty();
			case LotLookup.Found(LotState lot) -> {
				Optional<Map<String, Stage>> stages = stageCatalog.stages();
				yield lotRejection(record, lot, stages).or(() -> stages.flatMap(s -> routeRejection(record, s)));
			}
			case LotLookup.Unavailable() -> stageCatalog.stages().flatMap(s -> routeRejection(record, s));
		};
	}

	private static Optional<ScanResponse> lotRejection(ScanRecord record, LotState lot,
			Optional<Map<String, Stage>> stages) {
		if (lot.status() != null && !ACTIVE.equals(lot.status())) {
			return Optional.of(ScanResponse.rejected(record, "LOT_" + lot.status().toUpperCase(Locale.ROOT),
					"Lot " + record.lotId() + " is " + lot.status()));
		}
		boolean mismatch = isMismatch(record, lot);
		// The consumer moves a mismatched lot to the scan's stage first, so that's the stage it would leave.
		String from = mismatch ? record.currentStage() : lot.currentStage();
		if (lot.onHold() && !record.destinationStage().equals(from)) {
			return Optional.of(ScanResponse.rejected(record, LOT_ON_HOLD, "Lot " + record.lotId() + " is on hold"));
		}
		if (mismatch && !record.confirmsLocation()) {
			return Optional.of(mismatchRejection(record, lot, stages.orElse(Map.of())));
		}
		return Optional.empty();
	}

	/**
	 * The records have the lot at another stage than the scan's. Not when they have it at the scan's
	 * destination: that's the same move scanned again.
	 */
	private static boolean isMismatch(ScanRecord record, LotState lot) {
		String recorded = lot.currentStage();
		return recorded != null && !recorded.equals(record.currentStage())
				&& !recorded.equals(record.destinationStage());
	}

	/**
	 * Words a missed scan (the records' stage leads to the scan's stage; the lot just wasn't logged
	 * out) differently from a wrong one (it was logged somewhere else entirely).
	 */
	private static ScanResponse mismatchRejection(ScanRecord record, LotState lot, Map<String, Stage> stages) {
		String recorded = lot.currentStage();
		String here = name(stages, record.currentStage());
		LotState.LastScan last = lot.lastScan();
		String by = last == null ? "" : " (logged by " + last.userName() + " at " + TIME.format(last.at()) + ")";
		boolean missedScan = Optional.ofNullable(stages.get(recorded))
				.map(stage -> stage.nextStages().contains(record.currentStage()))
				.orElse(false);
		String message = missedScan
				? "Lot %s was never logged out of %s%s. Confirm it is here at %s to correct the record."
						.formatted(record.lotId(), name(stages, recorded), by, here)
				: "Records show lot %s at %s%s, not %s. Confirm it is here to correct the record."
						.formatted(record.lotId(), name(stages, recorded), by, here);
		RecordedLocation location = new RecordedLocation(recorded, lot.wipLocation(),
				last == null ? null : last.userName(), last == null ? null : last.at());
		return ScanResponse.mismatch(record, LOT_LOCATION_MISMATCH, message, location);
	}

	/**
	 * Moves within the scan's stage are always allowed. So is any move from a stage the catalog
	 * doesn't know, since the catalog may be older than the scan station's.
	 */
	private static Optional<ScanResponse> routeRejection(ScanRecord record, Map<String, Stage> stages) {
		String from = record.currentStage();
		String to = record.destinationStage();
		Stage fromStage = stages.get(from);
		if (to.equals(from) || fromStage == null) {
			return Optional.empty();
		}
		if (!fromStage.nextStages().contains(to)) {
			return Optional.of(ScanResponse.rejected(record, INVALID_NEXT_STAGE,
					"%s is not a next stage of %s".formatted(name(stages, to), name(stages, from))));
		}
		if (isBlank(record.destinationWipLocation())) {
			return Optional.empty();
		}
		String wip = record.destinationWipLocation().trim();
		Stage toStage = stages.get(to);
		StageCatalog.WipLocation location = toStage == null ? null : toStage.wipLocations().get(wip);
		List<String> allowed = fromStage.nextWipLocations().get(to);
		if (location == null || allowed != null && !allowed.contains(wip)) {
			String wipName = location == null || location.description() == null ? wip : location.description();
			return Optional.of(ScanResponse.rejected(record, WIP_LOCATION_NOT_ALLOWED,
					"Lots from %s can't go to %s in %s".formatted(name(stages, from), wipName, name(stages, to))));
		}
		return Optional.empty();
	}

	private static String name(Map<String, Stage> stages, String id) {
		Stage stage = stages.get(id);
		return stage == null || stage.description() == null ? id : stage.description();
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
