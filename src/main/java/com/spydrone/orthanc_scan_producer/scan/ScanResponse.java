package com.spydrone.orthanc_scan_producer.scan;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The accepted scan echoed back. A 200 response may still carry a business error code. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScanResponse(
		String clientId,
		String userName,
		String currentStage,
		String lotId,
		String destination,
		ScanType scanType,
		String note,
		String errorCode,
		String errorMessage) {

	public static ScanResponse accepted(ScanRecord record) {
		return new ScanResponse(record.clientId(), record.userName(), record.currentStage(), record.lotId(),
				record.destination(), record.scanType(), record.note(), null, null);
	}
}
