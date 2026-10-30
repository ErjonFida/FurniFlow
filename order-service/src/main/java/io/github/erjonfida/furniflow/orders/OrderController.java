package io.github.erjonfida.furniflow.orders;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

@RestController
@RequestMapping("/api/orders")
class OrderController {

	record PlaceOrderRequest(@NotBlank String sku, @Positive int quantity) {
	}

	record OrderView(UUID id, String sku, int quantity, Order.Status status, Instant reservedUntil) {

		static OrderView of(Order o) {
			return new OrderView(o.getId(), o.getSku(), o.getQuantity(), o.getStatus(), o.getReservedUntil());
		}

	}

	private final OrderService orders;

	OrderController(OrderService orders) {
		this.orders = orders;
	}

	// The client makes up a key per order (a UUID) and sends the same one on every retry.
	// customer.getName() is the token's subject: the customer's Keycloak user ID.
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	OrderView place(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
			@Valid @RequestBody PlaceOrderRequest request, Principal customer) {
		return OrderView.of(orders.place(customer.getName(), idempotencyKey, request.sku(), request.quantity()));
	}

	// ponytail: no paging, fine for a customer's own orders
	@GetMapping
	List<OrderView> list(Principal customer) {
		return orders.list(customer.getName()).stream().map(OrderView::of).toList();
	}

	@GetMapping("/{id}")
	OrderView get(@PathVariable UUID id, Principal customer) {
		return OrderView.of(orders.get(id, customer.getName()));
	}

	// Stands in for checking out with a payment provider
	@PostMapping("/{id}/pay")
	OrderView pay(@PathVariable UUID id, Principal customer) {
		return OrderView.of(orders.pay(id, customer.getName()));
	}

	@ExceptionHandler
	ProblemDetail notFound(NoSuchElementException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler
	ProblemDetail notPayable(IllegalStateException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	// Timed out or couldn't connect. Retrying with the same Idempotency-Key is safe.
	@ExceptionHandler
	ProblemDetail inventoryUnavailable(ResourceAccessException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Inventory is not answering. Try again.");
	}

}
