package io.github.erjonfida.furniflow.orders;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Transactional outbox: an event is saved in the same transaction as the change it describes, then sent to Kafka.
// Writing to both directly can't be atomic: a crash in between loses the event or announces a change that rolled back.
@Component
class Outbox {

	record Message(String topic, String key, String payload) {
	}

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final KafkaTemplate<String, String> kafka;

	Outbox(JdbcTemplate jdbc, JsonMapper json, KafkaTemplate<String, String> kafka) {
		this.jdbc = jdbc;
		this.json = json;
		this.kafka = kafka;
	}

	// MANDATORY: fails fast if called outside the transaction making the change
	@Transactional(propagation = Propagation.MANDATORY)
	public void add(String topic, UUID key, Object event) {
		jdbc.update("INSERT INTO outbox (topic, message_key, payload) VALUES (?, ?, ?)", topic, key.toString(),
				json.writeValueAsString(event));
	}

	// Delete and send in one transaction: a failed send rolls the delete back, and a crash before the commit sends
	// twice, so delivery is at least once. SKIP LOCKED lets several instances run this without sharing rows.
	@Scheduled(fixedDelay = 500)
	@Transactional
	public void publish() {
		List<Message> batch = jdbc.query("""
				WITH batch AS (
				    DELETE FROM outbox
				    WHERE id IN (SELECT id FROM outbox ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED)
				    RETURNING id, topic, message_key, payload
				)
				SELECT * FROM batch ORDER BY id
				""", (row, i) -> new Message(row.getString("topic"), row.getString("message_key"),
				row.getString("payload")));
		for (Message message : batch) {
			// join() rather than get(): its exception is unchecked, and only unchecked exceptions roll back
			// One send at a time (a few hundred events/s); send the batch and join them all if orders outgrow that
			kafka.send(message.topic(), message.key(), message.payload()).join();
		}
	}

}
