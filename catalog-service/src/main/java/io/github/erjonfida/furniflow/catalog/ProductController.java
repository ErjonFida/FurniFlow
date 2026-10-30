package io.github.erjonfida.furniflow.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog/products")
class ProductController {

	// Attribute names become MongoDB field names, which must not contain dots or start with $
	record ProductRequest(@NotBlank String name, @NotBlank String category, @NotNull @Positive BigDecimal price,
			String description, Map<@Pattern(regexp = "[\\w -]+") String, @NotBlank String> attributes) {
	}

	private final ProductRepository products;

	ProductController(ProductRepository products) {
		this.products = products;
	}

	// ponytail: the whole catalog in one response; add paging when it grows past a few hundred products
	@GetMapping
	List<Product> all() {
		return products.findAll(Sort.by("name"));
	}

	@GetMapping("/{sku}")
	Product get(@PathVariable String sku) {
		return products.findById(sku).orElseThrow(() -> new NoSuchElementException("Unknown product " + sku));
	}

	// Creates the product or replaces it. Its stock is kept by inventory-service.
	@PutMapping("/{sku}")
	Product save(@PathVariable String sku, @Valid @RequestBody ProductRequest request) {
		return products.save(new Product(sku, request.name(), request.category(), request.price(),
				request.description(), Objects.requireNonNullElse(request.attributes(), Map.of())));
	}

	@ExceptionHandler
	ProblemDetail notFound(NoSuchElementException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

}
