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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

	@Autowired
	DemoProducts demoProducts;

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
	void adminsDeleteProducts() throws Exception {
		mvc.perform(save(sofa, "Oslo sofa", "1299.90", null).with(as("admin")));

		mvc.perform(delete("/api/catalog/products/{sku}", sofa).with(as("customer"))).andExpect(status().isForbidden());
		mvc.perform(delete("/api/catalog/products/{sku}", sofa).with(as("admin"))).andExpect(status().isNoContent());
		mvc.perform(get("/api/catalog/products/{sku}", sofa)).andExpect(status().isNotFound());
	}

	@Test
	void productsKeepTheirImageAddress() throws Exception {
		mvc.perform(saveWithImage(sofa, "https://images.example.com/sofa.jpg").with(as("admin"))).andExpect(status().isOk());
		mvc.perform(get("/api/catalog/products/{sku}", sofa))
			.andExpect(jsonPath("$.imageUrl").value("https://images.example.com/sofa.jpg"));

		mvc.perform(saveWithImage(table, "javascript:alert(1)").with(as("admin"))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/catalog/products/{sku}", table)).andExpect(status().isNotFound());
	}

	@Test
	void aNewCatalogHasPhotographedDemoProductsInEveryRoom() throws Exception {
		for (String room : List.of("Living room", "Dining room", "Bedroom", "Office", "Outdoor", "Hallway", "Kitchen",
				"Kids' room", "Bathroom", "Home decor")) {
			mvc.perform(get("/api/catalog/products"))
				.andExpect(jsonPath("$[?(@.category == \"%s\" && @.imageUrl =~ /https:.*/)]", room).value(hasSize(greaterThanOrEqualTo(4))));
		}
	}

	@Test
	void aDeletedDemoProductStaysDeleted() throws Exception {
		mvc.perform(delete("/api/catalog/products/{sku}", "BENCH-ALM").with(as("admin"))).andExpect(status().isNoContent());

		demoProducts.run(null); // what the next start does

		mvc.perform(get("/api/catalog/products/{sku}", "BENCH-ALM")).andExpect(status().isNotFound());
	}

	@Test
	void aDemoProductAddedToTheFileLaterStillArrives() throws Exception {
		mongo.remove(query(where("_id").is("VASE-LEIRE")), DemoProducts.OFFERED);
		mvc.perform(delete("/api/catalog/products/{sku}", "VASE-LEIRE").with(as("admin"))).andExpect(status().isNoContent());

		demoProducts.run(null);

		mvc.perform(get("/api/catalog/products/{sku}", "VASE-LEIRE")).andExpect(jsonPath("$.name").value("Leire ceramic vase"));
	}

	@Test
	void aProductThatsAlreadyThereIsLeftAlone() throws Exception {
		mongo.remove(query(where("_id").is("TBL-EIK")), DemoProducts.OFFERED);
		mvc.perform(save("TBL-EIK", "Our own table", "10.00", null).with(as("admin"))).andExpect(status().isOk());

		demoProducts.run(null);

		mvc.perform(get("/api/catalog/products/{sku}", "TBL-EIK")).andExpect(jsonPath("$.name").value("Our own table"));
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

	// Authorities come from the app's own converter (application.yml), so a broken role mapping fails these tests too
	private RequestPostProcessor as(String role) {
		return jwt().jwt(token -> token.claim("realm_access", Map.of("roles", List.of(role))))
			.authorities(token -> tokens.convert(token).getAuthorities());
	}

	private static MockHttpServletRequestBuilder save(String sku, String name, String price, String attributes) {
		return put("/api/catalog/products/{sku}", sku).contentType(APPLICATION_JSON).content("""
				{"name": "%s", "category": "Living room", "price": %s, "attributes": %s}"""
			.formatted(name, price, attributes));
	}

	private static MockHttpServletRequestBuilder saveWithImage(String sku, String imageUrl) {
		return put("/api/catalog/products/{sku}", sku).contentType(APPLICATION_JSON).content("""
				{"name": "Oslo sofa", "category": "Living room", "price": 1299.90, "imageUrl": "%s"}""".formatted(imageUrl));
	}

}
