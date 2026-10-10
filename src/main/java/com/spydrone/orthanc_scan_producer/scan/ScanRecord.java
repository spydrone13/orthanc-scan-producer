package com.spydrone.orthanc_scan_producer.scan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A scan as submitted by the UI.
 *
 * @param clientId client-generated id, also sent as the Idempotency-Key header so repeat sends are ignored
 * @param correctionReason optional reason the operator gave with locationConfirmed
 * @param locationConfirmed set when the operator confirms the lot is at currentStage although the records
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
		@Size(max = 2000) String correctionReason,
		Boolean locationConfirmed) {

	/** Whether the operator confirmed the lot is here; older UIs confirmed with a reason alone. */
	public boolean confirmsLocation() {
		return Boolean.TRUE.equals(locationConfirmed) || (correctionReason != null && !correctionReason.isBlank());
	}
}
