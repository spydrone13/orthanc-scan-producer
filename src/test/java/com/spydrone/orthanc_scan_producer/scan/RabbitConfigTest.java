package com.spydrone.orthanc_scan_producer.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

class RabbitConfigTest {

	private final RabbitConfig config = new RabbitConfig();

	@Test
	void scansQueueDeadLettersToTheDeadLetterQueue() {
		Queue queue = config.scansQueue("orthanc.scans", "orthanc.scans.dlx", "orthanc.scans.dlq", null);

		assertThat(queue.isDurable()).isTrue();
		assertThat(queue.getArguments())
				.containsEntry("x-dead-letter-exchange", "orthanc.scans.dlx")
				.containsEntry("x-dead-letter-routing-key", "orthanc.scans.dlq")
				.doesNotContainKey("x-expires");
	}

	@Test
	void queuesExpireWhenAnExpiryIsSet() {
		Queue queue = config.scansQueue("orthanc.scans.me", "orthanc.scans.dlx", "orthanc.scans.dlq.me",
				Duration.ofDays(7));
		Queue dlq = config.scansDeadLetterQueue("orthanc.scans.dlq.me", Duration.ofDays(7));

		assertThat(queue.getArguments()).containsEntry("x-expires", 604_800_000);
		assertThat(dlq.getArguments()).containsEntry("x-expires", 604_800_000);
	}

	@Test
	void deadLetterQueueIsBoundToTheDeadLetterExchangeByItsName() {
		Queue dlq = config.scansDeadLetterQueue("orthanc.scans.dlq", null);
		DirectExchange dlx = config.scansDeadLetterExchange("orthanc.scans.dlx");

		Binding binding = config.scansDeadLetterBinding(dlq, dlx);

		assertThat(dlq.isDurable()).isTrue();
		assertThat(dlq.getArguments()).doesNotContainKey("x-expires");
		assertThat(binding.getExchange()).isEqualTo("orthanc.scans.dlx");
		assertThat(binding.getDestination()).isEqualTo("orthanc.scans.dlq");
		assertThat(binding.getRoutingKey()).isEqualTo("orthanc.scans.dlq");
	}
}
