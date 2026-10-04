package com.spydrone.orthanc_scan_producer.scan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ScanService {

	private final RabbitTemplate rabbitTemplate;
	private final String exchange;
	private final String routingKey;

	/**
	 * Accepted scans by clientId, so repeat sends get the original response instead of a second publish.
	 * In-memory and per instance; replace with a shared store (Redis, DB) when running more than one.
	 */
	private final Map<String, ScanResponse> accepted = new ConcurrentHashMap<>();

	public ScanService(RabbitTemplate rabbitTemplate,
			@Value("${app.scans.exchange}") String exchange,
			@Value("${app.scans.routing-key}") String routingKey) {
		this.rabbitTemplate = rabbitTemplate;
		this.exchange = exchange;
		this.routingKey = routingKey;
	}

	/** Publishes a new scan. Throws AmqpException if the broker is unreachable, leaving the scan resendable. */
	public ScanResponse submit(ScanRecord record) {
		ScanResponse existing = accepted.get(record.clientId());
		if (existing != null) {
			return existing;
		}
		rabbitTemplate.convertAndSend(exchange, routingKey, record);
		ScanResponse response = ScanResponse.accepted(record);
		ScanResponse raced = accepted.putIfAbsent(record.clientId(), response);
		return raced != null ? raced : response;
	}
}
