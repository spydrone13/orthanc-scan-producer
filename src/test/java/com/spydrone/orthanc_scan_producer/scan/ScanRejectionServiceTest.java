package com.spydrone.orthanc_scan_producer.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.spydrone.orthanc_scan_producer.stage.StageCatalog;
import com.spydrone.orthanc_scan_producer.stage.StageCatalog.Stage;
import com.spydrone.orthanc_scan_producer.stage.StageCatalog.WipLocation;

class ScanRejectionServiceTest {

	private final LotClient lotClient = mock(LotClient.class);
	private final StageCatalog stageCatalog = mock(StageCatalog.class);
	private final ScanRejectionService rejections = new ScanRejectionService(lotClient, stageCatalog);
	private final ScanRecord record =
			new ScanRecord("abc", "u", "S1", "L1", "S2", "W1", ScanType.TRANSITIONAL, "", null, null);

	/** S1 → S2 (only Bench 1 allowed) → S3. */
	private static final Map<String, Stage> STAGES = Map.of(
			"S1", new Stage("Stage 1", List.of("S2"), Map.of("W0", new WipLocation("Bench 0")),
					Map.of("S2", List.of("W1"))),
			"S2", new Stage("Stage 2", List.of("S3"),
					Map.of("W1", new WipLocation("Bench 1"), "W2", new WipLocation("Bench 2")), null),
			"S3", new Stage("Stage 3", null, null, null));
	private static final Instant EARLIER = Instant.parse("2026-10-07T09:00:00Z");

	/** Unless a test says otherwise, the consumer can't be asked: lot checks skipped, route still checked. */
	@BeforeEach
	void consumerUnavailableByDefault() {
		given(lotClient.find(anyString())).willReturn(new LotLookup.Unavailable());
	}

	private static ScanRecord scan(String from, String to, String wip, String correctionReason) {
		return new ScanRecord("abc", "u", from, "L1", to, wip, ScanType.TRANSITIONAL, "", correctionReason, null);
	}

	private ScanResponse rejected(ScanRecord scan) {
		return rejections.rejection(scan).orElseThrow();
	}

	/** The rejection's error code, or null if the scan may be published. */
	private String errorCode(ScanRecord scan) {
		return rejections.rejection(scan).map(ScanResponse::errorCode).orElse(null);
	}

	@Test
	void heldLotLeavingItsStageIsRejected() {
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S1", true, "active")));

		ScanResponse response = rejected(record);

		assertThat(response.errorCode()).isEqualTo("LOT_ON_HOLD");
		assertThat(response.errorMessage()).isEqualTo("Lot L1 is on hold");
		assertThat(response.clientId()).isEqualTo("abc");
	}

	@Test
	void heldLotMovingWithinItsStageIsAllowed() {
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S2", true, "active")));

		assertThat(errorCode(record)).isNull();
	}

	@Test
	void unknownOrUnheldLotIsAllowed() {
		given(lotClient.find("L1")).willReturn(new LotLookup.NotFound());
		assertThat(errorCode(record)).isNull();

		ScanRecord other = new ScanRecord("def", "u", "S1", "L2", "S2", null, ScanType.TRANSITIONAL, "", null, null);
		given(lotClient.find("L2")).willReturn(new LotLookup.Found(new LotState("S1", false, "active")));
		assertThat(errorCode(other)).isNull();
	}

	@Test
	void unknownLotIsAcceptedWithoutAnyChecks() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(new LotLookup.NotFound());

