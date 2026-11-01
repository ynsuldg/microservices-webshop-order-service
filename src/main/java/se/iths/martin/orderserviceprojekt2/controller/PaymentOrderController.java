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
    /**
     * Innehåller kund och orderrader när en betalningsorder förbereds.
     */
    public record PrepareRequest(String customerName, List<OrderItemRequestDTO> items) { }
    /**
     * Kopplar kundens order till en Stripe Checkout-session.
     */
    public record SessionRequest(String customerName, String sessionId) { }
    /**
     * Innehåller Stripe-session, betalt belopp i minsta valutaenhet och valuta.
     */
    public record PaymentRequest(String sessionId, Long amountTotal, String currency) { }
    /**
     * Identifierar Stripe-sessionen för en utgången checkout.
     */
    public record ExpiryRequest(String sessionId) { }

    /**
     * Returnerar orderns sparade belopp och sessionskoppling till betalningstjänsten.
     *
     * Ingår i betalningsflödet.
     *
     * @param id id för den efterfrågade posten eller reservationen
     * @return resultatet av anropet
     */
    @GetMapping("/{id}")
    public PaymentOrderResponseDTO getOrder(@PathVariable UUID id) {
        return service.getPaymentOrder(id);
    }

    /**
     * Tar emot betalningsbekräftelse endast från betrodd backend, inte från retursidan.
     *
     * Ingår i betalningsflödet.
     *
     * @param id id för den efterfrågade posten eller reservationen
     * @param request uppgifter för anropet
     * @return HTTP-svar med statuskod och eventuell svarskropp
     */
    @PostMapping("/{id}/paid")
    public ResponseEntity<Void> paid(@PathVariable UUID id, @RequestBody PaymentRequest request) {
        service.confirmPayment(id, request.sessionId(), request.amountTotal(), request.currency());
        return ResponseEntity.noContent().build();
    }

    /**
     * Tar emot besked om en verifierat utgången Checkout-session.
     *
     * Ingår i betalningsflödet.
     *
     * @param id id för den efterfrågade posten eller reservationen
     * @param request uppgifter för anropet
     * @return HTTP-svar med statuskod och eventuell svarskropp
     */
    @PostMapping("/{id}/expired")
    public ResponseEntity<Void> expired(@PathVariable UUID id, @RequestBody ExpiryRequest request) {
        service.expirePayment(id, request.sessionId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Förbereder eller återanvänder betalningsordern med samma reservations-id.
     *
     * Ingår i betalningsflödet.
     *
     * @param id id för den efterfrågade posten eller reservationen
     * @param request uppgifter för anropet
     * @return den förberedda ordern
     */
    @PostMapping("/{id}/prepare")
    public PaymentOrderResponseDTO prepare(@PathVariable UUID id, @RequestBody PrepareRequest request) {
        return service.prepareCheckout(id, request.customerName(), new OrderRequestDTO(request.items()));
    }

    /**
     * Sparar Stripe-sessionen på ordern innan checkout-URL:en lämnas vidare.
     *
     * Ingår i betalningsflödet.
     *
     * @param id id för den efterfrågade posten eller reservationen
     * @param request uppgifter för anropet
     * @return HTTP-svar med statuskod och eventuell svarskropp
     */
    @PostMapping("/{id}/session")
    public ResponseEntity<Void> attachSession(@PathVariable UUID id, @RequestBody SessionRequest request) {
        service.attachCheckoutSession(id, request.customerName(), request.sessionId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Returnerar klientfel för ogiltig varukorg eller ogiltigt sessions-id.
     *
     * Ingår i betalningsflödet.
     *
     * @param exception felet som ska översättas till ett HTTP-svar
     * @return HTTP-svar med statuskod och eventuell svarskropp
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    /**
     * Förhindrar att annan kund kan återanvända betalningsordern.
     *
     * Ingår i betalningsflödet.
     *
     * @param exception felet som ska översättas till ett HTTP-svar
     * @return HTTP-svar med statuskod och eventuell svarskropp
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> wrongCustomer(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(exception.getMessage());
    }

    /**
     * Returnerar konflikt om betalningsordern inte längre kan användas.
     *
     * Ingår i betalningsflödet.
     *
     * @param exception felet som ska översättas till ett HTTP-svar
     * @return HTTP-svar med statuskod och eventuell svarskropp
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.getMessage());
    }
}
