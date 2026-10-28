package io.github.erjonfida.furniflow.inventory;

import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Events from order-service. The JSON is the contract between the services; this record is inventory's own copy. */
@Component
class OrderEvents {

	record OrderPaid(UUID orderId, UUID reservationId) {
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
		inventory.confirm(json.readValue(event, OrderPaid.class).reservationId());
	}

}
