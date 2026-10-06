package com.spydrone.orthanc_scan_producer.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LotClientTest {

	private final RestClient.Builder builder = RestClient.builder().baseUrl("http://consumer");
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final LotClient client = new LotClient(builder);

	@Test
	void mapsLotFromConsumer() {
		server.expect(requestTo("http://consumer/api/lots/L1")).andRespond(withSuccess("""
				{"lotId":"L1","currentStage":"intake","wipLocation":"INTAKE-001",
				 "status":"active","onHold":true,"updatedAt":"2026-10-06T12:00:00Z"}
				""", MediaType.APPLICATION_JSON));

		assertThat(client.find("L1")).contains(new LotState("intake", true, "active"));
		server.verify();
	}

	@Test
	void unknownLotIsEmpty() {
		server.expect(requestTo("http://consumer/api/lots/nope")).andRespond(withResourceNotFound());

		assertThat(client.find("nope")).isEmpty();
	}

	@Test
	void consumerErrorIsEmpty() {
		server.expect(requestTo("http://consumer/api/lots/L1")).andRespond(withServerError());

		assertThat(client.find("L1")).isEmpty();
	}
}
