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

/**
 * Transactional outbox. An event is saved in the same database transaction as the change it describes, and a
 * scheduled job sends it to Kafka afterwards. Writing to the database and to Kafka directly can't be atomic: a crash
 * between the two would lose the event, or send one for a change that rolled back.
 */
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

	/**
	 * Deletes the oldest rows and sends them, in one transaction: if a send fails, the delete rolls back and the rows
	 * go out on the next run. A crash after sending but before the commit sends them twice, so delivery is at least
	 * once and consumers must tolerate repeats. SKIP LOCKED lets several instances run this without sharing rows.
	 */
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
			// ponytail: one send at a time (a few hundred events/s); send the batch and join them all if orders outgrow that
			kafka.send(message.topic(), message.key(), message.payload()).join();
		}
	}

}
