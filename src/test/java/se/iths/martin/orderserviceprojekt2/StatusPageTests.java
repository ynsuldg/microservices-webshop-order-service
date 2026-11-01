package se.iths.martin.orderserviceprojekt2;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import se.iths.martin.orderserviceprojekt2.controller.StatusController;
import org.springframework.context.annotation.Import;
import se.iths.martin.orderserviceprojekt2.security.SecurityConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StatusController.class)
@Import(SecurityConfig.class)
class StatusPageTests {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // Rotadressen och direktadressen ska fungera utan inloggning och visa rätt tjänst.
    @Test
    void statusPageIsPublic() throws Exception {
        for (String address : new String[] { "/", "/index.html" }) {
            mockMvc.perform(get(address))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(content().string(containsString("Order Service")))
                    .andExpect(content().string(containsString("Service is running")));
        }
    }

    // Statustillgång får inte öppna ett skyddat API för anonyma användare.
    @Test
    void apiStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/orders")).andExpect(status().isUnauthorized());
    }
}
