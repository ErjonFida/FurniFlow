package io.github.erjonfida.furniflow.inventory;

import java.util.NoSuchElementException;

import tools.jackson.core.JacksonException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

@SpringBootApplication
@EnableScheduling
public class InventoryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(InventoryServiceApplication.class, args);
	}

	// Spring Kafka's default drops a failed event after 10 tries with no pause, so a database that is away for a few
	// seconds would lose a paid order's confirmation. Keep trying instead, up to 30 seconds apart.
	@Bean
	DefaultErrorHandler orderEventRetries() {
		DefaultErrorHandler retries = new DefaultErrorHandler(new ExponentialBackOff(1_000, 2));
		// No retry fixes a broken event, an unknown reservation or units sold meanwhile: those are logged and skipped
		retries.addNotRetryableExceptions(JacksonException.class, NullPointerException.class,
				NoSuchElementException.class, InsufficientStockException.class);
		return retries;
	}

}
