package com.spydrone.orthanc_scan_producer.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.spydrone.orthanc_scan_producer.ConsumerCircuitBreakerConfig;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class LotStageClientTest {

	private static final String STAGES = "{\"intake\":{\"description\":\"Intake\"}}";
	private static final String URL = "http://consumer/api/lot-stages";

	@TempDir
	private Path dir;

	private final RestClient.Builder builder = RestClient.builder().baseUrl("http://consumer");
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	/** Opens after 2 fetches with at least one failure; lets 1 probe through when half-open. */
	private final CircuitBreaker breaker = ConsumerCircuitBreakerConfig.breaker("test",
			new ConsumerCircuitBreakerConfig.Settings(50, 2, 2, Duration.ofMinutes(1), 1));

	private LotStageClient client(Path cacheFile) {
		return new LotStageClient(builder, cacheFile, breaker);
	}

	@Test
	void repeatedFailuresOpenTheCircuitAndServeTheSavedCatalogWithoutAsking() throws Exception {
		Path cacheFile = dir.resolve("lot-stages.json");
		Files.writeString(cacheFile, STAGES);
		server.expect(twice(), requestTo(URL)).andRespond(withServerError());
		LotStageClient client = client(cacheFile);

		for (int i = 0; i < 4; i++) {
			assertThat(client.stages()).contains(STAGES);
		}

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
		server.verify();
	}

	@Test
	void clientErrorsDontOpenTheCircuit() {
		server.expect(times(3), requestTo(URL)).andRespond(withBadRequest());
		LotStageClient client = client(dir.resolve("lot-stages.json"));

		for (int i = 0; i < 3; i++) {
			assertThat(client.stages()).isEmpty();
		}

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		server.verify();
	}

	@Test
	void circuitClosesAgainOnceTheConsumerAnswers() throws Exception {
		Path cacheFile = dir.resolve("lot-stages.json");
		server.expect(twice(), requestTo(URL)).andRespond(withServerError());
		server.expect(requestTo(URL)).andRespond(withSuccess(STAGES, MediaType.APPLICATION_JSON));
		LotStageClient client = client(cacheFile);
		client.stages();
		client.stages();
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

		breaker.transitionToHalfOpenState();

		assertThat(client.stages()).contains(STAGES);
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(Files.readString(cacheFile)).isEqualTo(STAGES);
	}

	@Test
	void returnsAndSavesTheConsumersCatalog() throws Exception {
		Path cacheFile = dir.resolve("data/lot-stages.json");
		server.expect(requestTo(URL)).andRespond(withSuccess(STAGES, MediaType.APPLICATION_JSON));

		assertThat(client(cacheFile).stages()).contains(STAGES);
		assertThat(Files.readString(cacheFile)).isEqualTo(STAGES);
	}

	@Test
	void servesTheLastCatalogWhileTheConsumerIsDown() {
		LotStageClient client = client(dir.resolve("lot-stages.json"));
		server.expect(requestTo(URL)).andRespond(withSuccess(STAGES, MediaType.APPLICATION_JSON));
		server.expect(requestTo(URL)).andRespond(withServerError());
		client.stages();

		assertThat(client.stages()).contains(STAGES);
	}

	@Test
	void servesTheSavedCatalogAfterARestart() throws Exception {
		Path cacheFile = dir.resolve("lot-stages.json");
		Files.writeString(cacheFile, STAGES);
		server.expect(requestTo(URL)).andRespond(withServerError());

		assertThat(client(cacheFile).stages()).contains(STAGES);
	}

	@Test
	void emptyWhenTheConsumerIsDownAndNothingWasSaved() {
		server.expect(twice(), requestTo(URL)).andRespond(withServerError());
		LotStageClient client = client(dir.resolve("lot-stages.json"));

		assertThat(client.stages()).isEmpty();
		assertThat(client.stages()).isEmpty();
	}
}
