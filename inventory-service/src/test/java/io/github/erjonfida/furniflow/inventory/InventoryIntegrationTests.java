package io.github.erjonfida.furniflow.inventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

//Runs against a real PostgreSQL and Kafka in Docker (Testcontainers), with the Flyway schema applied
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InventoryIntegrationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	InventoryService inventory;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	JwtAuthenticationConverter tokens;

	// The tests share one database, so each test (a new instance) gets its own SKU
	final String sku = "SOFA-" + UUID.randomUUID();

	@Test
	void reserveAndReleaseOverHttp() throws Exception {
		mvc.perform(restock(sku, 5).with(as("admin"))).andExpect(status().isNoContent());

		String created = mvc.perform(reserve(sku, 3).with(as("reserve-stock")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andReturn().getResponse().getContentAsString();
		mvc.perform(get("/api/inventory/stock/{sku}", sku)).andExpect(jsonPath("$.available").value(2));

		String id = JsonPath.read(created, "$.id");
		mvc.perform(delete("/api/inventory/reservations/{id}", id).with(as("reserve-stock")))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/inventory/stock/{sku}", sku)).andExpect(jsonPath("$.available").value(5));
	}

	@Test
	void eachEndpointNeedsItsRole() throws Exception {
		inventory.restock(sku, 5);

		mvc.perform(get("/api/inventory/stock/{sku}", sku)).andExpect(status().isOk()); // anyone may look
		mvc.perform(restock(sku, 1)).andExpect(status().isUnauthorized());
		mvc.perform(restock(sku, 1).with(as("customer"))).andExpect(status().isForbidden());
		// Not even an admin holds stock directly: only order-service does, while placing an order
		mvc.perform(reserve(sku, 1).with(as("customer"))).andExpect(status().isForbidden());
		mvc.perform(reserve(sku, 1).with(as("admin"))).andExpect(status().isForbidden());

		assertThat(inventory.getStock(sku).getAvailable()).isEqualTo(5);
	}

	@Test
	void errorsComeBackAsProblemDetails() throws Exception {
		inventory.restock(sku, 1);

		mvc.perform(reserve(sku, 2).with(as("reserve-stock")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(containsString("Not enough stock")));
		mvc.perform(reserve("NO-SUCH-SKU", 1).with(as("reserve-stock"))).andExpect(status().isNotFound());
		mvc.perform(reserve(sku, 0).with(as("reserve-stock"))).andExpect(status().isBadRequest());
	}

	@Test
	void concurrentReservationsNeverOversell() throws Exception {
		inventory.restock(sku, 50);
		AtomicInteger reserved = new AtomicInteger();

		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> buyers = new ArrayList<>();
			for (int i = 0; i < 200; i++) {
				buyers.add(pool.submit(() -> {
					try {
						inventory.reserve(sku, 1);
						reserved.incrementAndGet();
					}
					catch (InsufficientStockException soldOut) {
						// expected for the last 150 buyers
					}
				}));
			}
			for (Future<?> buyer : buyers) {
				buyer.get(); // rethrows anything unexpected
			}
		}

		assertThat(reserved).hasValue(50);
		assertThat(inventory.getStock(sku).getAvailable()).isZero();
	}

	@Test
	void overdueReservationsExpireAndGiveStockBackOnce() {
		inventory.restock(sku, 5);
		UUID id = inventory.reserve(sku, 2).getId();
		jdbc.update("UPDATE reservation SET expires_at = now() - interval '1 minute' WHERE id = ?", id);

		inventory.expireOverdue();
		inventory.release(id); // a late release after expiry must not give the units back a second time

		assertThat(inventory.getStock(sku).getAvailable()).isEqualTo(5);
		assertThat(reservationStatus(id)).isEqualTo("EXPIRED");
	}

	@Test
	void anOrderPaidEventConfirmsTheReservation() {
		inventory.restock(sku, 5);
		UUID id = inventory.reserve(sku, 2).getId();

		kafka.send("order-paid", """
				{"orderId": "%s", "reservationId": "%s"}""".formatted(UUID.randomUUID(), id)).join();

		await().atMost(Duration.ofSeconds(30)).until(() -> reservationStatus(id), "CONFIRMED"::equals);
		inventory.release(id); // sold units can't be released any more
		assertThat(inventory.getStock(sku).getAvailable()).isEqualTo(3);
	}

	private String reservationStatus(UUID id) {
		return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id);
	}

	// Signed in with this Keycloak realm role. Its authorities come from the app's own converter (application.yml),
	// so a broken role mapping fails these tests too. Only the token's signature isn't checked.
	private RequestPostProcessor as(String role) {
		return jwt().jwt(token -> token.claim("realm_access", Map.of("roles", List.of(role))))
			.authorities(token -> tokens.convert(token).getAuthorities());
	}

	private static MockHttpServletRequestBuilder restock(String sku, int quantity) {
		return post("/api/inventory/stock/{sku}/restock", sku).contentType(APPLICATION_JSON)
			.content("{\"quantity\": %d}".formatted(quantity));
	}

	private static MockHttpServletRequestBuilder reserve(String sku, int quantity) {
		return post("/api/inventory/reservations").contentType(APPLICATION_JSON)
			.content("{\"sku\": \"%s\", \"quantity\": %d}".formatted(sku, quantity));
	}

}
