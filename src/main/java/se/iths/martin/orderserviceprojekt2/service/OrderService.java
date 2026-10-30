package se.iths.martin.orderserviceprojekt2.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import se.iths.martin.orderserviceprojekt2.Repository.OrderRepository;
import se.iths.martin.orderserviceprojekt2.client.ProductClient;
import se.iths.martin.orderserviceprojekt2.dto.OrderItemResponseDTO;
import se.iths.martin.orderserviceprojekt2.dto.OrderRequestDTO;
import se.iths.martin.orderserviceprojekt2.dto.OrderResponseDTO;
import se.iths.martin.orderserviceprojekt2.dto.ProductInfoDTO;
import se.iths.martin.orderserviceprojekt2.messaging.OrderMessageProducer;
import se.iths.martin.orderserviceprojekt2.model.Order;
import se.iths.martin.orderserviceprojekt2.model.OrderItem;
import se.iths.martin.orderserviceprojekt2.model.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Hanterar orderläggning, beräkning av totalpris och lagring av order.
 * Anropar product-service för lagerminskning och skickar orderbekräftelser via RabbitMQ.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final OrderMessageProducer orderMessageProducer;

    /**
     * Minskar lagersaldot i product-service, sparar ordern och skickar ett bekräftelsemeddelande.
     * Produktnamn och priser hämtas från product-service, inte från klientens indata.
     *
     * @param request produkter och antal som ska beställas
     * @param customerName kundens identitet från JWT:ns subject
     * @param bearerToken Authorization-header med prefixet {@code Bearer }, som skickas till product-service
     * @return den sparade ordern med orderrader och totalpris
     * @throws org.springframework.web.server.ResponseStatusException om product-service returnerar ett felsvar
     * @throws org.springframework.amqp.AmqpException om bekräftelsemeddelandet inte kan skickas
     */
    public OrderResponseDTO createOrder(
            OrderRequestDTO request,
            String customerName,
            String bearerToken) {

        Order savedOrder = saveOrder(request, customerName, bearerToken, OrderStatus.CREATED);
        OrderResponseDTO response = mapToResponse(savedOrder);
        orderMessageProducer.sendOrderConfirmation(response);
        return response;
    }

    /**
     * Förbereder en order för betalning och minskar tillgängligt lager utan att skicka ordermejl.
     * Metoden kopplas inte till checkout förrän återställning av reservationer finns på plats.
     *
     * @param request produkt-id:n och antal som ska reserveras
     * @param customerName kundens identitet från JWT
     * @param bearerToken kundens Authorization-header för produktanropet
     * @return ordern med produktpriser från backend
     * @throws IllegalArgumentException om varukorgen är tom eller innehåller ogiltiga eller upprepade produkt-id:n
     */
    public OrderResponseDTO prepareOrderForPayment(
            OrderRequestDTO request,
            String customerName,
            String bearerToken) {
        validatePaymentRequest(request);
        return mapToResponse(saveOrder(request, customerName, bearerToken, OrderStatus.PENDING_PAYMENT));
    }

    // Kontrollerar varukorgen innan något anrop kan minska lagret.
    private void validatePaymentRequest(OrderRequestDTO request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item.");
        }
        Set<Long> productIds = new HashSet<>();
        for (var item : request.getItems()) {
            if (item == null || item.getProductId() == null || item.getProductId() <= 0 ||
                    item.getQuantity() <= 0 || !productIds.add(item.getProductId())) {
                throw new IllegalArgumentException("Each item must have a unique positive product ID and a positive quantity.");
            }
        }
    }

    // Hämtar produktuppgifter, minskar lagret och sparar ordern med angiven status.
    private Order saveOrder(OrderRequestDTO request, String customerName, String bearerToken, OrderStatus status) {
        List<ProductInfoDTO> products =
                productClient.decreaseStock(request, bearerToken);

        Order order = new Order();
        order.setOrderDate(LocalDateTime.now());
        order.setCustomerName(customerName);
        order.setStatus(status);

        List<OrderItem> orderItems = new ArrayList<>();

        for (ProductInfoDTO product : products) {

            OrderItem item = new OrderItem();
            item.setProductId(product.getId());
            item.setName(product.getName());
            item.setPrice(product.getPrice());
            item.setQuantity(product.getQuantity());
            item.setOrder(order);

            orderItems.add(item);
        }

        BigDecimal totalPrice = products.stream()
                .map(product ->
                        product.getPrice()
                                .multiply(BigDecimal.valueOf(product.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        order.setOrderItems(orderItems);
        order.setTotalPrice(totalPrice);

        return orderRepository.save(order);
    }

    // Omvandlar den sparade ordern till ett svar med ursprungliga produkt-id:n.
    private OrderResponseDTO mapToResponse(Order order) {

        List<OrderItemResponseDTO> items =
                order.getOrderItems()
                        .stream()
                        .map(item -> new OrderItemResponseDTO(
                                item.getProductId(),
                                item.getName(),
                                item.getPrice(),
                                item.getQuantity()
                        ))
                        .toList();

        return new OrderResponseDTO(
                order.getId(),
                order.getOrderDate(),
                order.getCustomerName(),
                order.getTotalPrice(),
                items
        );
    }
}
