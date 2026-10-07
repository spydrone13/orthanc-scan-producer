package com.spydrone.orthanc_scan_producer.stage;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LotStageController.class)
class LotStageControllerTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private LotStageClient lotStageClient;

	@Test
	void passesTheCatalogThrough() throws Exception {
		given(lotStageClient.stages()).willReturn(Optional.of("{\"intake\":{\"description\":\"Intake\"}}"));

		mvc.perform(get("/api/lot-stages"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(content().json("{\"intake\":{\"description\":\"Intake\"}}"));
	}

	@Test
	void unavailableWithNoCatalog() throws Exception {
		given(lotStageClient.stages()).willReturn(Optional.empty());

		mvc.perform(get("/api/lot-stages")).andExpect(status().isServiceUnavailable());
	}
}
