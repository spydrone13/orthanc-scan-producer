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
	private final LotClient lotClient = mock(LotClient.class);
	private final ScanService service = new ScanService(rabbitTemplate, lotClient, "ex", "rk");
	private final ScanRecord record =
			new ScanRecord("abc", "u", "S1", "L1", "S2", "W1", ScanType.TRANSITIONAL, "");

	@Test
	void heldLotLeavingItsStageIsRejectedAndNotPublished() {
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S1", true)));

		ScanResponse response = service.submit(record);

		assertThat(response.errorCode()).isEqualTo("LOT_ON_HOLD");
		assertThat(response.errorMessage()).isEqualTo("Lot L1 is on hold");
		assertThat(response.clientId()).isEqualTo("abc");
		verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
	}

	@Test
	void heldLotMovingWithinItsStageIsPublished() {
		given(lotClient.find("L1")).willReturn(Optional.of(new LotState("S2", true)));

		assertThat(service.submit(record).errorCode()).isNull();
		verify(rabbitTemplate).convertAndSend("ex", "rk", record);
	}

	@Test
	void unknownOrUnheldLotIsPublished() {
		given(lotClient.find("L1")).willReturn(Optional.empty());
		assertThat(service.submit(record).errorCode()).isNull();

		ScanRecord other = new ScanRecord("def", "u", "S1", "L2", "S2", null, ScanType.TRANSITIONAL, "");
		given(lotClient.find("L2")).willReturn(Optional.of(new LotState("S1", false)));
		assertThat(service.submit(other).errorCode()).isNull();

		verify(rabbitTemplate).convertAndSend("ex", "rk", record);
		verify(rabbitTemplate).convertAndSend("ex", "rk", other);
	}

	@Test
	void rejectedScanIsCheckedAgainWhenResent() {
		given(lotClient.find("L1"))
				.willReturn(Optional.of(new LotState("S1", true)))
				.willReturn(Optional.of(new LotState("S1", false)));

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
