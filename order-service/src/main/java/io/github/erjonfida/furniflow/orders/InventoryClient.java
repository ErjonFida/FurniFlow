package io.github.erjonfida.furniflow.orders;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.client.RestClient;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Calls inventory-service. Timeouts are under spring.http.clients in application.yml. */
@Component
class InventoryClient {

	record Reservation(UUID id, Instant expiresAt) {
	}

	private final RestClient http;

	InventoryClient(RestClient.Builder builder, JsonMapper json,
			@Value("${furniflow.inventory-url}") String inventoryUrl, ClientRegistrationRepository registrations,
			OAuth2AuthorizedClientService authorizedClients) {
		// Adds order-service's own access token, fetched from Keycloak (client credentials) and reused until it expires
		OAuth2ClientHttpRequestInterceptor token = new OAuth2ClientHttpRequestInterceptor(
				new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients));
		token.setClientRegistrationIdResolver(request -> "inventory");
		token.setPrincipalResolver(request -> null); // one token for the service, not one per signed-in customer
		// A 401 (say Keycloak was reset and its keys changed) drops the cached token, so the next call gets a new one
		token.setAuthorizationFailureHandler(
				OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(authorizedClients));

		this.http = builder.baseUrl(inventoryUrl)
			.requestInterceptor(token)
			// Unknown SKU (404) or not enough stock (409): pass inventory's explanation on to the customer.
			// Any other error (inventory rejecting our token, say) is our fault and ends up as a 500.
			.defaultStatusHandler(status -> status.isSameCodeAs(NOT_FOUND) || status.isSameCodeAs(CONFLICT),
					(request, response) -> {
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
