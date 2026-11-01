package se.iths.martin.orderserviceprojekt2.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Visar en publik statussida för tjänsten utan att kontrollera andra mikrotjänster.
 */
@RestController
public class StatusController {

    /**
     * Returnerar tjänstens HTML-sida på rotadressen och indexadressen.
     *
     * @return statisk HTML med tjänstenamn och länk till webbshoppen
     */
    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public Resource statusPage() {
        return new ClassPathResource("static/index.html");
    }
}
