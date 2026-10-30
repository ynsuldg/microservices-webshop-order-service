package se.iths.martin.orderserviceprojekt2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import se.iths.martin.orderserviceprojekt2.Repository.OrderRepository;
import se.iths.martin.orderserviceprojekt2.client.ProductClient;
import se.iths.martin.orderserviceprojekt2.dto.OrderItemRequestDTO;
import se.iths.martin.orderserviceprojekt2.dto.OrderRequestDTO;
import se.iths.martin.orderserviceprojekt2.dto.ProductInfoDTO;
import se.iths.martin.orderserviceprojekt2.messaging.OrderMessageProducer;
import se.iths.martin.orderserviceprojekt2.model.Order;
import se.iths.martin.orderserviceprojekt2.model.OrderStatus;
import se.iths.martin.orderserviceprojekt2.service.OrderService;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderPaymentPreparationTests {
    @Mock private OrderRepository orderRepository;
    @Mock private ProductClient productClient;
    @Mock private OrderMessageProducer messageProducer;
    private OrderService orderService;

    // Skapar en tjänst med mockade beroenden utan riktig databas eller lagerminskning.
    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, productClient, messageProducer);
    }

    // Kontrollerar status, produkt-id, backendpris och att ordermejl inte skickas före betalning.
    @Test
    void preparesPendingOrderWithoutSendingEmail() {
        var request = new OrderRequestDTO(List.of(new OrderItemRequestDTO(42L, 2)));
        when(productClient.decreaseStock(request, "Bearer test"))
                .thenReturn(List.of(new ProductInfoDTO(42L, "Product", new BigDecimal("12.50"), 2)));
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> {
            Order order = call.getArgument(0);
            order.setId(1L);
            order.getOrderItems().get(0).setId(99L);
            return order;
        });

        var response = orderService.prepareOrderForPayment(request, "user@example.com", "Bearer test");

        var captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertEquals(OrderStatus.PENDING_PAYMENT, captor.getValue().getStatus());
        assertEquals(42L, captor.getValue().getOrderItems().get(0).getProductId());
        assertEquals(42L, response.getItems().get(0).getProductId());
        assertEquals(new BigDecimal("25.00"), response.getTotalPrice());
        verify(productClient).decreaseStock(request, "Bearer test");
        verifyNoInteractions(messageProducer);
    }

    // Kontrollerar att den befintliga ordervägen fortfarande skickar sin bekräftelse.
    @Test
    void regularOrderStillUsesCreatedStatusAndSendsEmail() {
        var request = new OrderRequestDTO(List.of(new OrderItemRequestDTO(42L, 1)));
        when(productClient.decreaseStock(request, "Bearer test"))
                .thenReturn(List.of(new ProductInfoDTO(42L, "Product", BigDecimal.TEN, 1)));
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));

        var response = orderService.createOrder(request, "user@example.com", "Bearer test");

        var captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertEquals(OrderStatus.CREATED, captor.getValue().getStatus());
        verify(messageProducer).sendOrderConfirmation(response);
    }

    // Kontrollerar att tom varukorg, ogiltiga antal och dubbletter nekas före lagerminskning.
    @Test
    void rejectsInvalidPaymentRequestsBeforeChangingStock() {
        var invalidRequests = List.of(
                new OrderRequestDTO(List.of()),
                new OrderRequestDTO(List.of(new OrderItemRequestDTO(42L, 0))),
                new OrderRequestDTO(List.of(new OrderItemRequestDTO(42L, -1))),
                new OrderRequestDTO(List.of(new OrderItemRequestDTO(42L, 1), new OrderItemRequestDTO(42L, 1)))
        );

        for (var request : invalidRequests) {
            assertThrows(IllegalArgumentException.class,
                    () -> orderService.prepareOrderForPayment(request, "user@example.com", "Bearer test"));
        }
        verifyNoInteractions(productClient, orderRepository, messageProducer);
    }
}
