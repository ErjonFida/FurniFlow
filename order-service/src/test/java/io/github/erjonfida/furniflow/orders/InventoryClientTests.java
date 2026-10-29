package io.github.erjonfida.furniflow.orders;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The HTTP contract with inventory-service, against a fake server. */
@RestClientTest(components = InventoryClient.class, properties = "furniflow.inventory-url=http://inventory")
class InventoryClientTests {

	@Autowired
	InventoryClient inventory;

	@Autowired
	MockRestServiceServer server;

	@MockitoBean
	ClientRegistrationRepository registrations;

	@MockitoBean
	OAuth2AuthorizedClientService authorizedClients;

	// order-service already holds a valid token from Keycloak, so no token request is made
	@BeforeEach
	void serviceToken() {
		ClientRegistration keycloak = ClientRegistration.withRegistrationId("inventory")
			.clientId("order-service")
			.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
			.tokenUri("http://keycloak/token")
			.build();
		OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "service-token",
				Instant.now(), Instant.now().plusSeconds(300));
		when(registrations.findByRegistrationId("inventory")).thenReturn(keycloak);
		when(authorizedClients.<OAuth2AuthorizedClient>loadAuthorizedClient("inventory", "anonymousUser"))
			.thenReturn(new OAuth2AuthorizedClient(keycloak, "anonymousUser", token));
	}

	@Test
	void reserveSendsTheRequestAndReadsTheReservation() {
		UUID id = UUID.randomUUID();
		server.expect(requestTo("http://inventory/api/inventory/reservations"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer service-token"))
			.andExpect(content().json("{\"sku\": \"SOFA-1\", \"quantity\": 2}"))
			.andRespond(withSuccess("""
					{"id": "%s", "sku": "SOFA-1", "quantity": 2, "status": "ACTIVE", "expiresAt": "2026-10-27T10:15:00Z"}
					""".formatted(id), MediaType.APPLICATION_JSON));

		assertThat(inventory.reserve("SOFA-1", 2))
			.isEqualTo(new InventoryClient.Reservation(id, Instant.parse("2026-10-27T10:15:00Z")));
	}

	@Test
	void inventoryRefusalsArePassedOn() {
		server.expect(requestTo("http://inventory/api/inventory/reservations"))
			.andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.body("{\"status\": 409, \"detail\": \"Not enough stock of SOFA-1 to reserve 5\"}"));

		assertThatThrownBy(() -> inventory.reserve("SOFA-1", 5))
			.isInstanceOfSatisfying(ErrorResponseException.class, e -> {
				assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(e.getBody().getDetail()).isEqualTo("Not enough stock of SOFA-1 to reserve 5");
			});
	}

	@Test
	void aRejectedServiceTokenIsDroppedAndNotBlamedOnTheCustomer() {
		server.expect(requestTo("http://inventory/api/inventory/reservations"))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		// Not an ErrorResponseException: the customer must not see a 401 for our own credentials
		assertThatThrownBy(() -> inventory.reserve("SOFA-1", 1)).isInstanceOf(HttpClientErrorException.class);
		verify(authorizedClients).removeAuthorizedClient("inventory", "anonymousUser");
	}

}
