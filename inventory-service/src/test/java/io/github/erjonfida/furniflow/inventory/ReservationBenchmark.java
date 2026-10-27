package io.github.erjonfida.furniflow.inventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The overselling experiment: {@value #BUYERS} buyers hit one SKU with {@value #STOCK} units at the same moment,
 * each trying to take one unit. Four ways of taking stock, same database, same connection pool (HikariCP, 10).
 * <p>
 * Not part of {@code ./mvnw verify} (Surefire only runs *Test and *Tests classes). Run it with:
 * {@code ./mvnw -pl inventory-service test -Dtest=ReservationBenchmark}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class ReservationBenchmark {

	static final int STOCK = 100;

	static final int BUYERS = 500;

	static final int ROUNDS = 5;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	final AtomicInteger retries = new AtomicInteger();

	// Read, check, write. Two buyers can read the same number and both take "the last" unit
	boolean naive(String sku) {
		int available = jdbc.queryForObject("SELECT available FROM stock WHERE sku = ?", Integer.class, sku);
		if (available < 1) {
			return false;
		}
		jdbc.update("UPDATE stock SET available = ? WHERE sku = ?", available - 1, sku);
		return true;
	}

	//Write only if the row still holds what we read; otherwise read again. JPA does this with @Version
	boolean optimistic(String sku) {
		while (true) {
			int available = jdbc.queryForObject("SELECT available FROM stock WHERE sku = ?", Integer.class, sku);
			if (available < 1) {
				return false;
			}
			if (jdbc.update("UPDATE stock SET available = ? WHERE sku = ? AND available = ?", available - 1, sku,
					available) == 1) {
				return true;
			}
			retries.incrementAndGet();
		}
	}

	//Lock the row before reading it, so buyers queue up one by one. JPA: @Lock(PESSIMISTIC_WRITE)
	boolean pessimistic(String sku) {
		return tx.execute(status -> {
			int available = jdbc.queryForObject("SELECT available FROM stock WHERE sku = ? FOR UPDATE", Integer.class,
					sku);
			if (available < 1) {
				return false;
			}
			jdbc.update("UPDATE stock SET available = ? WHERE sku = ?", available - 1, sku);
			return true;
		});
	}

	// Check and write in one statement, inside the database. The same SQL as StockRepository.take. 
	boolean conditionalUpdate(String sku) {
		return jdbc.update("UPDATE stock SET available = available - 1 WHERE sku = ? AND available >= 1", sku) == 1;
	}

	record Round(int oversold, double throughput, double p95Millis, int retries) {
	}

	@Test
	void compareStrategies() throws Exception {
		Map<String, Predicate<String>> strategies = new LinkedHashMap<>();
		strategies.put("Naive read-then-write", this::naive);
		strategies.put("Optimistic (compare-and-set, retry)", this::optimistic);
		strategies.put("Pessimistic (SELECT ... FOR UPDATE)", this::pessimistic);
		strategies.put("Conditional UPDATE", this::conditionalUpdate);

		for (Predicate<String> strategy : strategies.values()) {
			round(strategy); // warm-up (JIT, connection pool); results discarded
		}

		StringBuilder table = new StringBuilder("""
				| Strategy | Oversold | Throughput (req/s) | p95 latency (ms) | Retries |
				|---|--:|--:|--:|--:|
				""");
		for (var strategy : strategies.entrySet()) {
			List<Round> rounds = new ArrayList<>();
			for (int i = 0; i < ROUNDS; i++) {
				rounds.add(round(strategy.getValue()));
			}
			// Medians over the rounds
			table.append(String.format(Locale.ROOT, "| %s | %.0f | %.0f | %.1f | %.0f |%n", strategy.getKey(),
					median(rounds, Round::oversold), median(rounds, Round::throughput),
					median(rounds, Round::p95Millis), median(rounds, Round::retries)));
			if (!strategy.getKey().startsWith("Naive")) {
				assertThat(rounds).allSatisfy(r -> assertThat(r.oversold()).isZero());
			}
		}

		System.out.println(table);
		Files.writeString(Path.of("target", "benchmark.md"), table);
	}

	Round round(Predicate<String> strategy) throws Exception {
		String sku = "BENCH-" + UUID.randomUUID();
		jdbc.update("INSERT INTO stock (sku, available) VALUES (?, ?)", sku, STOCK);
		retries.set(0);
		AtomicInteger taken = new AtomicInteger();
		long[] latencies = new long[BUYERS];
		CountDownLatch go = new CountDownLatch(1);
		List<Future<?>> buyers = new ArrayList<>();

		long start;
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < BUYERS; i++) {
				int buyer = i;
				buyers.add(pool.submit(() -> {
					go.await(); // everyone starts at once
					long t = System.nanoTime();
					if (strategy.test(sku)) {
						taken.incrementAndGet();
					}
					latencies[buyer] = System.nanoTime() - t;
					return null;
				}));
			}
			start = System.nanoTime();
			go.countDown();
		} // close() waits for every buyer
		long elapsed = System.nanoTime() - start;
		for (Future<?> buyer : buyers) {
			buyer.get(); // rethrows anything unexpected
		}

		Arrays.sort(latencies);
		return new Round(Math.max(0, taken.get() - STOCK), BUYERS / (elapsed / 1e9),
				latencies[BUYERS * 95 / 100] / 1e6, retries.get());
	}

	static double median(List<Round> rounds, ToDoubleFunction<Round> metric) {
		double[] values = rounds.stream().mapToDouble(metric).sorted().toArray();
		return values[values.length / 2];
	}

}
