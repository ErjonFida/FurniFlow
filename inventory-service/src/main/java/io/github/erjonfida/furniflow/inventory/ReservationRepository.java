package io.github.erjonfida.furniflow.inventory;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ReservationRepository extends JpaRepository<Reservation, UUID> {

	// Returns 1 if this call moved it, 0 if a release, the expiry job or a repeated event got there first
	@Modifying
	@Query("UPDATE Reservation r SET r.status = :to WHERE r.id = :id AND r.status = :from")
	int transition(UUID id, Reservation.Status from, Reservation.Status to);

	// One statement expires overdue reservations and returns their units. Safe on several instances at once: a row
	// another run already expired no longer matches status = 'ACTIVE'.
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
