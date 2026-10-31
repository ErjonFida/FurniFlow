package io.github.erjonfida.furniflow.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Runs against a real MongoDB in Docker (Testcontainers)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogIntegrationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	MongoTemplate mongo;

	@Autowired
	JwtAuthenticationConverter tokens;

	// The tests share one database, so each test (a new instance) gets its own SKUs
	final String sofa = "SOFA-" + UUID.randomUUID();

	final String table = "TABLE-" + UUID.randomUUID();

	@Test
	void productsKeepTheirOwnAttributes() throws Exception {
		mvc.perform(save(sofa, "Oslo sofa", "1299.90", "{\"seats\": \"3\", \"fabric\": \"linen\"}").with(as("admin")))
			.andExpect(status().isOk());
		mvc.perform(save(table, "Fjord table", "449.00", "{\"top\": \"oak\", \"extendable\": \"yes\"}").with(as("admin")))
			.andExpect(status().isOk());

		mvc.perform(get("/api/catalog/products/{sku}", sofa)) // anyone may look
			.andExpect(jsonPath("$.price").value(1299.90))
			.andExpect(jsonPath("$.attributes.seats").value("3"))
			.andExpect(jsonPath("$.attributes.top").doesNotExist());
		mvc.perform(get("/api/catalog/products")).andExpect(jsonPath("$[?(@.sku == '%s')].attributes.top", table).value("oak"));

		Document stored = mongo.getCollection("product").find(new Document("_id", table)).first();
		assertThat(stored.get("price")).isEqualTo(Decimal128.parse("449.00"));
		assertThat(stored.get("attributes", Document.class)).containsEntry("extendable", "yes");
	}

	@Test
	void savingTheSameSkuReplacesTheProduct() throws Exception {
		mvc.perform(save(sofa, "Oslo sofa", "1299.90", "{\"seats\": \"3\"}").with(as("admin")));
		mvc.perform(save(sofa, "Oslo sofa", "999.00", null).with(as("admin"))).andExpect(status().isOk());

		mvc.perform(get("/api/catalog/products/{sku}", sofa))
			.andExpect(jsonPath("$.price").value(999.00))
			.andExpect(jsonPath("$.attributes").isEmpty());
	}

	@Test
	void onlyAdminsChangeTheCatalog() throws Exception {
		mvc.perform(save(sofa, "Oslo sofa", "1299.90", null)).andExpect(status().isUnauthorized());
		mvc.perform(save(sofa, "Oslo sofa", "1299.90", null).with(as("customer"))).andExpect(status().isForbidden());

		mvc.perform(get("/api/catalog/products/{sku}", sofa)).andExpect(status().isNotFound());
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()); // Kubernetes probes need no token
	}

	@Test
	void invalidProductsAreRejected() throws Exception {
		mvc.perform(save(sofa, "", "1299.90", null).with(as("admin"))).andExpect(status().isBadRequest());
		mvc.perform(save(sofa, "Oslo sofa", "-1", null).with(as("admin"))).andExpect(status().isBadRequest());
		// A dot would turn into a nested field in MongoDB
		mvc.perform(save(sofa, "Oslo sofa", "1299.90", "{\"seat.height\": \"45\"}").with(as("admin")))
			.andExpect(status().isBadRequest());

		mvc.perform(get("/api/catalog/products/{sku}", sofa)).andExpect(status().isNotFound());
	}

	// Signed in with this Keycloak realm role. Its authorities come from the app's own converter (application.yml),
	// so a broken role mapping fails these tests too. Only the token's signature isn't checked.
	private RequestPostProcessor as(String role) {
		return jwt().jwt(token -> token.claim("realm_access", Map.of("roles", List.of(role))))
			.authorities(token -> tokens.convert(token).getAuthorities());
	}

	private static MockHttpServletRequestBuilder save(String sku, String name, String price, String attributes) {
		return put("/api/catalog/products/{sku}", sku).contentType(APPLICATION_JSON).content("""
				{"name": "%s", "category": "Living room", "price": %s, "attributes": %s}"""
			.formatted(name, price, attributes));
	}

}
