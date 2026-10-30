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

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ScanServiceTest {

	private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
	private final ScanRejectionService scanRejectionService = mock(ScanRejectionService.class);
	private final ScanService service = new ScanService(rabbitTemplate, scanRejectionService, "ex", "rk");
	private final ScanRecord record = new ScanRecord("abc", "u", "S1", "L1", "S2", "W1", ScanType.TRANSITIONAL, "",
			"Found it on the Stage 1 rack", true);

	@Test
	void acceptedScanIsPublishedAndEchoed() {
		ScanResponse response = service.submit(record);

		assertThat(response).isEqualTo(ScanResponse.accepted(record));
		assertThat(response.errorCode()).isNull();
		assertThat(response.correctionReason()).isEqualTo("Found it on the Stage 1 rack");
		assertThat(response.locationConfirmed()).isTrue();
		verify(rabbitTemplate).convertAndSend("ex", "rk", record);
	}

	@Test
	void rejectedScanIsReturnedAndNotPublished() {
		ScanResponse rejection = ScanResponse.rejected(record, "LOT_ON_HOLD", "Lot L1 is on hold");
		given(scanRejectionService.rejection(record)).willReturn(Optional.of(rejection));

		assertThat(service.submit(record)).isSameAs(rejection);
		verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
	}

	@Test
	void rejectedScanIsCheckedAgainWhenResent() {
		given(scanRejectionService.rejection(record))
				.willReturn(Optional.of(ScanResponse.rejected(record, "LOT_ON_HOLD", "Lot L1 is on hold")))
				.willReturn(Optional.empty());

		assertThat(service.submit(record).errorCode()).isEqualTo("LOT_ON_HOLD");
		assertThat(service.submit(record).errorCode()).isNull();
		verify(rabbitTemplate, times(1)).convertAndSend("ex", "rk", record);
	}

	@Test
	void repeatSendPublishesOnce() {
		ScanResponse first = service.submit(record);
		ScanResponse second = service.submit(record);

		assertThat(second).isSameAs(first);
		verify(rabbitTemplate, times(1)).convertAndSend("ex", "rk", record);
		verify(scanRejectionService, times(1)).rejection(record);
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
}
