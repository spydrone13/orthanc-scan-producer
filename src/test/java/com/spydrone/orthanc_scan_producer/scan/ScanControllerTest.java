package com.spydrone.orthanc_scan_producer.scan;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ScanController.class)
class ScanControllerTest {

	private static final String BODY = """
			{"clientId":"abc","userName":"u","currentStage":"S1","lotId":"L1",
			 "destination":"S2","scanType":"transitional","note":""}
			""";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private ScanService scanService;

	@Test
	void acceptsScan() throws Exception {
		given(scanService.submit(any())).willAnswer(inv -> ScanResponse.accepted(inv.getArgument(0)));

		mvc.perform(post("/api/scans").contentType(MediaType.APPLICATION_JSON)
						.header("Idempotency-Key", "abc").content(BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clientId").value("abc"))
				.andExpect(jsonPath("$.scanType").value("transitional"))
				.andExpect(jsonPath("$.errorCode").doesNotExist());
	}

	@Test
	void rejectsMissingField() throws Exception {
		mvc.perform(post("/api/scans").contentType(MediaType.APPLICATION_JSON)
						.content(BODY.replace("\"lotId\":\"L1\",", "")))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(scanService);
	}

	@Test
	void rejectsMismatchedIdempotencyKey() throws Exception {
		mvc.perform(post("/api/scans").contentType(MediaType.APPLICATION_JSON)
						.header("Idempotency-Key", "other").content(BODY))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(scanService);
	}

	@Test
	void returns503WhenBrokerDown() throws Exception {
		given(scanService.submit(any())).willThrow(new AmqpConnectException(new RuntimeException("down")));

		mvc.perform(post("/api/scans").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").exists());
	}
}
