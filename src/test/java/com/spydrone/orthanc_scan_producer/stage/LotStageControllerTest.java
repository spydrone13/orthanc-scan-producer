package com.spydrone.orthanc_scan_producer.stage;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.spydrone.orthanc_scan_producer.scan.WebConfig;
import com.spydrone.orthanc_scan_producer.security.SecurityConfig;

@WebMvcTest(LotStageController.class)
@Import({SecurityConfig.class, WebConfig.class})
@WithMockUser
class LotStageControllerTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private StageCatalog stageCatalog;

	@Test
	void passesTheCatalogThrough() throws Exception {
		given(stageCatalog.json()).willReturn(Optional.of("{\"intake\":{\"description\":\"Intake\"}}"));

		mvc.perform(get("/api/lot-stages"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(content().json("{\"intake\":{\"description\":\"Intake\"}}"));
	}

	@Test
	void unavailableWithNoCatalog() throws Exception {
		given(stageCatalog.json()).willReturn(Optional.empty());

		mvc.perform(get("/api/lot-stages")).andExpect(status().isServiceUnavailable());
	}

	@Test
	@WithAnonymousUser
	void requiresLogin() throws Exception {
		mvc.perform(get("/api/lot-stages")).andExpect(status().isUnauthorized());
		verifyNoInteractions(stageCatalog);
	}
}
