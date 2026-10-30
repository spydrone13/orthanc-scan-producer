package com.spydrone.orthanc_scan_producer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The health endpoints, with RabbitMQ pointed at a closed port so it's down whether or not a broker runs
 * locally. Neither RabbitMQ nor the consumer is needed.
 */
@SpringBootTest(properties = "spring.rabbitmq.port=1")
@AutoConfigureMockMvc
class HealthEndpointTest {

	@Autowired
	private MockMvc mvc;

	@Test
	void healthShowsRabbitMqAndTheCircuits() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(jsonPath("$.components.rabbit.status").value("DOWN"))
				.andExpect(jsonPath("$.components.rabbit.details.error").exists())
				.andExpect(jsonPath("$.components.consumerCircuits.details.lot-lookup.state").value("CLOSED"));
	}

	@Test
	void circuitsGroupShowsOnlyTheCircuitsAndStaysUp() throws Exception {
		mvc.perform(get("/actuator/health/circuits"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components.rabbit").doesNotExist())
				.andExpect(jsonPath("$.components.consumerCircuits.details.lot-lookup.state").value("CLOSED"))
				.andExpect(jsonPath("$.components.consumerCircuits.details.lot-stages.state").value("CLOSED"));
	}
}
