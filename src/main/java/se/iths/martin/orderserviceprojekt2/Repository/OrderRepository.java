package se.iths.martin.orderserviceprojekt2.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import se.iths.martin.orderserviceprojekt2.model.Order;

/**
 * Databasåtkomst för order via Spring Data JPA:s standardoperationer.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    /**
     * Låser betalningsordern så att samtidiga återförsök inte reserverar eller avslutar den parallellt.
     *
     * Ingår i betalningsflödet.
     *
     * @param id id för den efterfrågade posten eller reservationen
     * @return den matchande posten, eller ett tomt Optional om den saknas
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.stockReservationId = :id")
    java.util.Optional<Order> findLockedByStockReservationId(
            @org.springframework.data.repository.query.Param("id") java.util.UUID id);
}
