package io.github.erjonfida.furniflow.orders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

import io.github.erjonfida.furniflow.orders.OrderService.Item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
		Order winner = new Order("alice", "Alice", "key-1", List.of(new Order.Line("SOFA-1", 1, UUID.randomUUID())),
				Instant.now());
		UUID ours = UUID.randomUUID();
		when(orders.findByCustomerIdAndIdempotencyKey("alice", "key-1"))
			.thenReturn(Optional.empty(), Optional.of(winner));
		when(inventory.reserve("SOFA-1", 1)).thenReturn(new InventoryClient.Reservation(ours, Instant.now()));
		when(orders.saveAndFlush(any())).thenThrow(DataIntegrityViolationException.class);

		assertThat(service.place("alice", "Alice", "key-1", List.of(new Item("SOFA-1", 1)))).isSameAs(winner);
		verify(inventory).release(ours);
	}

	@Test
	void aLineThatCantBeReservedGivesTheEarlierLinesBack() {
		UUID chairs = UUID.randomUUID();
		when(inventory.reserve("CHAIR-1", 4)).thenReturn(new InventoryClient.Reservation(chairs, Instant.now()));
		when(inventory.reserve("SOFA-1", 1)).thenThrow(new ErrorResponseException(HttpStatus.CONFLICT));

		assertThatThrownBy(() -> service.place("alice", "Alice", "key-1",
				List.of(new Item("CHAIR-1", 4), new Item("SOFA-1", 1)))).isInstanceOf(ErrorResponseException.class);
		verify(inventory).release(chairs);
		verify(orders, never()).saveAndFlush(any());
	}

	@Test
	void payingTwiceSendsOneEvent() {
		Order paid = new Order("alice", "Alice", "key-1", List.of(new Order.Line("SOFA-1", 1, UUID.randomUUID())),
				Instant.now().plusSeconds(60));
		paid.markPaid();
		when(orders.findForUpdateByIdAndCustomerId(any(), any())).thenReturn(Optional.of(paid));

		service.pay(UUID.randomUUID(), "alice");

		verifyNoInteractions(outbox);
	}

}
