package io.github.erjonfida.furniflow.orders;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.ProblemDetail;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.ErrorResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL and Kafka in Docker (Testcontainers); inventory-service is replaced by a mock. */
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

	@MockitoBean
	InventoryClient inventory;

	// The tests share one database, so each test (a new instance) gets its own key
	final String key = UUID.randomUUID().toString();

	@Test
	void placeRetryAndPayOverHttp() throws Exception {
		InventoryClient.Reservation hold = hold(Duration.ofMinutes(15));
		when(inventory.reserve("SOFA-1", 2)).thenReturn(hold);

		String placed = mvc.perform(place(key, "SOFA-1", 2))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("RESERVED"))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(placed, "$.id");

		// The client retries (say its connection dropped): the same order comes back, nothing is reserved twice
		mvc.perform(place(key, "SOFA-1", 2)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
		verify(inventory, times(1)).reserve("SOFA-1", 2);

		mvc.perform(post("/api/orders/{id}/pay", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PAID"));
		assertThat(paidEventFor(id)).contains(hold.id().toString());
	}

	@Test
	void concurrentRetriesCreateOneOrder() throws Exception {
		when(inventory.reserve(any(), anyInt())).thenAnswer(call -> hold(Duration.ofMinutes(15)));
		Set<UUID> answers = ConcurrentHashMap.newKeySet();

		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> retries = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				retries.add(pool.submit(() -> answers.add(orders.place(key, "SOFA-1", 1).getId())));
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
	void errorsComeBackAsProblemDetails() throws Exception {
		mvc.perform(post("/api/orders").contentType(APPLICATION_JSON).content(body("SOFA-1", 1)))
			.andExpect(status().isBadRequest()); // no Idempotency-Key

		when(inventory.reserve("SOFA-1", 9)).thenThrow(new ErrorResponseException(CONFLICT,
				ProblemDetail.forStatusAndDetail(CONFLICT, "Not enough stock of SOFA-1 to reserve 9"), null));
		mvc.perform(place(key, "SOFA-1", 9))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Not enough stock of SOFA-1 to reserve 9"));

		// The refused attempt left no order behind, so the same key can be used again
		when(inventory.reserve("SOFA-1", 1)).thenReturn(hold(Duration.ofSeconds(-1)));
		String late = mvc.perform(place(key, "SOFA-1", 1)).andReturn().getResponse().getContentAsString();
		mvc.perform(post("/api/orders/{id}/pay", JsonPath.<String>read(late, "$.id")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(containsString("hold on this order ran out")));

		mvc.perform(post("/api/orders/{id}/pay", UUID.randomUUID())).andExpect(status().isNotFound());
	}

	/** Reads the order-paid topic until the event for this order shows up, and returns its JSON. */
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

	private static InventoryClient.Reservation hold(Duration left) {
		return new InventoryClient.Reservation(UUID.randomUUID(), Instant.now().plus(left));
	}

	private static MockHttpServletRequestBuilder place(String key, String sku, int quantity) {
		return post("/api/orders").header("Idempotency-Key", key).contentType(APPLICATION_JSON).content(body(sku, quantity));
	}

	private static String body(String sku, int quantity) {
		return "{\"sku\": \"%s\", \"quantity\": %d}".formatted(sku, quantity);
	}

}
