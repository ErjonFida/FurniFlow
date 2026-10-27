package io.github.erjonfida.furniflow.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Reservation {

	public enum Status { ACTIVE, RELEASED, EXPIRED }

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String sku;

	private int quantity;

	@Enumerated(EnumType.STRING)
	private Status status;

	private Instant expiresAt;

	protected Reservation() {
	}

	Reservation(String sku, int quantity, Instant expiresAt) {
		this.sku = sku;
		this.quantity = quantity;
		this.status = Status.ACTIVE;
		this.expiresAt = expiresAt;
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

	public Instant getExpiresAt() {
		return expiresAt;
	}

}
