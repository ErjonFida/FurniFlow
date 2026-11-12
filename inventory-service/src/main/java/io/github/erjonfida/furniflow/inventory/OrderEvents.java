package io.github.erjonfida.furniflow.inventory;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// The JSON is the contract with order-service; this record is inventory's own copy
@Component
class OrderEvents {

	record OrderPaid(UUID orderId, List<UUID> reservationIds) {
	}

	private final InventoryService inventory;

	private final JsonMapper json;

	OrderEvents(InventoryService inventory, JsonMapper json) {
		this.inventory = inventory;
		this.json = json;
	}

	// Kafka delivers at least once, so the same event can arrive twice; confirm() is safe to repeat
	@KafkaListener(topics = "order-paid")
	void onOrderPaid(String event) {
		// Confirm every line before reporting a failure: one line sold out meanwhile (see confirm) must not leave the
		// others to expire. On a redelivery, the lines already confirmed are skipped.
		RuntimeException failure = null;
		for (UUID reservation : json.readValue(event, OrderPaid.class).reservationIds()) {
			try {
				inventory.confirm(reservation);
			}
			catch (RuntimeException e) {
				failure = e;
			}
		}
		if (failure != null) {
			throw failure;
		}
	}

}
