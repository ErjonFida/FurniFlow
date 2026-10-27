package io.github.erjonfida.furniflow.inventory;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ReservationRepository extends JpaRepository<Reservation, UUID> {

	//Returns 1 if this call released it, 0 if it was already released or expired.
	@Modifying
	@Query(value = "UPDATE reservation SET status = 'RELEASED' WHERE id = :id AND status = 'ACTIVE'", nativeQuery = true)
	int markReleased(UUID id);

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
