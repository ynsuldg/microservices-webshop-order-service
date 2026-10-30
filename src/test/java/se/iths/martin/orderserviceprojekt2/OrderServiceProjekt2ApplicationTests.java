package se.iths.martin.orderserviceprojekt2;

import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import se.iths.martin.orderserviceprojekt2.Repository.OrderRepository;
import se.iths.martin.orderserviceprojekt2.model.Order;
import se.iths.martin.orderserviceprojekt2.model.OrderStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
class OrderServiceProjekt2ApplicationTests {

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private EntityManager entityManager;

	// Kontrollerar att applikationens Spring-kontext kan startas.
	@Test
	void contextLoads() {
	}

	// Kontrollerar att en ny vanlig order inte automatiskt räknas som betald.
	@Test
	void newOrderUsesCreatedStatusWithoutStripeSession() {
		Order order = new Order();
		assertEquals(OrderStatus.CREATED, order.getStatus());
		assertNull(order.getStripeCheckoutSessionId());
	}

	// Kontrollerar databasens standardvärden när en order skapas utan de nya betalningsflaggorna.
	@Test
	@Transactional
	void databaseDefaultsPaymentFlagsToFalse() {
		entityManager.createNativeQuery("insert into orders (customer_name) values ('database-default-test')")
				.executeUpdate();
		Order loaded = entityManager.createQuery(
				"select o from Order o where o.customerName = 'database-default-test'", Order.class)
				.getSingleResult();
		assertFalse(loaded.isStockReserved());
		assertFalse(loaded.isConfirmationPending());
	}

	// Kontrollerar att betalningsstatus och Stripe-koppling kan sparas i testdatabasen.
	@Test
	@Transactional
	void savesPaymentStatusAndStripeSession() {
		Order order = new Order();
		order.setStatus(OrderStatus.PENDING_PAYMENT);
		order.setStripeCheckoutSessionId("cs_test_order_status");
		Order saved = orderRepository.saveAndFlush(order);
		entityManager.clear();

		Order loaded = orderRepository.findById(saved.getId()).orElseThrow();
		assertEquals(OrderStatus.PENDING_PAYMENT, loaded.getStatus());
		assertEquals("cs_test_order_status", loaded.getStripeCheckoutSessionId());
	}

}
