package se.iths.martin.orderserviceprojekt2.model;

/**
 * Orderns läge i betalningsflödet. CREATED används för befintlig orderläggning utan betalning.
 */
public enum OrderStatus {
    CREATED,
    PENDING_PAYMENT,
    PAID,
    CANCELLED
}
