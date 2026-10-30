package se.iths.martin.orderserviceprojekt2.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import se.iths.martin.orderserviceprojekt2.Repository.OrderRepository;
import se.iths.martin.orderserviceprojekt2.client.StockReservationClient;
import se.iths.martin.orderserviceprojekt2.dto.OrderRequestDTO;
import se.iths.martin.orderserviceprojekt2.dto.PaymentOrderResponseDTO;
import se.iths.martin.orderserviceprojekt2.dto.OrderItemResponseDTO;
import se.iths.martin.orderserviceprojekt2.model.Order;
import se.iths.martin.orderserviceprojekt2.model.OrderItem;
import se.iths.martin.orderserviceprojekt2.model.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Förbereder betalningsorder med beständiga reservations-id:n och återförsökbara produktanrop. */
@Service
public class PaymentOrderService {
    private final OrderRepository orders;
    private final StockReservationClient stock;
    private final TransactionTemplate transactions;

    // Skapar separata lokala transaktioner så att orderns id finns kvar även om ett produktanrop misslyckas.
    public PaymentOrderService(OrderRepository orders, StockReservationClient stock, PlatformTransactionManager manager) {
        this.orders = orders;
        this.stock = stock;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // Sparar avsikten före nätverksanropet och återanvänder samma reservation för samma kund och varukorg.
    public Order prepare(UUID reservationId, String customerName, OrderRequestDTO request) {
        validate(reservationId, customerName, request);
        try {
            transactions.executeWithoutResult(status -> {
                var existing = orders.findLockedByStockReservationId(reservationId);
                if (existing.isPresent()) {
                    checkOrder(existing.get(), customerName, request);
                    return;
                }
                var order = new Order();
                order.setStockReservationId(reservationId);
                order.setCustomerName(customerName);
                order.setOrderDate(LocalDateTime.now());
                order.setCheckoutRequestedAt(java.time.Instant.now());
                order.setStatus(OrderStatus.PENDING_PAYMENT);
                order.setTotalPrice(BigDecimal.ZERO);
                var items = new ArrayList<OrderItem>();
                for (var requested : request.getItems()) {
                    var item = new OrderItem();
                    item.setProductId(requested.getProductId());
                    item.setQuantity(requested.getQuantity());
                    item.setOrder(order);
                    items.add(item);
                }
                order.setOrderItems(items);
                orders.saveAndFlush(order);
            });
        } catch (DataIntegrityViolationException exception) {
            // Ett parallellt anrop kan redan ha sparat samma id; andra databasfel ska inte döljas.
            boolean exists = Boolean.TRUE.equals(transactions.execute(status ->
                    orders.findLockedByStockReservationId(reservationId).isPresent()));
            if (!exists) {
                throw exception;
            }
        }

        return transactions.execute(status -> {
            var order = ownedOrder(reservationId, customerName);
            checkOrder(order, customerName, request);
            if (order.isStockReserved()) {
                return order;
            }
            var products = stock.reserve(reservationId, customerName, request.getItems());
            if (products.size() != order.getOrderItems().size()) {
                throw new IllegalStateException("Reserved products do not match the order.");
            }
            BigDecimal total = BigDecimal.ZERO;
            for (var item : order.getOrderItems()) {
                var product = products.stream().filter(p -> Objects.equals(p.getId(), item.getProductId()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Reserved product is missing."));
                if (product.getQuantity() != item.getQuantity() || product.getPrice() == null ||
                        product.getPrice().signum() < 0) {
                    throw new IllegalStateException("Invalid reserved product details.");
                }
                item.setName(product.getName());
                item.setPrice(product.getPrice());
                total = total.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            }
            order.setTotalPrice(total);
            order.setStockReserved(true);
            return order;
        });
    }

    // Återställer en obetald order efter ett säkert avbrott eller en verifierat utgången Stripe-session.
    public void release(UUID reservationId, String customerName) {
        transactions.executeWithoutResult(status -> {
            var order = ownedOrder(reservationId, customerName);
            if (order.getStatus() == OrderStatus.CANCELLED) {
                return;
            }
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                throw new IllegalStateException("Only pending payment orders can be released.");
            }
            stock.release(reservationId, customerName);
            order.setStockReserved(false);
            order.setStatus(OrderStatus.CANCELLED);
        });
    }

    // Förbereder ordern och returnerar endast de uppgifter som betalningstjänsten behöver.
    public PaymentOrderResponseDTO prepareCheckout(UUID reservationId, String customerName, OrderRequestDTO request) {
        return snapshot(prepare(reservationId, customerName, request));
    }

    // Kopplar en testsession till en reserverad order utan att tillåta byte till en annan session.
    public void attachCheckoutSession(UUID reservationId, String customerName, String sessionId) {
        if (sessionId == null || !sessionId.startsWith("cs_test_")) {
            throw new IllegalArgumentException("A Stripe test Checkout session ID is required.");
        }
        transactions.executeWithoutResult(status -> {
            var order = ownedOrder(reservationId, customerName);
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !order.isStockReserved()) {
                throw new IllegalStateException("Order must have an active stock reservation.");
            }
            if (order.getStripeCheckoutSessionId() != null &&
                    !order.getStripeCheckoutSessionId().equals(sessionId)) {
                throw new IllegalStateException("Order already has a different Checkout session.");
            }
            order.setStripeCheckoutSessionId(sessionId);
        });
    }

    // Omvandlar ordern till en stabil ögonblicksbild med priser och ursprungliga produkt-id:n.
    private PaymentOrderResponseDTO snapshot(Order order) {
        var items = order.getOrderItems().stream().map(item -> new OrderItemResponseDTO(
                item.getProductId(), item.getName(), item.getPrice(), item.getQuantity())).toList();
        return new PaymentOrderResponseDTO(order.getId(), order.getStockReservationId(), order.getCustomerName(),
                order.getStatus(), order.isStockReserved(), order.getStripeCheckoutSessionId(),
                order.getOrderDate(), order.getCheckoutRequestedAt(), order.getTotalPrice(), items);
    }

    // Hämtar och låser en order utan att lita på ett kundnamn från frontend.
    private Order ownedOrder(UUID id, String customerName) {
        var order = orders.findLockedByStockReservationId(id)
                .orElseThrow(() -> new IllegalArgumentException("Payment order not found."));
        if (!Objects.equals(order.getCustomerName(), customerName)) {
            throw new AccessDeniedException("Payment order belongs to another customer.");
        }
        return order;
    }

    // Förhindrar att ett befintligt id återanvänds för en annan kund, varukorg eller avslutad order.
    private void checkOrder(Order order, String customerName, OrderRequestDTO request) {
        if (!Objects.equals(order.getCustomerName(), customerName)) {
            throw new AccessDeniedException("Payment order belongs to another customer.");
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Payment order is no longer pending.");
        }
        var quantities = new HashMap<Long, Integer>();
        request.getItems().forEach(item -> quantities.put(item.getProductId(), item.getQuantity()));
        if (order.getOrderItems().size() != quantities.size() || order.getOrderItems().stream()
                .anyMatch(item -> !Objects.equals(quantities.get(item.getProductId()), item.getQuantity()))) {
            throw new IllegalStateException("Reservation ID is already used for a different cart.");
        }
    }

    // Validerar varukorgen innan order eller produktlager ändras.
    private void validate(UUID id, String customerName, OrderRequestDTO request) {
        if (id == null || customerName == null || customerName.isBlank() || request == null ||
                request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Reservation ID, customer and items are required.");
        }
        var ids = new HashSet<Long>();
        for (var item : request.getItems()) {
            if (item == null || item.getProductId() == null || item.getProductId() <= 0 ||
                    item.getQuantity() <= 0 || !ids.add(item.getProductId())) {
                throw new IllegalArgumentException("Items must have unique positive product IDs and positive quantities.");
            }
        }
    }
}
