package io.github.erjonfida.furniflow.orders;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.ProblemDetail;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import io.github.erjonfida.furniflow.orders.OrderService.Item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderIntegrationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	OrderService orders;

	@Autowired
	ConsumerFactory<String, String> consumers;

	@Autowired
	JwtAuthenticationConverter tokens;

	@MockitoBean
	CatalogClient catalog;

	@MockitoBean
	InventoryClient inventory;

	// The tests share one database, so each test (a new instance) gets its own key
	final String key = UUID.randomUUID().toString();

	@BeforeEach
	void catalogKnowsEveryProduct() {
		when(catalog.product(any())).thenReturn(new CatalogClient.Product("A product", BigDecimal.TEN));
	}

	@Test
	void placeRetryAndPayOverHttp() throws Exception {
		InventoryClient.Reservation sofas = hold(Duration.ofMinutes(15));
		InventoryClient.Reservation chairs = hold(Duration.ofMinutes(15));
		when(inventory.reserve("SOFA-1", 2)).thenReturn(sofas);
		when(inventory.reserve("CHAIR-1", 4)).thenReturn(chairs);
		when(catalog.product("SOFA-1")).thenReturn(new CatalogClient.Product("Oslo sofa", new BigDecimal("1299.90")));
		when(catalog.product("CHAIR-1")).thenReturn(new CatalogClient.Product("Oak chair", new BigDecimal("149.50")));
		String[] cart = { line("SOFA-1", 2), line("CHAIR-1", 4) };

		String placed = mvc.perform(order("alice", key, cart))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("RESERVED"))
			.andExpect(jsonPath("$.items[1].sku").value("CHAIR-1"))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(placed, "$.id");

		// The client retries (say its connection dropped): the same order comes back, nothing is reserved twice
		mvc.perform(order("alice", key, cart))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(id));
		// The same key with another cart: answering with the first order would quietly drop the change
		mvc.perform(order("alice", key, line("SOFA-1", 2)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value(containsString("other items")));
		verify(inventory, times(1)).reserve("SOFA-1", 2);

		// The name and prices the order was placed with, read back from the database, even after the catalog changes them
		when(catalog.product("SOFA-1")).thenReturn(new CatalogClient.Product("Renamed sofa", new BigDecimal("999.00")));
		mvc.perform(get("/api/orders/{id}", id).with(as("alice", "customer")))
			.andExpect(jsonPath("$.items[0].name").value("Oslo sofa"))
			.andExpect(jsonPath("$.items[0].unitPrice").value(1299.90))
			.andExpect(jsonPath("$.total").value(3197.80)); // 2 × 1299.90 + 4 × 149.50

		mvc.perform(post("/api/orders/{id}/pay", id).with(as("alice", "customer")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PAID"));
		assertThat(paidEventFor(id)).contains(sofas.id().toString(), chairs.id().toString());
	}

	@Test
	void customersOnlyReachTheirOwnOrders() throws Exception {
		when(inventory.reserve(any(), anyInt())).thenAnswer(call -> hold(Duration.ofMinutes(15)));
		String alices = JsonPath.read(
				mvc.perform(place("alice", key, "SOFA-1", 1)).andReturn().getResponse().getContentAsString(), "$.id");

		// Bob happens to send the same idempotency key: he gets an order of his own, not Alice's
		mvc.perform(place("bob", key, "SOFA-1", 1)).andExpect(jsonPath("$.id").value(not(alices)));
		mvc.perform(get("/api/orders").with(as("bob", "customer")))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(not(alices)));
		mvc.perform(get("/api/orders/{id}", alices).with(as("bob", "customer"))).andExpect(status().isNotFound());
		mvc.perform(post("/api/orders/{id}/pay", alices).with(as("bob", "customer")))
			.andExpect(status().isNotFound());

		mvc.perform(get("/api/orders/{id}", alices)).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()); // Kubernetes probes need no token
		mvc.perform(get("/api/orders/{id}", alices).with(as("ada", "admin"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/orders/{id}", alices).with(as("alice", "customer"))).andExpect(status().isOk());
	}

	@Test
	void adminsSeeEveryOrderAndWhoPlacedIt() throws Exception {
		when(inventory.reserve(any(), anyInt())).thenAnswer(call -> hold(Duration.ofMinutes(15)));
		String sku = "SOFA-" + key; // only this test's orders
		mvc.perform(place("alice", key, sku, 1)).andExpect(status().isCreated());
		mvc.perform(place("carol", key, sku, 2)).andExpect(status().isCreated());

		mvc.perform(get("/api/orders/all").with(as("alice", "customer"))).andExpect(status().isForbidden());
		// Usernames, not the IDs in the tokens' subjects
		mvc.perform(get("/api/orders/all").with(as("ada", "admin")))
			.andExpect(jsonPath("$[?(@.items[0].sku == '%s')].customer", sku).value(containsInAnyOrder("alice", "carol")));
	}

	@Test
	void customersCancelOrdersUntilTheyPay() throws Exception {
		InventoryClient.Reservation hold = hold(Duration.ofMinutes(15));
		when(inventory.reserve(any(), anyInt())).thenReturn(hold).thenAnswer(call -> hold(Duration.ofMinutes(15)));
		String id = JsonPath.read(
				mvc.perform(place("alice", key, "SOFA-1", 1)).andReturn().getResponse().getContentAsString(), "$.id");

		mvc.perform(post("/api/orders/{id}/cancel", id).with(as("bob", "customer"))).andExpect(status().isNotFound());
		// Inventory can't take the units back right now: the order is cancelled anyway, and the hold runs out by itself
		doThrow(new ResourceAccessException("inventory is down")).when(inventory).release(hold.id());
		mvc.perform(post("/api/orders/{id}/cancel", id).with(as("alice", "customer")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
		verify(inventory).release(hold.id());
		mvc.perform(post("/api/orders/{id}/pay", id).with(as("alice", "customer")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(containsString("cancelled")));

		String paid = JsonPath.read(mvc.perform(place("alice", key + "-2", "SOFA-1", 1))
			.andReturn().getResponse().getContentAsString(), "$.id");
		mvc.perform(post("/api/orders/{id}/pay", paid).with(as("alice", "customer"))).andExpect(status().isOk());
		mvc.perform(post("/api/orders/{id}/cancel", paid).with(as("alice", "customer")))
			.andExpect(status().isConflict());
	}

	@Test
	void concurrentRetriesCreateOneOrder() throws Exception {
		when(inventory.reserve(any(), anyInt())).thenAnswer(call -> hold(Duration.ofMinutes(15)));
		Set<UUID> answers = ConcurrentHashMap.newKeySet();

		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> retries = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				retries.add(pool.submit(() -> answers.add(orders.place("alice", "alice", key, List.of(new Item("SOFA-1", 1))).getId())));
			}
			for (Future<?> retry : retries) {
				retry.get(); // rethrows anything unexpected
			}
		}

		assertThat(answers).hasSize(1);
		// Every request that lost the race gave its units back, so exactly one hold is left
		assertThat(calls("reserve") - calls("release")).isOne();
	}

	@Test
	void customersHoldOnlySoManyUnpaidUnitsEvenInParallel() throws Exception {
		when(inventory.reserve(any(), anyInt())).thenAnswer(call -> hold(Duration.ofMinutes(15)));
		Map<String, Future<Order>> attempts = new HashMap<>();

		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < 20; i++) {
				String attemptKey = key + "-" + i;
				attempts.put(attemptKey, pool.submit(() -> orders.place("dave", "dave", attemptKey, List.of(new Item("SOFA-1", 1)))));
			}
		}
		List<String> placed = new ArrayList<>();
		for (var attempt : attempts.entrySet()) {
			try {
				attempt.getValue().get();
				placed.add(attempt.getKey());
			}
			catch (ExecutionException refused) {
				assertThat(refused.getCause()).isInstanceOf(ResponseStatusException.class).hasMessageContaining("unpaid");
			}
		}

		assertThat(placed).hasSize(OrderService.MAX_UNPAID_UNITS);
		assertThat(calls("reserve") - calls("release")).isEqualTo(OrderService.MAX_UNPAID_UNITS); // refused ones gave back
		// A retry of an order that's already held still gets it back, although the customer is at the cap
		mvc.perform(place("dave", placed.getFirst(), "SOFA-1", 1)).andExpect(status().isCreated());
	}

	@Test
	void errorsComeBackAsProblemDetails() throws Exception {
		mvc.perform(post("/api/orders").with(as("alice", "customer"))
			.contentType(APPLICATION_JSON)
			.content("{\"items\": [" + line("SOFA-1", 1) + "]}")).andExpect(status().isBadRequest()); // no Idempotency-Key
		mvc.perform(order("alice", key)).andExpect(status().isBadRequest()); // an empty cart

		when(inventory.reserve("SOFA-1", 9)).thenThrow(new ErrorResponseException(CONFLICT,
				ProblemDetail.forStatusAndDetail(CONFLICT, "Not enough stock of SOFA-1 to reserve 9"), null));
		mvc.perform(place("alice", key, "SOFA-1", 9))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Not enough stock of SOFA-1 to reserve 9"));

		// The refused attempt left no order behind, so the same key can be used again
		when(inventory.reserve("SOFA-1", 1)).thenReturn(hold(Duration.ofSeconds(-1)));
		String late = mvc.perform(place("alice", key, "SOFA-1", 1)).andReturn().getResponse().getContentAsString();
		mvc.perform(post("/api/orders/{id}/pay", JsonPath.<String>read(late, "$.id")).with(as("alice", "customer")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(containsString("hold on this order ran out")));

		mvc.perform(post("/api/orders/{id}/pay", UUID.randomUUID()).with(as("alice", "customer")))
			.andExpect(status().isNotFound());
	}

	private String paidEventFor(String orderId) {
		Properties fromTheStart = new Properties();
		fromTheStart.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		try (Consumer<String, String> consumer = consumers.createConsumer("test-" + orderId, null, null, fromTheStart)) {
			consumer.subscribe(List.of(OrderService.ORDER_PAID));
			return await().atMost(Duration.ofSeconds(30))
				.pollInSameThread() // a Kafka consumer must not be shared between threads
				.until(() -> StreamSupport.stream(consumer.poll(Duration.ofMillis(200)).spliterator(), false)
					.filter(event -> event.key().equals(orderId))
					.map(ConsumerRecord::value)
					.findFirst(), Optional::isPresent)
				.orElseThrow();
		}
	}

	private long calls(String method) {
		return mockingDetails(inventory).getInvocations()
			.stream()
			.filter(call -> call.getMethod().getName().equals(method))
			.count();
	}

	// Authorities come from the app's own converter (application.yml), so a broken role mapping fails these tests too.
	// As in Keycloak, the subject is the user's ID and the username is a separate claim.
	private RequestPostProcessor as(String user, String role) {
		return jwt()
			.jwt(token -> token.subject("id-of-" + user)
				.claim("preferred_username", user)
				.claim("realm_access", Map.of("roles", List.of(role))))
			.authorities(token -> tokens.convert(token).getAuthorities());
	}

	private MockHttpServletRequestBuilder place(String customer, String key, String sku, int quantity) {
		return order(customer, key, line(sku, quantity));
	}

	private MockHttpServletRequestBuilder order(String customer, String key, String... lines) {
		return post("/api/orders").with(as(customer, "customer"))
			.header("Idempotency-Key", key)
			.contentType(APPLICATION_JSON)
			.content("{\"items\": [" + String.join(", ", lines) + "]}");
	}

	private static InventoryClient.Reservation hold(Duration left) {
		return new InventoryClient.Reservation(UUID.randomUUID(), Instant.now().plus(left));
	}

	private static String line(String sku, int quantity) {
		return "{\"sku\": \"%s\", \"quantity\": %d}".formatted(sku, quantity);
	}

}
