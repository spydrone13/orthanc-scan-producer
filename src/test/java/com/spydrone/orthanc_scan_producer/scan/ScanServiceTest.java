package com.spydrone.orthanc_scan_producer.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.spydrone.orthanc_scan_producer.stage.StageCatalog;
import com.spydrone.orthanc_scan_producer.stage.StageCatalog.Stage;
import com.spydrone.orthanc_scan_producer.stage.StageCatalog.WipLocation;

class ScanServiceTest {

	private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
	private final LotClient lotClient = mock(LotClient.class);
	private final StageCatalog stageCatalog = mock(StageCatalog.class);
	private final ScanService service = new ScanService(rabbitTemplate, lotClient, stageCatalog, "ex", "rk");
	private final ScanRecord record =
			new ScanRecord("abc", "u", "S1", "L1", "S2", "W1", ScanType.TRANSITIONAL, "", null, null);

	@Test
	void heldLotLeavingItsStageIsRejectedAndNotPublished() {
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S1", true, "active")));

		ScanResponse response = service.submit(record);

		assertThat(response.errorCode()).isEqualTo("LOT_ON_HOLD");
		assertThat(response.errorMessage()).isEqualTo("Lot L1 is on hold");
		assertThat(response.clientId()).isEqualTo("abc");
		verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
	}

	@Test
	void heldLotMovingWithinItsStageIsPublished() {
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S2", true, "active")));

		assertThat(service.submit(record).errorCode()).isNull();
		verify(rabbitTemplate).convertAndSend("ex", "rk", record);
	}

	@Test
	void unknownOrUnheldLotIsPublished() {
		given(lotClient.find("L1")).willReturn(Optional.empty());
		assertThat(service.submit(record).errorCode()).isNull();

		ScanRecord other = new ScanRecord("def", "u", "S1", "L2", "S2", null, ScanType.TRANSITIONAL, "", null, null);
		given(lotClient.find("L2")).willReturn(Optional.of(new LotState("S1", false, "active")));
		assertThat(service.submit(other).errorCode()).isNull();

		verify(rabbitTemplate).convertAndSend("ex", "rk", record);
		verify(rabbitTemplate).convertAndSend("ex", "rk", other);
	}

	@Test
	void rejectedScanIsCheckedAgainWhenResent() {
		given(lotClient.find("L1"))
				.willReturn(Optional.of(new LotState("S1", true, "active")))
				.willReturn(Optional.of(new LotState("S1", false, "active")));

		assertThat(service.submit(record).errorCode()).isEqualTo("LOT_ON_HOLD");
		assertThat(service.submit(record).errorCode()).isNull();
		verify(rabbitTemplate, times(1)).convertAndSend("ex", "rk", record);
	}

	@Test
	void canceledLotIsRejectedAndNotPublished() {
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S1", false, "canceled")));

		ScanResponse response = service.submit(record);

		assertThat(response.errorCode()).isEqualTo("LOT_CANCELED");
		assertThat(response.errorMessage()).isEqualTo("Lot L1 is canceled");
		verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
	}

	@Test
	void completeLotIsRejectedEvenWithinItsStage() {
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S2", false, "complete")));

		assertThat(service.submit(record).errorCode()).isEqualTo("LOT_COMPLETE");
	}

	@Test
	void statusIsCheckedBeforeHold() {
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S1", true, "destroyed")));

		assertThat(service.submit(record).errorCode()).isEqualTo("LOT_DESTROYED");
	}

	@Test
	void missingStatusIsTreatedAsActive() {
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S1", false, null)));

		assertThat(service.submit(record).errorCode()).isNull();
		verify(rabbitTemplate).convertAndSend("ex", "rk", record);
	}

	@Test
	void repeatSendPublishesOnce() {
		ScanResponse first = service.submit(record);
		ScanResponse second = service.submit(record);

		assertThat(second).isSameAs(first);
		verify(rabbitTemplate, times(1)).convertAndSend("ex", "rk", record);
	}

	@Test
	void failedPublishIsNotRemembered() {
		doThrow(new AmqpConnectException(new RuntimeException("down")))
				.doNothing()
				.when(rabbitTemplate).convertAndSend(eq("ex"), eq("rk"), any(Object.class));

		assertThatThrownBy(() -> service.submit(record)).isInstanceOf(AmqpConnectException.class);
		assertThat(service.submit(record).clientId()).isEqualTo("abc");
		verify(rabbitTemplate, times(2)).convertAndSend("ex", "rk", record);
	}

	/** S1 → S2 (only Bench 1 allowed) → S3. */
	private static final Map<String, Stage> STAGES = Map.of(
			"S1", new Stage("Stage 1", List.of("S2"), Map.of("W0", new WipLocation("Bench 0")),
					Map.of("S2", List.of("W1"))),
			"S2", new Stage("Stage 2", List.of("S3"),
					Map.of("W1", new WipLocation("Bench 1"), "W2", new WipLocation("Bench 2")), null),
			"S3", new Stage("Stage 3", null, null, null));
	private static final Instant EARLIER = Instant.parse("2026-10-07T09:00:00Z");

	private static ScanRecord scan(String from, String to, String wip, String correctionReason) {
		return new ScanRecord("abc", "u", from, "L1", to, wip, ScanType.TRANSITIONAL, "", correctionReason, null);
	}

	private void assertNotPublished() {
		verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
	}

