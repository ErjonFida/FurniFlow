package io.github.erjonfida.furniflow.orders;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Reads prices from catalog-service. Browsing the catalog needs no token. */
@Component
class CatalogClient {

	record Product(BigDecimal price) {
	}

	private final RestClient http;

	CatalogClient(RestClient.Builder builder, @Value("${furniflow.catalog-url}") String catalogUrl) {
		this.http = builder.baseUrl(catalogUrl).build();
	}

	// A product taken out of the shop can't be ordered, even if inventory still has units of it
	BigDecimal price(String sku) {
		return http.get()
			.uri("/api/catalog/products/{sku}", sku)
			.retrieve()
			.onStatus(status -> status.isSameCodeAs(NOT_FOUND), (request, response) -> {
				throw new NoSuchElementException("Unknown product " + sku);
			})
			.body(Product.class)
			.price();
	}

}
