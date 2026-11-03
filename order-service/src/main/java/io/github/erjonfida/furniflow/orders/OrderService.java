package io.github.erjonfida.furniflow.orders;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

	static final String ORDER_PAID = "order-paid";

	/** The event inventory-service needs to confirm the reservation. */
	record OrderPaid(UUID orderId, UUID reservationId) {
	}

	private final OrderRepository orders;

	private final InventoryClient inventory;

	private final Outbox outbox;

	private final TransactionTemplate transactions;

	OrderService(OrderRepository orders, InventoryClient inventory, Outbox outbox, TransactionTemplate transactions) {
		this.orders = orders;
		this.inventory = inventory;
		this.outbox = outbox;
		this.transactions = transactions;
	}

	// Another customer's order is reported as unknown, so nobody can find out which order IDs exist
	@Transactional(readOnly = true)
	public Order get(UUID id, String customerId) {
		return orders.findByIdAndCustomerId(id, customerId)
			.orElseThrow(() -> new NoSuchElementException("Unknown order " + id));
	}

	@Transactional(readOnly = true)
	public List<Order> list(String customerId) {
		return orders.findByCustomerIdOrderByReservedUntilDesc(customerId);
	}

	@Transactional(readOnly = true)
	public List<Order> all() {
		return orders.findAllByOrderByReservedUntilDesc();
	}

	/**
	 * Places one order per idempotency key, however often the client retries. Not @Transactional on purpose: no
	 * database connection is held while we wait for inventory. If this fails after inventory reserved the units,
	 * that reservation simply expires.
	 */
	public Order place(String customerId, String customerName, String idempotencyKey, String sku, int quantity) {
		Optional<Order> existing = orders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
		if (existing.isPresent()) {
			return existing.get();
		}
		InventoryClient.Reservation reservation = inventory.reserve(sku, quantity);
		try {
			return orders.saveAndFlush(new Order(customerId, customerName, idempotencyKey, sku, quantity,
					reservation.id(), reservation.expiresAt()));
		}
		catch (DataIntegrityViolationException sameKey) {
			// A concurrent retry with the same key saved its order first ((customer_id, idempotency_key) is UNIQUE).
			// Give back the units we just reserved and answer with that order.
			inventory.release(reservation.id());
			return orders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey).orElseThrow();
		}
	}

	@Transactional
	public Order pay(UUID id, String customerId) {
		Order order = lock(id, customerId);
		if (order.getStatus() == Order.Status.PAID) {
			return order; // payment providers retry their callbacks
		}
		if (order.getStatus() == Order.Status.CANCELLED) {
			// Its units are already back on sale
			throw new IllegalStateException("This order was cancelled. Place it again.");
		}
		if (order.getReservedUntil().isBefore(Instant.now())) {
			throw new IllegalStateException("The hold on this order ran out. Place it again.");
		}
		order.markPaid();
		// Same transaction as the status change: the event exists if and only if the payment was recorded
		outbox.add(ORDER_PAID, id, new OrderPaid(id, order.getReservationId()));
		return order;
	}

	/**
	 * Cancels an unpaid order and gives its units back. The cancellation is committed before inventory is called,
	 * so units never go back on sale for an order that can still be paid. Safe to repeat: if inventory can't be
	 * reached, cancelling again gives the units back, and otherwise the hold expires by itself.
	 */
	public Order cancel(UUID id, String customerId) {
		Order order = transactions.execute(tx -> {
			Order locked = lock(id, customerId);
			if (locked.getStatus() == Order.Status.PAID) {
				// ponytail: no refunds; add them together with a real payment provider
				throw new IllegalStateException("This order is paid and can't be cancelled.");
			}
			locked.markCancelled();
			return locked;
		});
		inventory.release(order.getReservationId()); // inventory only releases a hold that is still active
		return order;
	}

	private Order lock(UUID id, String customerId) {
		return orders.findForUpdateByIdAndCustomerId(id, customerId)
			.orElseThrow(() -> new NoSuchElementException("Unknown order " + id));
	}

}
