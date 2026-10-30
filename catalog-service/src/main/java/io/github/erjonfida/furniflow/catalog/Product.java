package io.github.erjonfida.furniflow.catalog;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

// One document per product. Each kind of furniture has its own attributes (a sofa has seats, a table a top),
// so they live in a nested map instead of a column per attribute. The price is stored as an exact Decimal128.
@Document
public record Product(@Id String sku, String name, String category, BigDecimal price, String description,
		Map<String, String> attributes) {
}
