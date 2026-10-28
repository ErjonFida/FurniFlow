package io.github.erjonfida.furniflow.orders;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRepository extends JpaRepository<Order, UUID> {

	Optional<Order> findByIdempotencyKey(String idempotencyKey);

}
