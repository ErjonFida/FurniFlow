package io.github.erjonfida.furniflow.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.bson.Document;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Fills the shop with demo products, so a new install has something to show. Inventory-service holds their units
 * (R__demo_stock.sql). Each one is offered once, and noted by SKU: a product an admin deleted stays deleted, one added
 * to the file later still reaches a catalog that has the others, and a product that's already there is left alone.
 * <p>
 * ponytail: no switch to turn this off, because this is a demo shop; add a property before selling real furniture
 */
@Component
class DemoProducts implements ApplicationRunner {

	static final String OFFERED = "demo_products_added";

	private final ProductRepository products;

	private final MongoTemplate mongo;

	private final JsonMapper json;

	DemoProducts(ProductRepository products, MongoTemplate mongo, JsonMapper json) {
		this.products = products;
		this.mongo = mongo;
		this.json = json;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		List<Product> demo;
		try (InputStream file = new ClassPathResource("demo-products.json").getInputStream()) {
			demo = json.readValue(file, new TypeReference<>() {
			});
		}
		for (Product product : demo) {
			if (!mongo.exists(query(where("_id").is(product.sku())), OFFERED)) {
				if (!products.existsById(product.sku())) {
					products.save(product);
				}
				mongo.save(new Document("_id", product.sku()), OFFERED);
			}
		}
	}

}
