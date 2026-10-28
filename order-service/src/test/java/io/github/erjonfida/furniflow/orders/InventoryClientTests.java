package io.github.erjonfida.furniflow.orders;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.ErrorResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The HTTP contract with inventory-service, against a fake server. */
@RestClientTest(components = InventoryClient.class, properties = "furniflow.inventory-url=http://inventory")
class InventoryClientTests {

	@Autowired
	InventoryClient inventory;

	@Autowired
	MockRestServiceServer server;

	@Test
	void reserveSendsTheRequestAndReadsTheReservation() {
		UUID id = UUID.randomUUID();
		server.expect(requestTo("http://inventory/api/inventory/reservations"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().json("{\"sku\": \"SOFA-1\", \"quantity\": 2}"))
			.andRespond(withSuccess("""
					{"id": "%s", "sku": "SOFA-1", "quantity": 2, "status": "ACTIVE", "expiresAt": "2026-10-27T10:15:00Z"}
					""".formatted(id), MediaType.APPLICATION_JSON));

		assertThat(inventory.reserve("SOFA-1", 2))
			.isEqualTo(new InventoryClient.Reservation(id, Instant.parse("2026-10-27T10:15:00Z")));
	}

	@Test
	void inventoryRefusalsArePassedOn() {
		server.expect(requestTo("http://inventory/api/inventory/reservations"))
			.andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.body("{\"status\": 409, \"detail\": \"Not enough stock of SOFA-1 to reserve 5\"}"));

		assertThatThrownBy(() -> inventory.reserve("SOFA-1", 5))
			.isInstanceOfSatisfying(ErrorResponseException.class, e -> {
				assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(e.getBody().getDetail()).isEqualTo("Not enough stock of SOFA-1 to reserve 5");
			});
	}

}
