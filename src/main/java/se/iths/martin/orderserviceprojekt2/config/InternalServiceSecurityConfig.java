package se.iths.martin.orderserviceprojekt2.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Separat skydd för serveranrop; kundens JWT eller sessionscookie ger inte intern åtkomst. */
@Configuration
public class InternalServiceSecurityConfig {

    // Skyddar interna sökvägar med en serverhemlighet utan att påverka tjänstens vanliga säkerhetskedja.
    @Bean
    @Order(1)
    public SecurityFilterChain internalServiceFilterChain(
            HttpSecurity http,
            @Value("${internal.service-token:}") String serviceToken) throws Exception {
        return http
                .securityMatcher("/internal/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                // Endast dessa serveranrop undantas; de använder en explicit header, inte cookieautentisering.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/internal/**"))
                .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) ->
                        new AuthorizationDecision(validToken(serviceToken,
                                context.getRequest().getHeader("X-Internal-Service-Token")))))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, exception) -> response.setStatus(401)))
                .build();
    }

    // Nekar saknad eller för kort konfiguration och jämför hemligheten utan vanlig strängjämförelse.
    private boolean validToken(String expected, String supplied) {
        return expected.length() >= 32 && supplied != null &&
                MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8));
    }
}
