package com.spydrone.orthanc_scan_producer.scan;

import jakarta.validation.constraints.NotBlank;

/**
 * A scan as submitted by the UI.
 *
 * @param clientId client-generated id, also sent as the Idempotency-Key header so repeat sends are ignored
 */
public record ScanRecord(
		@NotBlank String clientId,
		@NotBlank String userName,
		@NotBlank String currentStage,
		@NotBlank String lotId,
		@NotBlank String destinationStage,
		String destinationWipLocation,
		ScanType scanType,
		String note) {
}
