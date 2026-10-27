package io.github.erjonfida.furniflow.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
		Reservation reservation = reservations.findById(id)
			.orElseThrow(() -> new NoSuchElementException("Unknown reservation " + id));
		// A conditional UPDATE, not reservation.setStatus(...): if a release and the expiry job race,
		// only the one that flips ACTIVE gives the units back, so stock is never returned twice.
		if (reservations.markReleased(id) == 1) {
			stock.giveBack(reservation.getSku(), reservation.getQuantity());
		}
	}

	@Scheduled(fixedDelay = 60_000)
	@Transactional
	public void expireOverdue() {
		reservations.expireOverdue();
	}

}
