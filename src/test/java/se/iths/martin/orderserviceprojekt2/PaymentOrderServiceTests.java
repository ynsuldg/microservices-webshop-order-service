package se.iths.martin.orderserviceprojekt2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import se.iths.martin.orderserviceprojekt2.Repository.OrderRepository;
import se.iths.martin.orderserviceprojekt2.client.StockReservationClient;
import se.iths.martin.orderserviceprojekt2.dto.*;
import se.iths.martin.orderserviceprojekt2.messaging.OrderMessageProducer;
import se.iths.martin.orderserviceprojekt2.model.OrderStatus;
import se.iths.martin.orderserviceprojekt2.service.PaymentOrderService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class PaymentOrderServiceTests {
    @Autowired private PaymentOrderService service;
    @Autowired private OrderRepository orders;
    @MockitoBean private StockReservationClient stock;
    @MockitoBean private OrderMessageProducer messages;

    // Rensar endast H2-testdatabasen inför varje betalningsorder.
    @BeforeEach
    void setUp() {
        orders.deleteAll();
    }

    // Kontrollerar att samma betalningsorder återanvänds med backendpris och utan extra reservation eller mejl.
    @Test
    void preparesAndRetriesSameOrderWithoutReservingTwice() {
        var id = UUID.randomUUID();
        when(stock.reserve(eq(id), eq("customer"), anyList())).thenReturn(products());

        var first = service.prepare(id, "customer", request());
        var repeated = service.prepare(id, "customer", request());

        assertEquals(first.getId(), repeated.getId());
        assertEquals(OrderStatus.PENDING_PAYMENT, repeated.getStatus());
        assertTrue(repeated.isStockReserved());
        assertEquals(new BigDecimal("25.00"), repeated.getTotalPrice());
        assertEquals(42L, repeated.getOrderItems().get(0).getProductId());
        assertEquals(1, orders.count());
        verify(stock, times(1)).reserve(eq(id), eq("customer"), anyList());
        verifyNoInteractions(messages);
    }

    // Kontrollerar att orderns id överlever ett tappat produktsvar och återanvänds vid återförsök.
    @Test
    void keepsReservationIdAfterFailedRemoteResponse() {
        var id = UUID.randomUUID();
        when(stock.reserve(eq(id), eq("customer"), anyList()))
                .thenThrow(new IllegalStateException("Simulated lost response"))
                .thenReturn(products());

        assertThrows(IllegalStateException.class, () -> service.prepare(id, "customer", request()));
        assertEquals(1, orders.count());
        var order = service.prepare(id, "customer", request());
        assertEquals(id, order.getStockReservationId());
        verify(stock, times(2)).reserve(eq(id), eq("customer"), anyList());
        verifyNoInteractions(messages);
    }

    // Kontrollerar att återställning skickas en gång och hindrar nya försök på den avbrutna ordern.
    @Test
    void releasesPendingOrderOnce() {
        var id = UUID.randomUUID();
        when(stock.reserve(eq(id), eq("customer"), anyList())).thenReturn(products());
        var prepared = service.prepare(id, "customer", request());

        service.release(id, "customer");
        service.release(id, "customer");

        var saved = orders.findById(prepared.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, saved.getStatus());
        assertFalse(saved.isStockReserved());
        verify(stock, times(1)).release(id, "customer");
        assertThrows(IllegalStateException.class, () -> service.prepare(id, "customer", request()));
        verifyNoInteractions(messages);
    }

    // Kontrollerar att ändrad varukorg eller annan kund inte kan återanvända ett reservations-id.
    @Test
    void rejectsChangedCartAndWrongCustomer() {
        var id = UUID.randomUUID();
        when(stock.reserve(eq(id), eq("customer"), anyList())).thenReturn(products());
        service.prepare(id, "customer", request());

        assertThrows(AccessDeniedException.class, () -> service.prepare(id, "other", request()));
        assertThrows(IllegalStateException.class, () -> service.prepare(id, "customer",
                new OrderRequestDTO(List.of(new OrderItemRequestDTO(42L, 3)))));
        verify(stock, times(1)).reserve(eq(id), eq("customer"), anyList());
    }

    // Kontrollerar att ogiltiga antal och dubbletter stoppas innan order eller lager ändras.
    @Test
    void rejectsInvalidRequestsBeforePersistenceOrNetwork() {
        var invalid = List.of(new OrderRequestDTO(List.of()),
                new OrderRequestDTO(List.of(new OrderItemRequestDTO(42L, 0))),
                new OrderRequestDTO(List.of(new OrderItemRequestDTO(42L, -1))),
                new OrderRequestDTO(List.of(new OrderItemRequestDTO(42L, 1), new OrderItemRequestDTO(42L, 1))));
        for (var request : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.prepare(UUID.randomUUID(), "customer", request));
        }
        assertEquals(0, orders.count());
        verifyNoInteractions(stock, messages);
    }

    // Kontrollerar att samma testsession kan sparas igen men inte ersättas av en annan session.
    @Test
    void attachesCheckoutSessionIdempotently() {
        var id = UUID.randomUUID();
        when(stock.reserve(eq(id), eq("customer"), anyList())).thenReturn(products());
        var prepared = service.prepare(id, "customer", request());
        service.attachCheckoutSession(id, "customer", "cs_test_first");
        service.attachCheckoutSession(id, "customer", "cs_test_first");
        assertThrows(IllegalStateException.class,
                () -> service.attachCheckoutSession(id, "customer", "cs_test_other"));
        assertThrows(IllegalArgumentException.class,
                () -> service.attachCheckoutSession(id, "customer", "cs_live_invalid"));
        assertEquals("cs_test_first", orders.findById(prepared.getId()).orElseThrow().getStripeCheckoutSessionId());
    }

    // Kontrollerar att en verifierad betalning förbrukar reservationen och publicerar ordermejlet en gång.
    @Test
    void confirmsPaymentOnceForRepeatedCallbacks() {
        var id = UUID.randomUUID();
        when(stock.reserve(eq(id), eq("customer"), anyList())).thenReturn(products());
        var prepared = service.prepare(id, "customer", request());
        service.confirmPayment(id, "cs_test_paid", 2500L, "usd");
        service.confirmPayment(id, "cs_test_paid", 2500L, "usd");
        var saved = orders.findById(prepared.getId()).orElseThrow();
        assertEquals(OrderStatus.PAID, saved.getStatus());
        assertFalse(saved.isStockReserved());
        assertFalse(saved.isConfirmationPending());
        verify(stock, times(1)).consume(id, "customer");
        verify(messages, times(1)).sendOrderConfirmation(any());
        assertThrows(IllegalStateException.class, () -> service.release(id, "customer"));
    }

    // Kontrollerar att fel belopp eller valuta inte betalar ordern eller förbrukar reservationen.
    @Test
    void rejectsWrongPaymentAmountAndCurrency() {
        var id = UUID.randomUUID();
        when(stock.reserve(eq(id), eq("customer"), anyList())).thenReturn(products());
        var prepared = service.prepare(id, "customer", request());
        assertThrows(IllegalArgumentException.class, () -> service.confirmPayment(id, "cs_test_paid", 1L, "usd"));
        assertThrows(IllegalArgumentException.class, () -> service.confirmPayment(id, "cs_test_paid", 2500L, "sek"));
        assertEquals(OrderStatus.PENDING_PAYMENT, orders.findById(prepared.getId()).orElseThrow().getStatus());
        verify(stock, never()).consume(any(), any());
        verifyNoInteractions(messages);
    }

    // Kontrollerar att en utgången session återställer lagret en gång och inte skickar ordermejl.
    @Test
    void expiresPaymentOnce() {
        var id = UUID.randomUUID();
        when(stock.reserve(eq(id), eq("customer"), anyList())).thenReturn(products());
        var prepared = service.prepare(id, "customer", request());
        service.expirePayment(id, "cs_test_expired");
        service.expirePayment(id, "cs_test_expired");
        assertEquals(OrderStatus.CANCELLED, orders.findById(prepared.getId()).orElseThrow().getStatus());
        verify(stock, times(1)).release(id, "customer");
        verifyNoInteractions(messages);
    }

    // Kontrollerar att betald order bevaras när mejlpublicering misslyckas och att webhooken kan försöka igen.
    @Test
    void retriesPendingConfirmationAfterPublishFailure() {
        var id = UUID.randomUUID();
        when(stock.reserve(eq(id), eq("customer"), anyList())).thenReturn(products());
        var prepared = service.prepare(id, "customer", request());
        doThrow(new org.springframework.amqp.AmqpException("Simulated publish failure")).doNothing()
                .when(messages).sendOrderConfirmation(any());
        assertThrows(org.springframework.amqp.AmqpException.class,
                () -> service.confirmPayment(id, "cs_test_paid", 2500L, "usd"));
        var saved = orders.findById(prepared.getId()).orElseThrow();
        assertEquals(OrderStatus.PAID, saved.getStatus());
        assertTrue(saved.isConfirmationPending());
        service.confirmPayment(id, "cs_test_paid", 2500L, "usd");
        assertFalse(orders.findById(prepared.getId()).orElseThrow().isConfirmationPending());
        verify(stock, times(1)).consume(id, "customer");
    }

    // Skapar testets varukorg utan klientstyrda priser.
    private OrderRequestDTO request() {
        return new OrderRequestDTO(List.of(new OrderItemRequestDTO(42L, 2)));
    }

    // Skapar det mockade produktsvaret med pris från backend.
    private List<ProductInfoDTO> products() {
        return List.of(new ProductInfoDTO(42L, "Product", new BigDecimal("12.50"), 2));
    }
}
