package com.spydrone.orthanc_scan_producer.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What a browser does: pick up the XSRF-TOKEN cookie from any response, even a 401, then echo it in the
 * X-XSRF-TOKEN header. Never uses spring-security-test's csrf(), which swaps the cookie repository of the
 * cached context for one that writes no cookie; the distinct username keeps this context separate.
 */
@SpringBootTest(properties = {
		"spring.rabbitmq.port=1",
		"app.security.users[0].username=bob",
		"app.security.users[0].password={noop}secret" })
@AutoConfigureMockMvc
class CsrfCookieFlowTest {

	private static final String LOGIN = """
			{"username":"bob","password":"secret"}""";

	@Autowired
	private MockMvc mvc;

	@Test
	void loggedOutUserGetsACookieTheyCanLogInWith() throws Exception {
		Cookie token = mvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(cookie().exists("XSRF-TOKEN"))
				.andExpect(cookie().httpOnly("XSRF-TOKEN", false))
				.andReturn().getResponse().getCookie("XSRF-TOKEN");

		MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login").cookie(token)
						.header("X-XSRF-TOKEN", token.getValue())
						.contentType(MediaType.APPLICATION_JSON).content(LOGIN))
				.andExpect(status().isOk())
				.andReturn().getRequest().getSession(false);

		mvc.perform(get("/api/auth/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("bob"));
	}

	@Test
	void aWrongTokenIsRefused() throws Exception {
		mvc.perform(post("/api/auth/login").cookie(new Cookie("XSRF-TOKEN", "a"))
						.header("X-XSRF-TOKEN", "b")
						.contentType(MediaType.APPLICATION_JSON).content(LOGIN))
				.andExpect(status().isForbidden());
	}
}
