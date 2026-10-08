package com.spydrone.orthanc_scan_producer.scan;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

	@Bean
	DirectExchange scansExchange(@Value("${app.scans.exchange}") String name) {
		return new DirectExchange(name);
	}

	/**
	 * Must match the consumer's declaration, dead-letter arguments included, or whichever app starts
	 * second fails to declare it.
	 */
	@Bean
	Queue scansQueue(@Value("${app.scans.queue}") String name,
			@Value("${app.scans.dead-letter-exchange}") String deadLetterExchange,
			@Value("${app.scans.dead-letter-queue}") String deadLetterQueue) {
		return QueueBuilder.durable(name)
				.deadLetterExchange(deadLetterExchange)
				.deadLetterRoutingKey(deadLetterQueue)
				.build();
	}

	@Bean
	Binding scansBinding(Queue scansQueue, DirectExchange scansExchange,
			@Value("${app.scans.routing-key}") String routingKey) {
		return BindingBuilder.bind(scansQueue).to(scansExchange).with(routingKey);
	}

	@Bean
	DirectExchange scansDeadLetterExchange(@Value("${app.scans.dead-letter-exchange}") String name) {
		return new DirectExchange(name);
	}

	@Bean
	Queue scansDeadLetterQueue(@Value("${app.scans.dead-letter-queue}") String name) {
		return QueueBuilder.durable(name).build();
	}

	@Bean
	Binding scansDeadLetterBinding(Queue scansDeadLetterQueue, DirectExchange scansDeadLetterExchange) {
		return BindingBuilder.bind(scansDeadLetterQueue).to(scansDeadLetterExchange).with(scansDeadLetterQueue.getName());
	}

	@Bean
	MessageConverter jsonMessageConverter() {
		return new JacksonJsonMessageConverter();
	}
}
