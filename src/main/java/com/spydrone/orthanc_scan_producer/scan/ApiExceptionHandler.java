package com.spydrone.orthanc_scan_producer.scan;

import java.util.Map;

import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	/** The scan was not recorded; a 503 tells the UI to keep it and retry. */
	@ExceptionHandler(AmqpException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	Map<String, String> brokerUnavailable(AmqpException ex) {
		return Map.of("message", "Scan service temporarily unavailable");
	}
}
