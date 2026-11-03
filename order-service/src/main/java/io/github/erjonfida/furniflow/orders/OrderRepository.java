package io.github.erjonfida.furniflow.orders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface OrderRepository extends JpaRepository<Order, UUID> {

	Optional<Order> findByIdAndCustomerId(UUID id, String customerId);

	// SELECT ... FOR UPDATE: a second payment or cancellation of the same order waits until this transaction ends
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Order> findForUpdateByIdAndCustomerId(UUID id, String customerId);

	Optional<Order> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);

	// Every hold lasts equally long, so the latest hold is the newest order
	List<Order> findByCustomerIdOrderByReservedUntilDesc(String customerId);

	List<Order> findAllByOrderByReservedUntilDesc();

}
