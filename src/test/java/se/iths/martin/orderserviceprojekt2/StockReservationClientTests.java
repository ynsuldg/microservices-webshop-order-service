package se.iths.martin.orderserviceprojekt2;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import se.iths.martin.orderserviceprojekt2.client.StockReservationClient;
import se.iths.martin.orderserviceprojekt2.dto.OrderItemRequestDTO;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class StockReservationClientTests {
    // Kontrollerar URL, serverhemlighet, JSON-format och återställningsanrop utan riktig produktserver.
    @Test
    void sendsReservationAndReleaseUsingInternalToken() {
        var builder = RestClient.builder().baseUrl("http://product.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var token = "internal-test-token-not-for-real-use-123456";
        var client = new StockReservationClient(builder.build(), token);
        var id = UUID.randomUUID();

        server.expect(requestTo("http://product.test/internal/stock-reservations/" + id))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", token))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().json("{\"customerName\":\"customer\",\"items\":[{\"productId\":42,\"quantity\":2}]}"))
                .andRespond(withSuccess("[{\"id\":42,\"name\":\"Product\",\"price\":12.50,\"quantity\":2}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://product.test/internal/stock-reservations/" + id + "/release"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("X-Internal-Service-Token", token))
                .andRespond(withNoContent());

        var result = client.reserve(id, "customer", List.of(new OrderItemRequestDTO(42L, 2)));
        assertEquals(42L, result.get(0).getId());
        client.release(id, "customer");
        server.verify();
    }
}
