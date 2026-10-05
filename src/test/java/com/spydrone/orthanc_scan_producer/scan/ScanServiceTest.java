package com.spydrone.orthanc_scan_producer.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ScanServiceTest {

	private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
	private final ScanService service = new ScanService(rabbitTemplate, "ex", "rk");
	private final ScanRecord record =
			new ScanRecord("abc", "u", "S1", "L1", "S2", "W1", ScanType.TRANSITIONAL, "");

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
