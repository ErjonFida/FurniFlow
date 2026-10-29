package io.github.erjonfida.furniflow.orders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order {

	public enum Status { RESERVED, PAID }

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String customerId;

	private String idempotencyKey;

	private String sku;

	private int quantity;

	@Enumerated(EnumType.STRING)
	private Status status;

	private UUID reservationId;

	private Instant reservedUntil;

	protected Order() {
	}

	Order(String customerId, String idempotencyKey, String sku, int quantity, UUID reservationId,
			Instant reservedUntil) {
		this.customerId = customerId;
		this.idempotencyKey = idempotencyKey;
		this.sku = sku;
		this.quantity = quantity;
		this.status = Status.RESERVED;
		this.reservationId = reservationId;
		// PostgreSQL keeps microseconds: truncate now so a retried request gets back exactly the same response
		this.reservedUntil = reservedUntil.truncatedTo(ChronoUnit.MICROS);
	}

	void markPaid() {
		this.status = Status.PAID;
	}

	public UUID getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public int getQuantity() {
		return quantity;
	}

	public Status getStatus() {
		return status;
	}

	public UUID getReservationId() {
		return reservationId;
	}

	public Instant getReservedUntil() {
		return reservedUntil;
	}

}
