package io.github.erjonfida.furniflow.orders;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.github.erjonfida.furniflow.orders.OrderService.Item;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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

	// The cart. Each line costs a call to inventory, hence the cap.
	record PlaceOrderRequest(@NotEmpty @Size(max = 50) List<@NotNull @Valid Item> items) {
	}

	record OrderView(UUID id, String customer, List<Item> items, Order.Status status, Instant reservedUntil) {

		static OrderView of(Order o) {
			List<Item> items = o.getLines().stream().map(line -> new Item(line.sku(), line.quantity())).toList();
			return new OrderView(o.getId(), o.getCustomer(), items, o.getStatus(), o.getReservedUntil());
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
			@Valid @RequestBody PlaceOrderRequest request, JwtAuthenticationToken customer) {
		String username = customer.getToken().getClaimAsString("preferred_username");
		return OrderView.of(orders.place(customer.getName(), username, idempotencyKey, request.items()));
	}

	// ponytail: no paging, fine for a customer's own orders
	@GetMapping
	List<OrderView> list(Principal customer) {
		return orders.list(customer.getName()).stream().map(OrderView::of).toList();
	}

	// Every customer's orders, for admins (see SecurityConfig).
	// ponytail: no paging; add it when the shop has more than a few hundred orders
	@GetMapping("/all")
	List<OrderView> all() {
		return orders.all().stream().map(OrderView::of).toList();
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

	// Unpaid orders only. The order stays, marked CANCELLED, and its units go back on sale.
	@PostMapping("/{id}/cancel")
	OrderView cancel(@PathVariable UUID id, Principal customer) {
		return OrderView.of(orders.cancel(id, customer.getName()));
	}

	@ExceptionHandler
	ProblemDetail notFound(NoSuchElementException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler
	ProblemDetail wrongStatus(IllegalStateException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	// Timed out or couldn't connect. Retrying with the same Idempotency-Key is safe.
	@ExceptionHandler
	ProblemDetail inventoryUnavailable(ResourceAccessException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Inventory is not answering. Try again.");
	}

}
