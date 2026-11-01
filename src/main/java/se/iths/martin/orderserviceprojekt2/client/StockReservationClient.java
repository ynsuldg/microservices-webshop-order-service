package se.iths.martin.orderserviceprojekt2.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import se.iths.martin.orderserviceprojekt2.dto.OrderItemRequestDTO;
import se.iths.martin.orderserviceprojekt2.dto.ProductInfoDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Anropar produktreservationer med serverhemlighet i stället för kundens JWT. */
@Component
public class StockReservationClient {
    private final RestClient client;
    private final String token;

    /**
     * Tar emot befintlig produktklient och den interna serverhemligheten.
     *
     * Ingår i betalningsflödet.
     *
     * @param productRestClient HTTP-klient för product-service
     * @param token delad hemlighet för interna tjänsteanrop
     */
    public StockReservationClient(RestClient productRestClient, @Value("${internal.service-token:}") String token) {
        this.client = productRestClient;
        this.token = token;
    }

    /**
     * Reserverar med samma id vid återförsök och hämtar backendens sparade priser.
     *
     * Ingår i betalningsflödet.
     *
     * @param id id för den efterfrågade posten eller reservationen
     * @param customerName namnet på kunden som äger reservationen
     * @param items produkter och antal i ordern
     * @return reserverade produkter med pris och antal
     */
    public List<ProductInfoDTO> reserve(UUID id, String customerName, List<OrderItemRequestDTO> items) {
        checkConfiguration();
        var response = client.post().uri("/internal/stock-reservations/{id}", id)
                .header("X-Internal-Service-Token", token)
                .body(Map.of("customerName", customerName, "items", items))
                .retrieve().body(new ParameterizedTypeReference<List<ProductInfoDTO>>() { });
        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("Product service returned no reserved products.");
        }
        return response;
    }

    /**
     * Begär återställning även när ett tidigare reservationsanrop kan ha tappat sitt svar.
     *
     * Ingår i betalningsflödet.
     *
     * @param id id för den efterfrågade posten eller reservationen
     * @param customerName namnet på kunden som äger reservationen
     */
    public void release(UUID id, String customerName) {
        checkConfiguration();
        client.post().uri("/internal/stock-reservations/{id}/release", id)
                .header("X-Internal-Service-Token", token)
                .body(Map.of("customerName", customerName)).retrieve().toBodilessEntity();
    }

    /**
     * Slutför reservationen utan ytterligare lagerminskning.
     *
     * Ingår i betalningsflödet.
     *
     * @param id id för den efterfrågade posten eller reservationen
     * @param customerName namnet på kunden som äger reservationen
     */
    public void consume(UUID id, String customerName) {
        checkConfiguration();
        client.post().uri("/internal/stock-reservations/{id}/consume", id)
                .header("X-Internal-Service-Token", token)
                .body(Map.of("customerName", customerName)).retrieve().toBodilessEntity();
    }

    // Stoppar interna anrop innan de skickas om serverhemligheten saknas.
    private void checkConfiguration() {
        if (token.length() < 32) {
            throw new IllegalStateException("INTERNAL_SERVICE_TOKEN must contain at least 32 characters.");
        }
    }
}
