package se.iths.martin.orderserviceprojekt2.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import se.iths.martin.orderserviceprojekt2.dto.*;
import se.iths.martin.orderserviceprojekt2.service.PaymentOrderService;
import java.util.List;
import java.util.UUID;

/** Interna orderanrop som kräver tjänstehemlighet; kundnamnet ska komma från betrodd backend. */
@RestController
@RequestMapping("/internal/payment-orders")
@RequiredArgsConstructor
public class PaymentOrderController {
    private final PaymentOrderService service;
    public record PrepareRequest(String customerName, List<OrderItemRequestDTO> items) { }
    public record SessionRequest(String customerName, String sessionId) { }

    // Förbereder eller återanvänder betalningsordern med samma reservations-id.
    @PostMapping("/{id}/prepare")
    public PaymentOrderResponseDTO prepare(@PathVariable UUID id, @RequestBody PrepareRequest request) {
        return service.prepareCheckout(id, request.customerName(), new OrderRequestDTO(request.items()));
    }

    // Sparar Stripe-sessionen på ordern innan checkout-URL:en lämnas vidare.
    @PostMapping("/{id}/session")
    public ResponseEntity<Void> attachSession(@PathVariable UUID id, @RequestBody SessionRequest request) {
        service.attachCheckoutSession(id, request.customerName(), request.sessionId());
        return ResponseEntity.noContent().build();
    }

    // Returnerar klientfel för ogiltig varukorg eller ogiltigt sessions-id.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    // Förhindrar att annan kund kan återanvända betalningsordern.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> wrongCustomer(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(exception.getMessage());
    }

    // Returnerar konflikt om betalningsordern inte längre kan användas.
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.getMessage());
    }
}
