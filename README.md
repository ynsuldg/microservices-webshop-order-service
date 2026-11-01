# Order Service

## Ansvar

Order Service hanterar order i Microservices Webshop. Den sparar orderrader och backendpriser
och kommunicerar med product-service för lagerhantering. Kundens vanliga orderanrop går via gatewayn med JWT.

Stripe-flödet använder statusarna CREATED, PENDING_PAYMENT, PAID och CANCELLED.
Interna anrop förbereder ordern, kopplar Stripe-sessionen och behandlar verifierad betalning eller utgång.
En betald reservation förbrukas; en verifierat stängd obetald reservation frigörs.
Efter betalning skickas ordermeddelande via RabbitMQ till email-service.

## Tekniker

Java 17, Spring Boot 4, Spring Security, JWT, Maven, PostgreSQL och RabbitMQ.

## Databas

PostgreSQL lagrar order, orderrader, Stripe-sessionens id och reservationskoppling.
Tjänsten lagrar inga kortuppgifter.

## Miljövariabler

| Variabel | Lokalt | Render |
| --- | --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | JDBC-adress och inloggning till PostgreSQL. | Nåbar orderdatabas och dess inloggning. |
| `JWT_PUBLIC_URL` | `http://localhost:8080/auth/jwks` | `https://microservices-webshop-auth-service.onrender.com/auth/jwks` |
| `PRODUCT_SERVICE_URL` | `http://localhost:8081` | `https://microservices-webshop-product-service.onrender.com` |
| `ALLOWED_ORIGINS` | `http://localhost:5173` | `https://microservices-webshop-frontend.onrender.com` |
| `INTERNAL_SERVICE_TOKEN` | Gemensam intern hemlighet, minst 32 tecken. | Samma värde i gateway, payment, order och product. |
| `RABBITMQ_HOST`, `RABBITMQ_PORT` | Brokeradress och TLS-port. | Nåbar brokeradress och TLS-port. |
| `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` | Brokerns inloggning och virtual host. | Samma broker och virtual host som email-service. |
| `RABBITMQ_ORDER_CONFIRMATION_QUEUE` | Standard `new-queue`. | Behåll `new-queue`, som email-service lyssnar på. |

RabbitMQ använder TLS. Databasens H2-standardvärde ersätter inte den PostgreSQL-konfiguration som behövs för normal körning.
Hemliga värden ska inte sparas i Git.

## Starta applikationen lokalt

Sätt miljövariablerna och starta från projektmappen.

**macOS/Linux:**

```bash
./mvnw spring-boot:run
```

**Windows PowerShell:**

```powershell
.\mvnw.cmd spring-boot:run
```

Kräver Java 17 eller senare, PostgreSQL och RabbitMQ. Starta auth-service och product-service först.
Lokal adress: `http://localhost:8082`. Hela betalningsflödet behöver även payment-service, gateway och frontend.

## Render

| Tjänst | Render-adress |
| --- | --- |
| Frontend – öppna webshoppen | [microservices-webshop-frontend.onrender.com](https://microservices-webshop-frontend.onrender.com) |
| Auth Service | [microservices-webshop-auth-service.onrender.com](https://microservices-webshop-auth-service.onrender.com) |
| Product Service | [microservices-webshop-product-service.onrender.com](https://microservices-webshop-product-service.onrender.com) |
| Order Service | [microservices-webshop-order-service.onrender.com](https://microservices-webshop-order-service.onrender.com) |
| Email Service | [microservices-webshop-email-service.onrender.com](https://microservices-webshop-email-service.onrender.com) |
| Gateway Service | [microservices-webshop-gateway-service.onrender.com](https://microservices-webshop-gateway-service.onrender.com) |
| Payment Service | [microservices-webshop-payment-service.onrender.com](https://microservices-webshop-payment-service.onrender.com) |
- Docker-image: `ynsuldg/microservices-webshop-order-service:1.0.0`.

Använd **Web Service → Existing Image**, sätt miljövariablerna i Render och behåll Docker-imagens startkommando.
Efter en ny image från senaste `main` väljs **Manual Deploy → Deploy latest reference**.
En äldre Actions-omkörning kan skriva över samma tagg med äldre kod.

Backendadressen är ett API, inte en vanlig webbsida. Rotadressen kan ge 401 eller 404 utan att tjänsten är nere.
Render Free kan ge väntetid när tjänsterna väcks. Se [Render-dokumentationen](https://render.com/docs/deploying-an-image).

## Relaterade repositories

- [Auth Service](https://github.com/ynsuldg/microservices-webshop-auth-service)
- [Product Service](https://github.com/ynsuldg/microservices-webshop-product-service)
- [Email Service](https://github.com/ynsuldg/microservices-webshop-email-service)
- [Gateway Service](https://github.com/ynsuldg/microservices-webshop-gateway-service)
- [Payment Service](https://github.com/ynsuldg/microservices-webshop-payment-service)
- [Frontend](https://github.com/ynsuldg/microservices-webshop-frontend)
