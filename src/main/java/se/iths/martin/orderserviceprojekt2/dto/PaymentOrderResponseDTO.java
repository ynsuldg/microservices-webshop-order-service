package se.iths.martin.orderserviceprojekt2.dto;

import se.iths.martin.orderserviceprojekt2.model.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Orderögonblicksbild för betrodda betalningsanrop, utan JPA-entiteter i HTTP-svaret. */
public record PaymentOrderResponseDTO(Long id, UUID reservationId, String customerName,
                                      OrderStatus status, boolean stockReserved,
                                      String stripeCheckoutSessionId, LocalDateTime orderDate,
                                      java.time.Instant checkoutRequestedAt,
                                      BigDecimal totalPrice, List<OrderItemResponseDTO> items) { }
