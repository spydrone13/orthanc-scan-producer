package com.spydrone.orthanc_scan_producer.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LotStageClientTest {

	private static final String STAGES = "{\"intake\":{\"description\":\"Intake\"}}";
	private static final String URL = "http://consumer/api/lot-stages";

	@TempDir
	private Path dir;

	private final RestClient.Builder builder = RestClient.builder().baseUrl("http://consumer");
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private LotStageClient client(Path cacheFile) {
		return new LotStageClient(builder, cacheFile);
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
