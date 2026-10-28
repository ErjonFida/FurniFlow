package io.github.erjonfida.furniflow.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static io.github.erjonfida.furniflow.inventory.Reservation.Status.ACTIVE;
import static io.github.erjonfida.furniflow.inventory.Reservation.Status.CONFIRMED;
import static io.github.erjonfida.furniflow.inventory.Reservation.Status.EXPIRED;
import static io.github.erjonfida.furniflow.inventory.Reservation.Status.RELEASED;

@Service
public class InventoryService {

	//How long a customer has to pay before the reserved units go back on sale.
	static final Duration HOLD = Duration.ofMinutes(15);

	private final StockRepository stock;

	private final ReservationRepository reservations;

	InventoryService(StockRepository stock, ReservationRepository reservations) {
		this.stock = stock;
		this.reservations = reservations;
	}

	@Transactional(readOnly = true)
	public Stock getStock(String sku) {
		return stock.findById(sku).orElseThrow(() -> new NoSuchElementException("Unknown SKU " + sku));
	}

	@Transactional
	public void restock(String sku, int quantity) {
		stock.restock(sku, quantity);
	}

	@Transactional
	public Reservation reserve(String sku, int quantity) {
		if (stock.take(sku, quantity) == 0) {
			throw stock.existsById(sku) ? new InsufficientStockException(sku, quantity)
					: new NoSuchElementException("Unknown SKU " + sku);
		}
		return reservations.save(new Reservation(sku, quantity, Instant.now().plus(HOLD)));
	}

	@Transactional
	public void release(UUID id) {
		Reservation reservation = find(id);
		// A conditional UPDATE, not reservation.setStatus(...): if a release and the expiry job race,
		// only the one that flips ACTIVE gives the units back, so stock is never returned twice.
		if (reservations.transition(id, ACTIVE, RELEASED) == 1) {
			stock.giveBack(reservation.getSku(), reservation.getQuantity());
		}
	}

	/** The order was paid: the units are sold and can no longer expire or be released. Safe to repeat. */
	@Transactional
	public void confirm(UUID id) {
		if (reservations.transition(id, ACTIVE, CONFIRMED) == 1) {
			return;
		}
		// Paid after the hold ran out (say the event waited while Kafka was down), so the expiry job already put the
		// units back on sale: take them again. Any other status means a repeated event, and there is nothing to do.
		Reservation reservation = find(id);
		if (reservations.transition(id, EXPIRED, CONFIRMED) == 1
				&& stock.take(reservation.getSku(), reservation.getQuantity()) == 0) {
			// Sold to someone else meanwhile. Throwing rolls the status change back.
			// ponytail: after Kafka's retries this is only logged; add a dead-letter topic and a refund flow if it happens
			throw new InsufficientStockException(reservation.getSku(), reservation.getQuantity());
		}
	}

	@Scheduled(fixedDelay = 60_000)
	@Transactional
	public void expireOverdue() {
		reservations.expireOverdue();
	}

	private Reservation find(UUID id) {
		return reservations.findById(id).orElseThrow(() -> new NoSuchElementException("Unknown reservation " + id));
	}

}
