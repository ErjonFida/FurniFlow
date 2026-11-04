package io.github.erjonfida.furniflow.orders;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The HTTP contract with catalog-service, against a fake server. */
@RestClientTest(components = CatalogClient.class, properties = "furniflow.catalog-url=http://catalog")
class CatalogClientTests {

	@Autowired
	CatalogClient catalog;

	@Autowired
	MockRestServiceServer server;

	@Test
	void readsTheNameAndExactPriceAndReportsProductsNoLongerInTheShop() {
		server.expect(requestTo("http://catalog/api/catalog/products/SOFA-1")).andRespond(withSuccess("""
				{"sku": "SOFA-1", "name": "Oslo sofa", "category": "sofas", "price": 1299.90, "attributes": {}}
				""", MediaType.APPLICATION_JSON));
		server.expect(requestTo("http://catalog/api/catalog/products/GONE-1"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.body("{\"status\": 404, \"detail\": \"Unknown product GONE-1\"}"));

		// Scale and all, not a double
		assertThat(catalog.product("SOFA-1")).isEqualTo(new CatalogClient.Product("Oslo sofa", new BigDecimal("1299.90")));
		assertThatThrownBy(() -> catalog.product("GONE-1")).isInstanceOf(NoSuchElementException.class)
			.hasMessage("Unknown product GONE-1");
	}

}
