package com.spydrone.orthanc_scan_producer.scan;

import java.time.Instant;

/**
 * The parts of the consumer's lot (GET /api/lots/{lotId}) the producer checks before publishing.
 *
 * @param status lowercase lot status ("active", "canceled", ...); null is treated as active
 * @param lastScan the scan that put the lot where it is; null if the consumer doesn't know it
 */
public record LotState(String currentStage, String wipLocation, boolean onHold, String status, LastScan lastScan) {

	public record LastScan(String clientId, String userName, Instant at) {
	}

	public LotState(String currentStage, boolean onHold, String status) {
		this(currentStage, null, onHold, status, null);
	}
}
