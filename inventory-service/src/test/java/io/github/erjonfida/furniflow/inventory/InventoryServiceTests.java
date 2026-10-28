package io.github.erjonfida.furniflow.inventory;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static io.github.erjonfida.furniflow.inventory.Reservation.Status.ACTIVE;
import static io.github.erjonfida.furniflow.inventory.Reservation.Status.CONFIRMED;
import static io.github.erjonfida.furniflow.inventory.Reservation.Status.EXPIRED;
import static io.github.erjonfida.furniflow.inventory.Reservation.Status.RELEASED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTests {

	@Mock
	StockRepository stock;

	@Mock
	ReservationRepository reservations;

	@InjectMocks
	InventoryService inventory;

	@Test
	void reservingAnUnknownSkuIsNotFound() {
		when(stock.take("SOFA-1", 1)).thenReturn(0);
		when(stock.existsById("SOFA-1")).thenReturn(false);

		assertThatThrownBy(() -> inventory.reserve("SOFA-1", 1)).isInstanceOf(NoSuchElementException.class);
		verifyNoInteractions(reservations);
	}

	@Test
	void reservingMoreThanAvailableIsRejected() {
		when(stock.take("SOFA-1", 5)).thenReturn(0);
		when(stock.existsById("SOFA-1")).thenReturn(true);

		assertThatThrownBy(() -> inventory.reserve("SOFA-1", 5)).isInstanceOf(InsufficientStockException.class);
		verifyNoInteractions(reservations);
	}

	@Test
	void releasingAnAlreadyFinishedReservationDoesNotGiveStockBackAgain() {
		UUID id = UUID.randomUUID();
		when(reservations.findById(id)).thenReturn(Optional.of(new Reservation("SOFA-1", 2, Instant.now())));
		when(reservations.transition(id, ACTIVE, RELEASED)).thenReturn(0);

		inventory.release(id);

		verify(stock, never()).giveBack(any(), anyInt());
	}

	@Test
	void aRepeatedOrderPaidEventChangesNothing() {
		UUID id = UUID.randomUUID();
		// Already CONFIRMED: neither transition matches (an unstubbed mock returns 0)
		when(reservations.findById(id)).thenReturn(Optional.of(new Reservation("SOFA-1", 2, Instant.now())));

		inventory.confirm(id);

		verify(stock, never()).take(any(), anyInt());
	}

	@Test
	void payingAfterTheHoldRanOutTakesTheUnitsAgain() {
		UUID id = UUID.randomUUID();
		when(reservations.findById(id)).thenReturn(Optional.of(new Reservation("SOFA-1", 2, Instant.now())));
		when(reservations.transition(id, ACTIVE, CONFIRMED)).thenReturn(0);
		when(reservations.transition(id, EXPIRED, CONFIRMED)).thenReturn(1);
		when(stock.take("SOFA-1", 2)).thenReturn(1);

		inventory.confirm(id);

		verify(stock).take("SOFA-1", 2);
	}

	@Test
	void payingAfterTheUnitsSoldElsewhereIsRefused() {
		UUID id = UUID.randomUUID();
		when(reservations.findById(id)).thenReturn(Optional.of(new Reservation("SOFA-1", 2, Instant.now())));
		when(reservations.transition(id, ACTIVE, CONFIRMED)).thenReturn(0);
		when(reservations.transition(id, EXPIRED, CONFIRMED)).thenReturn(1);
		when(stock.take("SOFA-1", 2)).thenReturn(0);

		assertThatThrownBy(() -> inventory.confirm(id)).isInstanceOf(InsufficientStockException.class);
	}

}
