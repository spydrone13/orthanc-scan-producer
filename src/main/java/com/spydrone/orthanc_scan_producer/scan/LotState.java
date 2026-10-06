package com.spydrone.orthanc_scan_producer.scan;

/** The parts of the consumer's lot (GET /api/lots/{lotId}) the producer checks before publishing. */
public record LotState(String currentStage, boolean onHold) {
}