		assertThat(errorCode(scan("S1", "S3", null, null))).isNull();
		assertThat(errorCode(scan("S1", "S2", "W2", null))).isNull();
		verifyNoInteractions(stageCatalog);
	}

	@Test
	void consumerUnavailableSkipsTheLotChecksButNotTheRouteCheck() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(new LotLookup.Unavailable());

		assertThat(errorCode(record)).isNull();
		assertThat(errorCode(scan("S1", "S3", null, null))).isEqualTo("INVALID_NEXT_STAGE");
	}

	@Test
	void canceledLotIsRejected() {
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S1", false, "canceled")));

		ScanResponse response = rejected(record);

		assertThat(response.errorCode()).isEqualTo("LOT_CANCELED");
		assertThat(response.errorMessage()).isEqualTo("Lot L1 is canceled");
	}

	@Test
	void completeLotIsRejectedEvenWithinItsStage() {
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S2", false, "complete")));

		assertThat(errorCode(record)).isEqualTo("LOT_COMPLETE");
	}

	@Test
	void statusIsCheckedBeforeHold() {
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S1", true, "destroyed")));

		assertThat(errorCode(record)).isEqualTo("LOT_DESTROYED");
	}

	@Test
	void missingStatusIsTreatedAsActive() {
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S1", false, null)));

		assertThat(errorCode(record)).isNull();
	}

	@Test
	void allowedRouteIsAllowedEvenWhenTheConsumerCantBeAsked() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(new LotLookup.Unavailable());

		assertThat(errorCode(record)).isNull();
	}

	@Test
	void stageThatIsNotNextIsRejected() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));

		ScanResponse response = rejected(scan("S1", "S3", null, null));

		assertThat(response.errorCode()).isEqualTo("INVALID_NEXT_STAGE");
		assertThat(response.errorMessage()).isEqualTo("Stage 3 is not a next stage of Stage 1");
	}

	@Test
	void wipLocationNotAllowedFromTheScanStageIsRejected() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));

		ScanResponse response = rejected(scan("S1", "S2", "W2", null));

		assertThat(response.errorCode()).isEqualTo("WIP_LOCATION_NOT_ALLOWED");
		assertThat(response.errorMessage()).isEqualTo("Lots from Stage 1 can't go to Bench 2 in Stage 2");
	}

	@Test
	void unknownWipLocationInTheNextStageIsRejected() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));

		assertThat(errorCode(scan("S1", "S2", "W9", null))).isEqualTo("WIP_LOCATION_NOT_ALLOWED");
	}

	@Test
	void anyWipLocationIsAllowedWhenTheNextStageIsNotRestricted() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));

		assertThat(errorCode(scan("S2", "S3", null, null))).isNull();
	}

	@Test
	void movesWithinTheScanStageAndFromUnknownStagesAreNotRouteChecked() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));

		assertThat(errorCode(scan("S1", "S1", "anything", null))).isNull();
		assertThat(errorCode(new ScanRecord("def", "u", "S9", "L1", "S3", null, ScanType.TRANSITIONAL, "", null, null)))
				.isNull();
	}

	@Test
	void withoutACatalogRoutesAreNotChecked() {
		given(stageCatalog.stages()).willReturn(Optional.empty());

		assertThat(errorCode(scan("S1", "S3", null, null))).isNull();
	}

	@Test
	void lotNeverLoggedOutOfThePreviousStageIsAMismatch() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S1", "W0", false, "active",
				new LotState.LastScan("c0", "jsmith", EARLIER))));

		ScanResponse response = rejected(scan("S2", "S3", null, null));

		assertThat(response.errorCode()).isEqualTo("LOT_LOCATION_MISMATCH");
		assertThat(response.errorMessage())
				.startsWith("Lot L1 was never logged out of Stage 1 (logged by jsmith at ")
				.endsWith(". Confirm it is here at Stage 2 to correct the record.");
		assertThat(response.recorded())
				.isEqualTo(new ScanResponse.RecordedLocation("S1", "W0", "jsmith", EARLIER));
	}

	@Test
	void lotLoggedToAnotherBranchIsAMismatch() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S3", false, "active")));

		ScanResponse response = rejected(scan("S1", "S2", null, null));

		assertThat(response.errorCode()).isEqualTo("LOT_LOCATION_MISMATCH");
		assertThat(response.errorMessage())
				.isEqualTo("Records show lot L1 at Stage 3, not Stage 1. Confirm it is here to correct the record.");
		assertThat(response.recorded()).isEqualTo(new ScanResponse.RecordedLocation("S3", null, null, null));
	}

	@Test
	void sameMoveScannedAgainIsNotAMismatch() {
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S2", false, "active")));

		assertThat(errorCode(scan("S1", "S2", null, null))).isNull();
	}

	@Test
	void correctionConfirmedWithAReasonIsAllowed() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S3", false, "active")));

		assertThat(errorCode(scan("S1", "S2", "W1", "Found it on the Stage 1 rack"))).isNull();
	}

	@Test
	void correctionConfirmedWithoutAReasonIsAllowed() {
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S3", false, "active")));

		assertThat(errorCode(new ScanRecord("abc", "u", "S1", "L1", "S2", null, ScanType.TRANSITIONAL, "", null,
				true))).isNull();
	}

	@Test
	void confirmedCorrectionIsStillRouteCheckedFromTheScanStage() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S2", false, "active")));

		assertThat(errorCode(scan("S1", "S3", null, "here"))).isEqualTo("INVALID_NEXT_STAGE");
	}

	@Test
	void heldLotCanBeCorrectedWithinTheScanStageButNotMovedOn() {
		given(lotClient.find("L1")).willReturn(new LotLookup.Found(new LotState("S3", true, "active")));

		assertThat(errorCode(scan("S1", "S1", "W0", "here"))).isNull();
		assertThat(errorCode(new ScanRecord("def", "u", "S1", "L1", "S2", null, ScanType.TRANSITIONAL, "",
				"here", null))).isEqualTo("LOT_ON_HOLD");
	}
}
