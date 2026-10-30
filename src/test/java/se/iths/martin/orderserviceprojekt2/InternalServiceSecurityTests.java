package se.iths.martin.orderserviceprojekt2;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InternalServiceSecurityTests.TestEndpoint.class)
class InternalServiceSecurityTests {
    @Autowired private MockMvc mockMvc;

    // Kontrollerar att interna anrop utan hemlig header nekas även utan CSRF-krav.
    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(post("/internal/security-test")).andExpect(status().isUnauthorized());
    }

    // Kontrollerar att fel hemlighet och en vanlig Bearer-header inte ger intern åtkomst.
    @Test
    void rejectsWrongTokenAndCustomerBearerHeader() throws Exception {
        mockMvc.perform(post("/internal/security-test")
                        .header("X-Internal-Service-Token", "wrong-token")
                        .header("Authorization", "Bearer customer-token"))
                .andExpect(status().isUnauthorized());
    }

    // Kontrollerar att rätt serverhemlighet räcker utan kundsession eller CSRF-token.
    @Test
    void acceptsInternalTokenWithoutCustomerSession() throws Exception {
        mockMvc.perform(post("/internal/security-test")
                        .header("X-Internal-Service-Token", "internal-test-token-not-for-real-use-123456"))
                .andExpect(status().isOk());
    }

    // Kontrollerar att serverns felhantering bevarar ursprungsfelet utan att kräva kund-JWT.
    @Test
    void preservesServerErrorDuringErrorDispatch() throws Exception {
        mockMvc.perform(get("/error").with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            return request;
                        })
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/internal/payment-orders/example"))
                .andExpect(status().isInternalServerError());
    }

    // Denna endpoint finns bara i testkontexten, inte i den körande tjänsten.
    @RestController
    static class TestEndpoint {
        // Returnerar ett enkelt svar så att testet kan verifiera säkerhetskedjan.
        @PostMapping("/internal/security-test")
        public String check() {
            return "ok";
        }
    }
}
