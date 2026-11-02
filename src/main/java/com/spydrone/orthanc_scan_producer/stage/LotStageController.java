package com.spydrone.orthanc_scan_producer.stage;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The lot-stage catalog for orthanc-scan-ui, passed through from the consumer as-is. Comes from
 * {@link StageCatalog}, so the UI is offered the same stages the scans are checked against.
 */
@RestController
@RequestMapping("/api/lot-stages")
public class LotStageController {

	private final StageCatalog stageCatalog;

	public LotStageController(StageCatalog stageCatalog) {
		this.stageCatalog = stageCatalog;
	}

	@GetMapping
	public ResponseEntity<String> getLotStages() {
		return stageCatalog.json()
				.map(json -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json))
				.orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
	}
}
