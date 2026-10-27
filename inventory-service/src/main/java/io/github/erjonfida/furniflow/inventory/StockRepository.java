package io.github.erjonfida.furniflow.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

// Every stock change is a single atomic UPDATE: no read-then-write, so concurrent requests cannot lose updates.
interface StockRepository extends JpaRepository<Stock, String> {

	//Takes {@code quantity} units only if that many are available. Returns 1 if taken, 0 if not.
	@Modifying
	@Query(value = "UPDATE stock SET available = available - :quantity WHERE sku = :sku AND available >= :quantity",
			nativeQuery = true)
	int take(String sku, int quantity);

	@Modifying
	@Query(value = "UPDATE stock SET available = available + :quantity WHERE sku = :sku", nativeQuery = true)
	void giveBack(String sku, int quantity);

	@Modifying
	@Query(value = """
			INSERT INTO stock (sku, available) VALUES (:sku, :quantity)
			ON CONFLICT (sku) DO UPDATE SET available = stock.available + EXCLUDED.available
			""", nativeQuery = true)
	void restock(String sku, int quantity);

}
