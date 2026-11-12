package io.github.erjonfida.furniflow.orders;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.client.RestClient;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

// Timeouts are under spring.http.clients in application.yml
@Component
class InventoryClient {

	record Reservation(UUID id, Instant expiresAt) {
	}

	private final RestClient http;

	InventoryClient(RestClient.Builder builder, JsonMapper json,
			@Value("${furniflow.inventory-url}") String inventoryUrl, ClientRegistrationRepository registrations,
			OAuth2AuthorizedClientService authorizedClients) {
		// Spring Security fetches the token with an HTTP client of its own, which has no timeouts. A copy of ours,
		// made before the token interceptor is added to it, gives a Keycloak that doesn't answer the same timeouts.
		RestClientClientCredentialsTokenResponseClient tokens = new RestClientClientCredentialsTokenResponseClient();
		tokens.setRestClient(builder.clone()
			.configureMessageConverters(converters -> {
				converters.addCustomConverter(new FormHttpMessageConverter());
				converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
			})
			.defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
			.build());
		ClientCredentialsOAuth2AuthorizedClientProvider clientCredentials = new ClientCredentialsOAuth2AuthorizedClientProvider();
		clientCredentials.setAccessTokenResponseClient(tokens);
		AuthorizedClientServiceOAuth2AuthorizedClientManager tokenManager =
				new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
		tokenManager.setAuthorizedClientProvider(clientCredentials);

		// Adds order-service's own access token, fetched from Keycloak (client credentials) and reused until it expires
		OAuth2ClientHttpRequestInterceptor token = new OAuth2ClientHttpRequestInterceptor(tokenManager);
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
