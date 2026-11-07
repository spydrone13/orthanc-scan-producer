package com.spydrone.orthanc_scan_producer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** The session login, end to end through the security filters. Neither RabbitMQ nor the consumer is needed. */
@SpringBootTest(properties = {
		"spring.rabbitmq.port=1",
		"app.security.users[0].username=alice",
		"app.security.users[0].password={noop}secret" })
@AutoConfigureMockMvc
class AuthControllerTest {

	private static final String LOGIN = """
			{"username":"alice","password":"secret"}""";

	@Autowired
	private MockMvc mvc;

	private MockHttpSession login() throws Exception {
		return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(LOGIN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("alice"))
				.andReturn().getRequest().getSession(false);
	}

	@Test
	void loginStartsASession() throws Exception {
		MockHttpSession session = login();

		mvc.perform(get("/api/auth/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("alice"));
	}

	@Test
	void loginChangesAnExistingSessionId() throws Exception {
		MockHttpSession before = new MockHttpSession();
		String idBefore = before.getId();

		MockHttpSession after = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).session(before)
						.contentType(MediaType.APPLICATION_JSON).content(LOGIN))
				.andExpect(status().isOk())
				.andReturn().getRequest().getSession(false);

		assertThat(after.getId()).isNotEqualTo(idBefore);
	}

	@Test
	void wrongPasswordIsRejected() throws Exception {
		mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
						.content(LOGIN.replace("secret", "nope")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid username or password"));
	}

	@Test
	void unknownUserIsRejected() throws Exception {
		mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
						.content(LOGIN.replace("alice", "mallory")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid username or password"));
	}

	@Test
	void blankFieldsAreBadRequests() throws Exception {
		mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"\",\"password\":\"\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void loginNeedsACsrfToken() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
				.andExpect(status().isForbidden());
	}

	@Test
	void meWithoutASessionIsUnauthorized() throws Exception {
		mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutEndsTheSession() throws Exception {
		MockHttpSession session = login();

		mvc.perform(post("/api/auth/logout").with(csrf()).session(session))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void loginRequestHidesThePassword() {
		assertThat(new LoginRequest("alice", "secret").toString()).doesNotContain("secret");
		assertThat(new SecurityUsersProperties.User("alice", "secret").toString()).doesNotContain("secret");
	}
}
