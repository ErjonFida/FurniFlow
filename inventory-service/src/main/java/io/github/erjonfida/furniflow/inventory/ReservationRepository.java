package io.github.erjonfida.furniflow.inventory;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ReservationRepository extends JpaRepository<Reservation, UUID> {

	// Moves a reservation to {@code to} only if it is still {@code from}. Returns 1 if this call moved it, 0 if
	// something else (a release, the expiry job, a repeated event) got there first.
	@Modifying
	@Query("UPDATE Reservation r SET r.status = :to WHERE r.id = :id AND r.status = :from")
	int transition(UUID id, Reservation.Status from, Reservation.Status to);

	/**
	Expires overdue reservations and returns their units to stock, in one statement.
	Safe to run on several instances at once: a row already expired by another run no longer matches status = 'ACTIVE'.
	 */
	@Modifying
	@Query(value = """
			WITH expired AS (
			    UPDATE reservation SET status = 'EXPIRED'
			    WHERE status = 'ACTIVE' AND expires_at < now()
			    RETURNING sku, quantity
			)
			UPDATE stock SET available = stock.available + e.total
			FROM (SELECT sku, sum(quantity) AS total FROM expired GROUP BY sku) e
			WHERE stock.sku = e.sku
			""", nativeQuery = true)
	void expireOverdue();

}
