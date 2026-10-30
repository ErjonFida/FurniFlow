package io.github.erjonfida.furniflow.orders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRepository extends JpaRepository<Order, UUID> {

	Optional<Order> findByIdAndCustomerId(UUID id, String customerId);

	Optional<Order> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);

	// Every hold lasts equally long, so the latest hold is the newest order
	List<Order> findByCustomerIdOrderByReservedUntilDesc(String customerId);

}
