package io.github.erjonfida.furniflow.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

@Entity
@Table(name = "orders")
public class Order {

	public enum Status { RESERVED, PAID, CANCELLED }

	// One product in the order, with the catalog's name and price when the order was placed. Inventory holds its units
	// under reservationId.
	@Embeddable
	public record Line(String sku, String productName, int quantity, BigDecimal unitPrice, UUID reservationId) {
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String customerId;

	// The username when the order was placed, for people to read. Ownership checks use customerId.
	private String customerName;

	private String idempotencyKey;

	// Eager: every view of an order shows its lines (and open-in-view is off). SUBSELECT loads the lines of a whole
	// list of orders in one more query instead of one per order.
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "order_line", joinColumns = @JoinColumn(name = "order_id"))
	@OrderColumn(name = "line_no")
	@Fetch(FetchMode.SUBSELECT)
	private List<Line> lines = new ArrayList<>();

	@Enumerated(EnumType.STRING)
	private Status status;

	// When the earliest of the lines' holds runs out
	private Instant reservedUntil;

	protected Order() {
	}

	Order(String customerId, String customerName, String idempotencyKey, List<Line> lines, Instant reservedUntil) {
		this.customerId = customerId;
		this.customerName = customerName;
		this.idempotencyKey = idempotencyKey;
		this.lines = new ArrayList<>(lines);
		this.status = Status.RESERVED;
		// PostgreSQL keeps microseconds: truncate now so a retried request gets back exactly the same response
		this.reservedUntil = reservedUntil.truncatedTo(ChronoUnit.MICROS);
	}

	void markPaid() {
		this.status = Status.PAID;
	}

	void markCancelled() {
		this.status = Status.CANCELLED;
	}

	public UUID getId() {
		return id;
	}

	// Orders placed before names were kept (V3) only have the Keycloak user ID
	public String getCustomer() {
		return customerName != null ? customerName : customerId;
	}

	public List<Line> getLines() {
		return lines;
	}

	// Exact: BigDecimal, no rounding. Orders placed before prices were kept (V5) have no total.
	public BigDecimal getTotal() {
		if (lines.stream().anyMatch(line -> line.unitPrice() == null)) {
			return null;
		}
		return lines.stream()
			.map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	public List<UUID> getReservationIds() {
		return lines.stream().map(Line::reservationId).toList();
	}

	public Status getStatus() {
		return status;
	}

	public Instant getReservedUntil() {
		return reservedUntil;
	}

}
