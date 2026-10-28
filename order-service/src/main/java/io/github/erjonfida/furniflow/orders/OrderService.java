package io.github.erjonfida.furniflow.orders;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

	static final String ORDER_PAID = "order-paid";

	/** The event inventory-service needs to confirm the reservation. */
	record OrderPaid(UUID orderId, UUID reservationId) {
	}

	private final OrderRepository orders;

	private final InventoryClient inventory;

	private final Outbox outbox;

	OrderService(OrderRepository orders, InventoryClient inventory, Outbox outbox) {
		this.orders = orders;
		this.inventory = inventory;
		this.outbox = outbox;
	}

	@Transactional(readOnly = true)
	public Order get(UUID id) {
		return orders.findById(id).orElseThrow(() -> new NoSuchElementException("Unknown order " + id));
	}

	/**
	 * Places one order per idempotency key, however often the client retries. Not @Transactional on purpose: no
	 * database connection is held while we wait for inventory. If this fails after inventory reserved the units,
	 * that reservation simply expires.
	 */
	public Order place(String idempotencyKey, String sku, int quantity) {
		Optional<Order> existing = orders.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			return existing.get();
		}
		InventoryClient.Reservation reservation = inventory.reserve(sku, quantity);
		try {
			return orders.saveAndFlush(
					new Order(idempotencyKey, sku, quantity, reservation.id(), reservation.expiresAt()));
		}
		catch (DataIntegrityViolationException sameKey) {
			// A concurrent retry with the same key saved its order first (idempotency_key is UNIQUE).
			// Give back the units we just reserved and answer with that order.
			inventory.release(reservation.id());
			return orders.findByIdempotencyKey(idempotencyKey).orElseThrow();
		}
	}

	@Transactional
	public Order pay(UUID id) {
		Order order = get(id);
		if (order.getStatus() == Order.Status.PAID) {
			// Payment providers retry their callbacks. Two at the very same moment can both get past this check,
			// which is fine: inventory ignores the repeated event.
			return order;
		}
		if (order.getReservedUntil().isBefore(Instant.now())) {
			throw new IllegalStateException("The hold on this order ran out. Place it again.");
		}
		order.markPaid();
		// Same transaction as the status change: the event exists if and only if the payment was recorded
		outbox.add(ORDER_PAID, id, new OrderPaid(id, order.getReservationId()));
		return order;
	}

}
