package com.spydrone.orthanc_scan_producer.scan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A scan as submitted by the UI.
 *
 * @param clientId client-generated id, also sent as the Idempotency-Key header so repeat sends are ignored
 * @param correctionReason set when the operator confirms the lot is at currentStage although the records
 *        have it elsewhere (after a LOT_LOCATION_MISMATCH response)
 */
public record ScanRecord(
		@NotBlank String clientId,
		@NotBlank String userName,
		@NotBlank String currentStage,
		@NotBlank String lotId,
		@NotBlank String destinationStage,
		String destinationWipLocation,
		ScanType scanType,
		String note,
		@Size(max = 2000) String correctionReason) {
}
