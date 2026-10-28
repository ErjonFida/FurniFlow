package io.github.erjonfida.furniflow.orders;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTests {

	@Mock
	OrderRepository orders;

	@Mock
	InventoryClient inventory;

	@Mock
	Outbox outbox;

	@InjectMocks
	OrderService service;

	@Test
	void losingASameKeyRaceGivesTheReservationBack() {
		Order winner = new Order("key-1", "SOFA-1", 1, UUID.randomUUID(), Instant.now());
		UUID ours = UUID.randomUUID();
		when(orders.findByIdempotencyKey("key-1")).thenReturn(Optional.empty(), Optional.of(winner));
		when(inventory.reserve("SOFA-1", 1)).thenReturn(new InventoryClient.Reservation(ours, Instant.now()));
		when(orders.saveAndFlush(any())).thenThrow(DataIntegrityViolationException.class);

		assertThat(service.place("key-1", "SOFA-1", 1)).isSameAs(winner);
		verify(inventory).release(ours);
	}

	@Test
	void payingTwiceSendsOneEvent() {
		Order paid = new Order("key-1", "SOFA-1", 1, UUID.randomUUID(), Instant.now().plusSeconds(60));
		paid.markPaid();
		when(orders.findById(any())).thenReturn(Optional.of(paid));

		service.pay(UUID.randomUUID());

		verifyNoInteractions(outbox);
	}

}
