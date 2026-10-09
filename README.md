# Arafi API

The core backend engine for the [Arafi](https://arafi.app) payment platform. Built with **Spring Boot 3**, it provides the REST API layer consumed by the Arafi merchant dashboard and public-facing checkout pages.

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21 |
| Framework | Spring Boot 3.5 |
| Security | Spring Security + JWT (jjwt 0.12) |
| Persistence | Spring Data JPA + PostgreSQL |
| Resilience | Resilience4j (Circuit Breaker, Timeout) |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Email | Resend |
| Build | Maven (Maven Wrapper included) |
| Container | Docker |

## Project Structure

```
src/main/java/com/yourara/arafi/
├── controller/         # REST controllers (one per domain)
├── model/              # JPA entities
│   ├── request/        # Request DTOs
│   └── response/       # Response DTOs
├── repository/         # Spring Data JPA repositories
├── service/            # Business logic
├── scheduler/          # Background job schedulers
├── security/           # JWT filter + request context
└── webhook/
    └── normalizer/     # Gateway-specific webhook normalizers
```

## API Reference

All authenticated endpoints require a Bearer JWT token in the `Authorization` header.  
The live Swagger UI is available at `/swagger-ui/index.html` when the server is running.

### Auth — `/v1/auth`

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/v1/auth/signup` | — | Register a new user |
| `POST` | `/v1/auth/login` | — | Authenticate and receive a JWT |

### Workspaces — `/v1/workspaces`

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/v1/workspaces` | ✅ | Create a workspace |
| `GET` | `/v1/workspaces` | ✅ | Get current user's workspace |

### Products — `/v1/products`

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/v1/products` | ✅ | Create a product |
| `GET` | `/v1/products` | ✅ | List products for workspace |
| `DELETE` | `/v1/products/{id}` | ✅ | Delete a product |
| `POST` | `/v1/products/{id}/checkout` | ✅ | Generate a checkout link for a product |
| `GET` | `/v1/products/public/checkout/{checkoutId}` | — | Fetch public checkout page data |
| `POST` | `/v1/products/public/checkout/{checkoutId}/card-checkout` | — | Initiate card payment |
| `POST` | `/v1/products/public/checkout/{checkoutId}/bank-transfer` | — | Initiate bank transfer payment |
| `GET` | `/v1/products/public/checkout/{checkoutId}/verify` | — | Verify a product payment |
| `POST` | `/v1/products/public/simulate-transfer` | — | Simulate bank transfer (dev/test) |

### Plans — `/v1/plans`

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/v1/plans` | ✅ | Create a subscription plan |
| `GET` | `/v1/plans` | ✅ | List plans for workspace |

### Subscriptions — `/v1/subscriptions`

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/v1/subscriptions` | ✅ | Create a subscription |
| `GET` | `/v1/subscriptions` | ✅ | List subscriptions for workspace |
| `POST` | `/v1/subscriptions/run-renewals` | ✅ | Manually trigger renewal processing |
| `POST` | `/v1/subscriptions/run-webhooks` | ✅ | Manually trigger webhook dispatch |
| `DELETE` | `/v1/subscriptions/{id}/cancel` | ✅ | Cancel a subscription |
| `POST` | `/v1/subscriptions/{id}/pause` | ✅ | Pause a subscription |
| `POST` | `/v1/subscriptions/{id}/resume` | ✅ | Resume a paused subscription |
| `PUT` | `/v1/subscriptions/{id}/plan` | ✅ | Change the plan on a subscription |
| `POST` | `/v1/subscriptions/{id}/verify-payment` | ✅ | Verify a subscription payment |
| `GET` | `/v1/subscriptions/public/verify` | — | Public payment verification |
| `POST` | `/v1/subscriptions/public/simulate-transfer` | — | Simulate bank transfer (dev/test) |
| `GET` | `/v1/subscriptions/public/{id}` | — | Get public subscription checkout data |
| `POST` | `/v1/subscriptions/public/{id}/card-checkout` | — | Public card checkout |
| `POST` | `/v1/subscriptions/public/{id}/bank-transfer` | — | Public bank transfer checkout |

### Customers — `/v1/customers`

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/v1/customers` | ✅ | Create a customer |
| `GET` | `/v1/customers` | ✅ | List customers for workspace |
| `DELETE` | `/v1/customers/{id}/card` | ✅ | Remove saved card |
| `POST` | `/v1/customers/{id}/card/tokenize` | ✅ | Tokenize a card for a customer |

### Coupons — `/v1/coupons`

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/v1/coupons` | ✅ | Create a coupon |
| `GET` | `/v1/coupons` | ✅ | List coupons for workspace |

### Payouts — `/v1/payouts`

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/v1/payouts` | ✅ | Initiate a payout |
| `GET` | `/v1/payouts` | ✅ | List payout history |

### Balances — `/v1/balances`

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/v1/balances` | ✅ | Get ledger balance for workspace |

### Virtual Accounts — `/v1/virtual-accounts`

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/v1/virtual-accounts` | ✅ | Create a virtual bank account |

### Email Templates — `/v1/settings/emails`

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/v1/settings/emails` | ✅ | Save an email template |
| `GET` | `/v1/settings/emails` | ✅ | Get email templates for workspace |

### Gateway Webhooks — `/v1/webhooks`

Public endpoints called by payment gateways. Each validates the gateway signature before processing.

| Method | Path | Gateway |
|---|---|---|
| `POST` | `/v1/webhooks/flutterwave` | Flutterwave |
| `POST` | `/v1/webhooks/paystack` | Paystack |
| `POST` | `/v1/webhooks/alatpay` | AlatPay |
| `POST` | `/v1/webhooks/nomba` | Nomba |

### Health

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Server health check |

---

## Background Schedulers

| Scheduler | Description |
|---|---|
| `SubscriptionRenewalScheduler` | Processes due subscription renewals on a cron schedule |
| `WebhookDispatchScheduler` | Dispatches pending merchant webhook events |
| `WebhookProcessorScheduler` | Processes raw gateway webhook payloads into `ArafiEvent`s |
| `PayoutProcessingScheduler` | Handles queued payout requests |

## Payment Gateways

The API integrates with multiple Nigerian payment gateways via a normalizer pattern (`WebhookNormalizerRegistry`), enabling gateway-agnostic event processing and automatic failover via **Resilience4j** circuit breakers with a 2,500ms timeout cap.

Supported gateways: **Nomba**, **Paystack**, **Flutterwave**, **AlatPay**

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+ (or use the included `./mvnw`)
- PostgreSQL 14+

### Environment Variables

Copy `.env.example` to `.env` (or set the following in `application.properties`):

```properties
# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/arafi
spring.datasource.username=your_db_user
spring.datasource.password=your_db_password

# JWT
jwt.secret=your_jwt_secret_min_256_bits
jwt.expiration=86400000

# Resend (email)
resend.api.key=re_xxxxxxxxxxxx

# Gateway secrets
nomba.webhook.secret=...
paystack.webhook.secret=...
flutterwave.webhook.secret=...
alatpay.webhook.secret=...
```

### Running Locally

```bash
# Using Maven Wrapper
./mvnw spring-boot:run

# Or build and run the JAR
./mvnw clean package -DskipTests
java -jar target/arafi-api-0.0.1-SNAPSHOT.jar
```

The server starts on **port 8080** by default.  
Swagger UI: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)

### Docker

```bash
docker build -t arafi-api .
docker run -p 8080:8080 --env-file .env arafi-api
```

### Running Tests

```bash
./mvnw test
```
