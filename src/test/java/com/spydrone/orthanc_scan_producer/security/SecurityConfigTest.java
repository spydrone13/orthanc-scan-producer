package com.spydrone.orthanc_scan_producer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What stays open without a login, with no users configured (the app still starts, nobody can log in).
 * Neither RabbitMQ nor the consumer is needed.
 */
@SpringBootTest(properties = "spring.rabbitmq.port=1")
@AutoConfigureMockMvc
class SecurityConfigTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SecurityUsersProperties users;

	@Test
	void startsWithNoUsers() {
		assertThat(users.users()).isEmpty();
	}

	@Test
	void healthNeedsNoLogin() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
		mvc.perform(get("/actuator/health/circuits")).andExpect(status().isOk());
	}

	@Test
	void uiShellNeedsNoLogin() throws Exception {
		for (String path : new String[] { "/", "/index.html", "/scan-lot", "/scan-lot/S1/scan", "/admin" }) {
			int status = mvc.perform(get(path)).andReturn().getResponse().getStatus();
			assertThat(status).as(path).isNotIn(401, 403);
		}
	}

	@Test
	void apiNeedsLogin() throws Exception {
		mvc.perform(get("/api/lot-stages")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/anything-else")).andExpect(status().isUnauthorized());
	}

	@Test
	void corsPreflightFromTheDevServerAllowsCredentials() throws Exception {
		mvc.perform(options("/api/scans")
						.header(HttpHeaders.ORIGIN, "http://localhost:4200")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type,Idempotency-Key,X-XSRF-TOKEN"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
	}

	@Test
	void corsPreflightFromAnotherOriginIsRefused() throws Exception {
		mvc.perform(options("/api/scans")
						.header(HttpHeaders.ORIGIN, "http://evil.example")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isForbidden());
	}
}
