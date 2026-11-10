package io.github.erjonfida.furniflow.orders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

	private static final Logger log = LoggerFactory.getLogger(OrderService.class);

	static final String ORDER_PAID = "order-paid";

	/** The event inventory-service needs to confirm the reservations, one per line. */
	record OrderPaid(UUID orderId, List<UUID> reservationIds) {
	}

	/** One product the customer wants, and how many. */
	record Item(@NotBlank String sku, @Positive int quantity) {
	}

	private final OrderRepository orders;

	private final CatalogClient catalog;

	private final InventoryClient inventory;

	private final Outbox outbox;

	private final TransactionTemplate transactions;

	OrderService(OrderRepository orders, CatalogClient catalog, InventoryClient inventory, Outbox outbox,
			TransactionTemplate transactions) {
		this.orders = orders;
		this.catalog = catalog;
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
	 * database connection is held while we wait for the catalog or inventory. If this fails after inventory reserved
	 * the units, that reservation simply expires.
	 */
	public Order place(String customerId, String customerName, String idempotencyKey, List<Item> items) {
		Optional<Order> existing = orders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
		if (existing.isPresent()) {
			return sameItems(existing.get(), items);
		}
		List<Order.Line> lines = new ArrayList<>();
		Instant reservedUntil = Instant.MAX;
		try {
			// ponytail: a catalog and an inventory call per line, fine for carts of a few products (the controller caps
			// them); add batch endpoints to both if carts get big
			for (Item item : items) {
				// From the catalog, never from the client: the request only says what and how many
				CatalogClient.Product product = catalog.product(item.sku());
				InventoryClient.Reservation reservation = inventory.reserve(item.sku(), item.quantity());
				lines.add(new Order.Line(item.sku(), product.name(), item.quantity(), product.price(), reservation.id()));
				if (reservation.expiresAt().isBefore(reservedUntil)) {
					reservedUntil = reservation.expiresAt();
				}
			}
		}
		catch (RuntimeException failed) {
			// Say the second product is out of stock: give back what the lines before it reserved, so nothing is held
			// for an order that doesn't exist
			release(lines);
			throw failed;
		}
		try {
			return orders.saveAndFlush(new Order(customerId, customerName, idempotencyKey, lines, reservedUntil));
		}
		catch (DataIntegrityViolationException sameKey) {
			// A concurrent retry with the same key saved its order first ((customer_id, idempotency_key) is UNIQUE).
			// Give back the units we just reserved and answer with that order.
			release(lines);
			return sameItems(orders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey).orElseThrow(), items);
		}
	}

	// A retry must send the same items. Other items under the same key are another order, and answering with the
	// first one would quietly drop the change (422, as the IETF Idempotency-Key draft suggests).
	private static Order sameItems(Order order, List<Item> items) {
		if (!order.getLines().stream().map(line -> new Item(line.sku(), line.quantity())).toList().equals(items)) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
					"This Idempotency-Key was already used for an order with other items. Send a new key for a new order.");
		}
		return order;
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
		outbox.add(ORDER_PAID, id, new OrderPaid(id, order.getReservationIds()));
		return order;
	}

	/**
	 * Cancels an unpaid order and gives its units back. The cancellation is committed before inventory is called,
	 * so units never go back on sale for an order that can still be paid. If inventory can't take them back right now,
	 * the order is still cancelled and its holds run out by themselves.
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
		release(order.getLines()); // inventory only releases a hold that is still active
		return order;
	}

	// Best effort: a hold that isn't given back runs out by itself. Each line gets its own try, so one failure doesn't
	// keep the others held, and the caller's own outcome (out of stock, the order that exists) is what the customer sees.
	private void release(List<Order.Line> lines) {
		for (Order.Line line : lines) {
			try {
				inventory.release(line.reservationId());
			}
			catch (RuntimeException e) {
				log.warn("Couldn't give back reservation {}; it runs out by itself", line.reservationId(), e);
			}
		}
	}

	private Order lock(UUID id, String customerId) {
		return orders.findForUpdateByIdAndCustomerId(id, customerId)
			.orElseThrow(() -> new NoSuchElementException("Unknown order " + id));
	}

}
