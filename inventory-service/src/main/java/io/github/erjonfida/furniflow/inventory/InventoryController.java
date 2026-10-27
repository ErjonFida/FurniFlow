package io.github.erjonfida.furniflow.inventory;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
class InventoryController {

	record StockView(String sku, int available) {
	}

	record RestockRequest(@Positive int quantity) {
	}

	record ReserveRequest(@NotBlank String sku, @Positive int quantity) {
	}

	record ReservationView(UUID id, String sku, int quantity, Reservation.Status status, Instant expiresAt) {

		static ReservationView of(Reservation r) {
			return new ReservationView(r.getId(), r.getSku(), r.getQuantity(), r.getStatus(), r.getExpiresAt());
		}

	}

	private final InventoryService inventory;

	InventoryController(InventoryService inventory) {
		this.inventory = inventory;
	}

	@GetMapping("/stock/{sku}")
	StockView stock(@PathVariable String sku) {
		Stock stock = inventory.getStock(sku);
		return new StockView(stock.getSku(), stock.getAvailable());
	}

	@PostMapping("/stock/{sku}/restock")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void restock(@PathVariable String sku, @Valid @RequestBody RestockRequest request) {
		inventory.restock(sku, request.quantity());
	}

	@PostMapping("/reservations")
	@ResponseStatus(HttpStatus.CREATED)
	ReservationView reserve(@Valid @RequestBody ReserveRequest request) {
		return ReservationView.of(inventory.reserve(request.sku(), request.quantity()));
	}

	@DeleteMapping("/reservations/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void release(@PathVariable UUID id) {
		inventory.release(id);
	}

	@ExceptionHandler
	ProblemDetail notFound(NoSuchElementException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler
	ProblemDetail insufficientStock(InsufficientStockException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

}
