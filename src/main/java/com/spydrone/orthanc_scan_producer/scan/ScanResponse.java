package com.spydrone.orthanc_scan_producer.scan;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The accepted scan echoed back. A 200 response may still carry a business error code.
 *
 * @param recorded with LOT_LOCATION_MISMATCH, where the records have the lot and who put it there
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScanResponse(
		String clientId,
		String userName,
		String currentStage,
		String lotId,
		String destinationStage,
		String destinationWipLocation,
		ScanType scanType,
		String note,
		String correctionReason,
		Boolean locationConfirmed,
		String errorCode,
		String errorMessage,
		RecordedLocation recorded) {

	/** Where the consumer's records have a lot, and the scan that put it there (null if not known). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RecordedLocation(String stage, String wipLocation, String scannedBy, Instant scannedAt) {
	}

	public static ScanResponse accepted(ScanRecord record) {
		return rejected(record, null, null);
	}

	/** Not published; the UI shows the scan as rejected (not resendable). */
	public static ScanResponse rejected(ScanRecord record, String errorCode, String errorMessage) {
		return mismatch(record, errorCode, errorMessage, null);
	}

	/**
	 * Not published: the records have the lot elsewhere. The UI may resend the scan with
	 * locationConfirmed once the operator confirms the lot is here.
	 */
	public static ScanResponse mismatch(ScanRecord record, String errorCode, String errorMessage,
			RecordedLocation recorded) {
		return new ScanResponse(record.clientId(), record.userName(), record.currentStage(), record.lotId(),
				record.destinationStage(), record.destinationWipLocation(), record.scanType(), record.note(),
				record.correctionReason(), record.locationConfirmed(), errorCode, errorMessage, recorded);
	}
}
