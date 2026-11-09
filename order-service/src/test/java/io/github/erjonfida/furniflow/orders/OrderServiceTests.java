package io.github.erjonfida.furniflow.orders;

import java.math.BigDecimal;
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
import org.springframework.web.client.ResourceAccessException;

import io.github.erjonfida.furniflow.orders.OrderService.Item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTests {

	@Mock
	OrderRepository orders;

	@Mock
	CatalogClient catalog;

	@Mock
	InventoryClient inventory;

	@Mock
	Outbox outbox;

	@InjectMocks
	OrderService service;

	@Test
	void losingASameKeyRaceGivesTheReservationBack() {
		Order winner = new Order("alice", "Alice", "key-1",
				List.of(new Order.Line("SOFA-1", "Oslo sofa", 1, BigDecimal.TEN, UUID.randomUUID())), Instant.now());
		UUID ours = UUID.randomUUID();
		when(catalog.product("SOFA-1")).thenReturn(new CatalogClient.Product("Oslo sofa", BigDecimal.TEN));
		when(orders.findByCustomerIdAndIdempotencyKey("alice", "key-1"))
			.thenReturn(Optional.empty(), Optional.of(winner));
		when(inventory.reserve("SOFA-1", 1)).thenReturn(new InventoryClient.Reservation(ours, Instant.now()));
		when(orders.saveAndFlush(any())).thenThrow(DataIntegrityViolationException.class);
		// The order exists, so the customer gets it even if inventory can't take the units back right now
		doThrow(new ResourceAccessException("inventory is down")).when(inventory).release(ours);

		assertThat(service.place("alice", "Alice", "key-1", List.of(new Item("SOFA-1", 1)))).isSameAs(winner);
		verify(inventory).release(ours);
	}

	@Test
	void aLineThatCantBeReservedGivesTheEarlierLinesBack() {
		UUID chairs = UUID.randomUUID();
		UUID tables = UUID.randomUUID();
		when(catalog.product(any())).thenReturn(new CatalogClient.Product("A product", BigDecimal.TEN));
		when(inventory.reserve("CHAIR-1", 4)).thenReturn(new InventoryClient.Reservation(chairs, Instant.now()));
		when(inventory.reserve("TABLE-1", 1)).thenReturn(new InventoryClient.Reservation(tables, Instant.now()));
		when(inventory.reserve("SOFA-1", 1)).thenThrow(new ErrorResponseException(HttpStatus.CONFLICT));
		doThrow(new ResourceAccessException("inventory is down")).when(inventory).release(chairs);

		// Still "out of stock", not the failed release, and the tables go back although the chairs couldn't
		assertThatThrownBy(() -> service.place("alice", "Alice", "key-1",
				List.of(new Item("CHAIR-1", 4), new Item("TABLE-1", 1), new Item("SOFA-1", 1))))
			.isInstanceOfSatisfying(ErrorResponseException.class,
					e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
		verify(inventory).release(tables);
		verify(orders, never()).saveAndFlush(any());
	}

	@Test
	void payingTwiceSendsOneEvent() {
		Order paid = new Order("alice", "Alice", "key-1",
				List.of(new Order.Line("SOFA-1", "Oslo sofa", 1, BigDecimal.TEN, UUID.randomUUID())),
				Instant.now().plusSeconds(60));
		paid.markPaid();
		when(orders.findForUpdateByIdAndCustomerId(any(), any())).thenReturn(Optional.of(paid));

		service.pay(UUID.randomUUID(), "alice");

		verifyNoInteractions(outbox);
	}

	@Test
	void theTotalIsExactAndUnknownForOrdersFromBeforePricesWereKept() {
		// 0.1 * 3 isn't 0.3 in doubles
		List<Order.Line> lines = List.of(
				new Order.Line("SOFA-1", "Oslo sofa", 2, new BigDecimal("1299.90"), UUID.randomUUID()),
				new Order.Line("CHAIR-1", "Oak chair", 3, new BigDecimal("0.10"), UUID.randomUUID()));
		assertThat(new Order("alice", "Alice", "key-1", lines, Instant.now()).getTotal())
			.isEqualByComparingTo("2600.10");

		Order old = new Order("alice", "Alice", "key-2",
				List.of(new Order.Line("SOFA-1", null, 1, null, UUID.randomUUID())), Instant.now());
		assertThat(old.getTotal()).isNull();
	}

}
