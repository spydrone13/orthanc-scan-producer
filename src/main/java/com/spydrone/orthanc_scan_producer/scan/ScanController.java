package com.spydrone.orthanc_scan_producer.scan;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/scans")
public class ScanController {

	private final ScanService scanService;

	public ScanController(ScanService scanService) {
		this.scanService = scanService;
	}

	@PostMapping
	public ScanResponse postScan(@Valid @RequestBody ScanRecord record,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		if (idempotencyKey != null && !idempotencyKey.equals(record.clientId())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must match clientId");
		}
		return scanService.submit(record);
	}
}
