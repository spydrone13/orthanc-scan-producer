package com.spydrone.orthanc_scan_producer.scan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
		@NotBlank String destination,
		ScanType scanType,
		String note) {
}
