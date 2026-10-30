package com.spydrone.orthanc_scan_producer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The circuits health group is exposed with details, without needing RabbitMQ or the consumer. */
@SpringBootTest
@AutoConfigureMockMvc
class CircuitsHealthEndpointTest {

	@Autowired
	private MockMvc mvc;

	@Test
	void showsEachCircuitsState() throws Exception {
		mvc.perform(get("/actuator/health/circuits"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components.consumerCircuits.details.lot-lookup.state").value("CLOSED"))
				.andExpect(jsonPath("$.components.consumerCircuits.details.lot-stages.state").value("CLOSED"));
	}
}
