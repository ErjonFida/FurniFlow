package io.github.erjonfida.furniflow.inventory;

public class InsufficientStockException extends RuntimeException {

	InsufficientStockException(String sku, int requested) {
		super("Not enough stock of " + sku + " to reserve " + requested);
	}

}
