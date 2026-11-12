package io.github.erjonfida.furniflow.inventory;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderEventsTests {

	@MockitoSpyBean
	InventoryService inventory;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Test
	void anEventThatArrivesWhileTheDatabaseIsAwayIsConfirmedOnceItIsBack() {
		UUID id = reserve();
		// Longer than Spring Kafka's default of 10 tries with no pause lasts: about 5 seconds on a quiet topic
		long back = System.nanoTime() + Duration.ofSeconds(6).toNanos();
		doAnswer(call -> {
			if (System.nanoTime() < back) {
				throw new CannotCreateTransactionException("the database is away");
			}
			return call.callRealMethod();
		}).when(inventory).confirm(id);

		kafka.send("order-paid", paid(id)).join();

		awaitConfirmed(id);
	}

	@Test
	void anEventThatRetryingCantFixDoesntHoldUpTheOnesBehindIt() {
		UUID id = reserve();

		// The same key keeps them on one partition, in this order
		kafka.send("order-paid", "key", "not an event").join();
		kafka.send("order-paid", "key", "{\"orderId\": \"%s\"}".formatted(UUID.randomUUID())).join(); // no reservations
		kafka.send("order-paid", "key", paid(UUID.randomUUID())).join(); // a reservation that doesn't exist
		kafka.send("order-paid", "key", paid(id)).join();

		awaitConfirmed(id);
	}

	private UUID reserve() {
		String sku = "SOFA-" + UUID.randomUUID();
		inventory.restock(sku, 5);
		return inventory.reserve(sku, 2).getId();
	}

	private static String paid(UUID reservation) {
		return "{\"orderId\": \"%s\", \"reservationIds\": [\"%s\"]}".formatted(UUID.randomUUID(), reservation);
	}

	private void awaitConfirmed(UUID id) {
		await().atMost(Duration.ofSeconds(30))
			.until(() -> jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id),
					"CONFIRMED"::equals);
	}

}
