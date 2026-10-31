package se.iths.martin.orderserviceprojekt2.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Sparad orderrad med produktnamn, styckpris och antal vid beställningen.
 * Varje orderrad tillhör en order.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long productId;
    private String name;
    private BigDecimal price;
    private int quantity;

    @ManyToOne
    @JoinColumn(name = "order_id")
    private Order order;
}
