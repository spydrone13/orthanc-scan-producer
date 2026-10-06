package com.spydrone.orthanc_scan_producer.scan;

/**
 * The parts of the consumer's lot (GET /api/lots/{lotId}) the producer checks before publishing.
 *
 * @param status lowercase lot status ("active", "canceled", ...); null is treated as active
 */
public record LotState(String currentStage, boolean onHold, String status) {
}
