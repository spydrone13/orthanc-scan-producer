package com.spydrone.orthanc_scan_producer.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.spydrone.orthanc_scan_producer.ConsumerCircuitBreakerConfig;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class LotClientTest {

	private static final String URL = "http://consumer/api/lots/L1";
	private static final String LOT = """
			{"lotId":"L1","currentStage":"intake","wipLocation":"INTAKE-001",
			 "status":"active","onHold":true,"updatedAt":"2026-10-06T12:00:00Z",
			 "lastScan":{"clientId":"c1","userName":"jsmith","at":"2026-10-06T11:59:00Z"}}
			""";

	private final RestClient.Builder builder = RestClient.builder().baseUrl("http://consumer");
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	/** Opens after 2 lookups with at least one failure; lets 1 probe through when half-open. */
	private final CircuitBreaker breaker = ConsumerCircuitBreakerConfig.breaker("test",
			new ConsumerCircuitBreakerConfig.Settings(50, 2, 2, Duration.ofMinutes(1), 1));
	private final LotClient client = new LotClient(builder, breaker);

	@Test
	void mapsLotFromConsumer() {
		server.expect(requestTo(URL)).andRespond(withSuccess(LOT, MediaType.APPLICATION_JSON));

		assertThat(client.find("L1")).contains(new LotState("intake", "INTAKE-001", true, "active",
				new LotState.LastScan("c1", "jsmith", Instant.parse("2026-10-06T11:59:00Z"))));
		server.verify();
	}

	@Test
	void unknownLotIsEmpty() {
		server.expect(requestTo("http://consumer/api/lots/nope")).andRespond(withResourceNotFound());

		assertThat(client.find("nope")).isEmpty();
	}

	@Test
	void consumerErrorIsEmpty() {
		server.expect(requestTo(URL)).andRespond(withServerError());

		assertThat(client.find("L1")).isEmpty();
	}

	@Test
	void repeatedFailuresOpenTheCircuitAndStopAskingTheConsumer() {
		server.expect(twice(), requestTo(URL)).andRespond(withServerError());

		for (int i = 0; i < 4; i++) {
			assertThat(client.find("L1")).isEmpty();
		}

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
		server.verify();
	}

	@Test
	void clientErrorsDontOpenTheCircuit() {
		server.expect(times(3), requestTo(URL)).andRespond(withBadRequest());

		for (int i = 0; i < 3; i++) {
			assertThat(client.find("L1")).isEmpty();
		}

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		server.verify();
	}

	@Test
	void unknownLotsDontOpenTheCircuit() {
		server.expect(times(3), requestTo(URL)).andRespond(withResourceNotFound());

		for (int i = 0; i < 3; i++) {
			assertThat(client.find("L1")).isEmpty();
		}

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void circuitClosesAgainOnceTheConsumerAnswers() {
		server.expect(twice(), requestTo(URL)).andRespond(withServerError());
		server.expect(requestTo(URL)).andRespond(withSuccess(LOT, MediaType.APPLICATION_JSON));
		client.find("L1");
		client.find("L1");
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

		breaker.transitionToHalfOpenState();

		assertThat(client.find("L1")).isPresent();
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		server.verify();
	}
}
