package io.github.erjonfida.furniflow.orders;

import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrderServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderServiceApplication.class, args);
	}

	// Events are keyed by order ID, so all events for one order land on the same partition, in order
	@Bean
	NewTopic orderPaidTopic() {
		return TopicBuilder.name(OrderService.ORDER_PAID).partitions(3).build();
	}

}
