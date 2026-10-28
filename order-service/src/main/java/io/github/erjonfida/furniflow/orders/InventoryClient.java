package io.github.erjonfida.furniflow.orders;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.client.RestClient;

/** Calls inventory-service. Timeouts are under spring.http.clients in application.yml. */
@Component
class InventoryClient {

	record Reservation(UUID id, Instant expiresAt) {
	}

	private final RestClient http;

	InventoryClient(RestClient.Builder builder, JsonMapper json,
			@Value("${furniflow.inventory-url}") String inventoryUrl) {
		this.http = builder.baseUrl(inventoryUrl)
			// Unknown SKU (404) or not enough stock (409): pass inventory's explanation on to the customer
			.defaultStatusHandler(HttpStatusCode::is4xxClientError, (request, response) -> {
				String detail = json.readTree(response.getBody()).path("detail").asString();
				throw new ErrorResponseException(response.getStatusCode(),
						ProblemDetail.forStatusAndDetail(response.getStatusCode(), detail), null);
			})
			.build();
	}

	Reservation reserve(String sku, int quantity) {
		return http.post()
			.uri("/api/inventory/reservations")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("sku", sku, "quantity", quantity))
			.retrieve()
			.body(Reservation.class);
	}

	void release(UUID reservationId) {
		http.delete().uri("/api/inventory/reservations/{id}", reservationId).retrieve().toBodilessEntity();
	}

}