	@Test
	void allowedRouteIsPublishedEvenWhenTheConsumerCantBeAsked() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(Optional.empty());

		assertThat(service.submit(record).errorCode()).isNull();
		verify(rabbitTemplate).convertAndSend("ex", "rk", record);
	}

	@Test
	void stageThatIsNotNextIsRejected() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));

		ScanResponse response = service.submit(scan("S1", "S3", null, null));

		assertThat(response.errorCode()).isEqualTo("INVALID_NEXT_STAGE");
		assertThat(response.errorMessage()).isEqualTo("Stage 3 is not a next stage of Stage 1");
		assertNotPublished();
	}

	@Test
	void wipLocationNotAllowedFromTheScanStageIsRejected() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));

		ScanResponse response = service.submit(scan("S1", "S2", "W2", null));

		assertThat(response.errorCode()).isEqualTo("WIP_LOCATION_NOT_ALLOWED");
		assertThat(response.errorMessage()).isEqualTo("Lots from Stage 1 can't go to Bench 2 in Stage 2");
		assertNotPublished();
	}

	@Test
	void unknownWipLocationInTheNextStageIsRejected() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));

		assertThat(service.submit(scan("S1", "S2", "W9", null)).errorCode()).isEqualTo("WIP_LOCATION_NOT_ALLOWED");
	}

	@Test
	void anyWipLocationIsAllowedWhenTheNextStageIsNotRestricted() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));

		assertThat(service.submit(scan("S2", "S3", null, null)).errorCode()).isNull();
	}

	@Test
	void movesWithinTheScanStageAndFromUnknownStagesAreNotRouteChecked() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));

		assertThat(service.submit(scan("S1", "S1", "anything", null)).errorCode()).isNull();
		assertThat(service.submit(new ScanRecord("def", "u", "S9", "L1", "S3", null, ScanType.TRANSITIONAL, "", null, null))
				.errorCode()).isNull();
	}

	@Test
	void withoutACatalogRoutesAreNotChecked() {
		given(stageCatalog.stages()).willReturn(Optional.empty());

		assertThat(service.submit(scan("S1", "S3", null, null)).errorCode()).isNull();
	}

	@Test
	void lotNeverLoggedOutOfThePreviousStageIsAMismatch() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S1", "W0", false, "active",
				new LotState.LastScan("c0", "jsmith", EARLIER))));

		ScanResponse response = service.submit(scan("S2", "S3", null, null));

		assertThat(response.errorCode()).isEqualTo("LOT_LOCATION_MISMATCH");
		assertThat(response.errorMessage())
				.startsWith("Lot L1 was never logged out of Stage 1 (logged by jsmith at ")
				.endsWith(". Confirm it is here at Stage 2 to correct the record.");
		assertThat(response.recorded())
				.isEqualTo(new ScanResponse.RecordedLocation("S1", "W0", "jsmith", EARLIER));
		assertNotPublished();
	}

	@Test
	void lotLoggedToAnotherBranchIsAMismatch() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S3", false, "active")));

		ScanResponse response = service.submit(scan("S1", "S2", null, null));

		assertThat(response.errorCode()).isEqualTo("LOT_LOCATION_MISMATCH");
		assertThat(response.errorMessage())
				.isEqualTo("Records show lot L1 at Stage 3, not Stage 1. Confirm it is here to correct the record.");
		assertThat(response.recorded()).isEqualTo(new ScanResponse.RecordedLocation("S3", null, null, null));
	}

	@Test
	void sameMoveScannedAgainIsNotAMismatch() {
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S2", false, "active")));

		assertThat(service.submit(scan("S1", "S2", null, null)).errorCode()).isNull();
	}

	@Test
	void confirmedCorrectionIsPublishedWithItsReason() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S3", false, "active")));
		ScanRecord confirmed = scan("S1", "S2", "W1", "Found it on the Stage 1 rack");

		ScanResponse response = service.submit(confirmed);

		assertThat(response.errorCode()).isNull();
		assertThat(response.correctionReason()).isEqualTo("Found it on the Stage 1 rack");
		verify(rabbitTemplate).convertAndSend("ex", "rk", confirmed);
	}

	@Test
	void correctionConfirmedWithoutAReasonIsPublished() {
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S3", false, "active")));
		ScanRecord confirmed = new ScanRecord("abc", "u", "S1", "L1", "S2", null, ScanType.TRANSITIONAL, "", null,
				true);

		ScanResponse response = service.submit(confirmed);

		assertThat(response.errorCode()).isNull();
		assertThat(response.locationConfirmed()).isTrue();
		verify(rabbitTemplate).convertAndSend("ex", "rk", confirmed);
	}

	@Test
	void confirmedCorrectionIsStillRouteCheckedFromTheScanStage() {
		given(stageCatalog.stages()).willReturn(Optional.of(STAGES));
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S2", false, "active")));

		assertThat(service.submit(scan("S1", "S3", null, "here")).errorCode()).isEqualTo("INVALID_NEXT_STAGE");
	}

	@Test
	void heldLotCanBeCorrectedWithinTheScanStageButNotMovedOn() {
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S3", true, "active")));

		assertThat(service.submit(scan("S1", "S1", "W0", "here")).errorCode()).isNull();
		assertThat(service.submit(new ScanRecord("def", "u", "S1", "L1", "S2", null, ScanType.TRANSITIONAL, "",
				"here", null)).errorCode()).isEqualTo("LOT_ON_HOLD");
	}
}
