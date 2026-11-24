# 15 — PAY_NOW through a payment provider (payment-sim)

Status: implemented on main (2026-11). Tests not yet updated (see §9).

## 1. Why

PAY_NOW used to authorise inside an async saga step: billing "approved" the card with nobody present.
A real payment needs the subscriber **live** (3-D Secure OTP, UPI approval), and that can't happen in a
Kafka handler. The fix: the subscriber pays on the provider's checkout page, and the saga waits for the
provider's verdict. Everything after the money is secured stays an async saga.

There is no real provider. **payment-sim** plays one, as ott-service plays an OTT platform: it knows
nothing about orders, uses no Tram and no DB, and talks to billing only through the provider APIs.

## 2. Flow

```
POST /v1/orders (PAY_NOW) ──▶ order-service: PLACED, saga starts, 202
saga 1  reserve ─────────────▶ inventory: hold (TTL 10 min)
saga 4  createPaymentIntent ─▶ billing ──REST (API key)──▶ payment-sim: intent + checkout URL (TTL 8 min)
        reply PaymentIntentCreated → order PENDING_PAYMENT + OrderAwaitingPayment(checkoutUrl, expiresAt)
client  polls GET /v1/orders/{id} → payment.checkoutUrl → opens it
browser payment-sim /checkout/{id}?cs=… → OTP 123456 → Pay | Decline   (the only step with the user live)
        payment-sim ──signed webhook──▶ billing /webhooks/psp (retried until 2xx)
saga 4b awaitPayment ────────▶ billing holds the reply (deferred) until the webhook / expiry / cancel
        PaymentAuthorized → 5 commit → 6 [cancel?] → 7 CAPTURE (pivot, billing ──▶ payment-sim) → confirm …
        PaymentNotCompleted(DECLINED|EXPIRED|CANCELLED) → compensation: release hold → FAILED / CANCELLED
```

* The payment window (8 min) is shorter than the inventory hold (10 min), so a paid order's commit
  finds its hold.
* 4b's compensation is **void**: if anything later rolls back (pre-pivot cancel, capture failure), the
  authorisation is released.
* BILL_TO_MOBILE is unchanged. Carrier billing needs no user interaction, so a fully async saga is right.

## 3. Billing being down

| When | What happens | Why it's acceptable |
|---|---|---|
| Placement | Nothing: placement never calls billing. The order is PLACED and the saga's command waits in Kafka | Checkout doesn't depend on billing being up |
| Before the intent exists | The order stays PLACED with no checkout link; the client keeps polling. When billing returns it consumes the command, and the link appears | The user waits, or cancels (cancel is remembered, §5) |
| While the user pays | payment-sim retries the webhook with backoff (up to 15 attempts, capped at 1 min) until billing answers 2xx | Webhooks are at-least-once by design; billing dedupes on the event id |
| Billing back after the window closed | The sweeper expires the intent; a late "authorized" webhook is voided at once | The customer is never charged for an order that gave up |

The alternative, billing creating the intent synchronously during placement, would turn a billing outage
into a 503 at checkout and need a new authenticated internal REST API. Async keeps one integration style
(Tram commands) and lets billing restart without losing a checkout.

## 4. payment-sim (port 8091)

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /v1/payment_intents` | API key (Basic `keyId:keySecret`) + `Idempotency-Key` | Create; same key → same intent |
| `POST /v1/payment_intents/{id}/capture` · `/cancel` · `/refund` | API key | Idempotent; `cancel` on an authorisation = void |
| `GET /checkout/{id}?cs=…` | Client secret in the URL (single-use capability) | Hosted checkout page |
| `POST /checkout/{id}/decision` (`cs`, `decision`, `otp`) | Client secret | Approve (OTP 123456) / decline; also used by E2E without a browser |

Webhooks: `payment.authorized` / `payment.failed`, header `Psp-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`.
Demo triggers: amount **503** → create answers 503 (provider down, offer `ACC_DEMO_PSPDOWN`); amount **777** → capture declined.
State is in memory: a restart forgets open intents and billing's sweeper expires them.

## 5. Billing state machine (`billing.payment_intent`, one row per order)

```
CREATED ─webhook authorized─▶ AUTHORIZED ─capture─▶ CAPTURED ─refund─▶ REFUNDED   (or REFUND_OWED → REFUNDED)
   │                              └─void (compensation)─▶ VOIDED  (or VOID_OWED → VOIDED)
   ├─webhook failed──▶ FAILED      ┐
   ├─sweeper──────────▶ EXPIRED     ├─ a later "authorized" webhook → void
   └─user cancel─────▶ CANCELLED   ┘
(no row) ─user cancel─▶ CANCELLED tombstone → createPaymentIntent refuses (CANCELLED)
```

* The row is locked (`FOR UPDATE`) by every writer: saga command, webhook, sweeper. A waiting saga's reply
  token lives on the row and the reply is sent in the transaction that leaves CREATED, so exactly once.
* Provider calls run inside those transactions, bounded by 2 s / 5 s HTTP timeouts and the `psp` breaker.
  **Create** and **capture** failing fail the saga step (rollback). **Void** and **refund** never fail the
  saga: if the provider is down they become *owed* and the sweeper retries them every minute.
* Webhook checks, in order: HMAC over the **raw** body (constant-time, several `v1` allowed for secret
  rotation), timestamp within ±5 min (replay), event-id dedupe (`billing.psp_webhook_event`). 2xx only after
  the event is applied.

## 6. Cancel

`POST /v1/orders/{id}/cancel` on a PAY_NOW order that is PLACED or PENDING_PAYMENT also sends
`CancelPaymentCommand` to billing. Billing cancels the intent (or writes a tombstone if it doesn't exist yet)
and answers the waiting saga `PaymentNotCompleted(CANCELLED)` → hold released → order **CANCELLED**.
Once paid, the existing pre-pivot checkpoint handles a cancel: rollback = void + release.

## 7. Authn / authz

| Direction | Mechanism | Real-world equivalent |
|---|---|---|
| billing → payment-sim | Static API key, HTTP Basic, from `vabags-secrets` (`VAB_PSP_KEY_SECRET`) | Stripe secret key, Razorpay key_id:key_secret |
| browser → checkout | Unguessable client secret in the URL | Stripe `client_secret` |
| payment-sim → billing | HMAC-signed webhook; the endpoint is not behind login and not routed via the gateway | Stripe/Razorpay webhook signatures |

## 8. Config

| Key | Where |
|---|---|
| `vab.billing.payments.*` (`PSP_BASE_URL`, `VAB_PSP_KEY_*`, `VAB_PSP_WEBHOOK_SECRET`) | billing |
| `vab.psp.*` (`VAB_PSP_WEBHOOK_URL`, `PAYMENT_SIM_PUBLIC_URL`, same secrets) | payment-sim |
| `payment-public` NLB, `PAYMENT_SIM_PUBLIC_URL` in `vabags-public`, secrets patched into `vabags-secrets` | EKS (`deploy.sh`) |

## 9. Not done yet

* Tests: `PlaceOrderSagaTest`, `BillingCommandHandlersTest`, the shared-events contract file, the
  PAY_NOW E2E suites (they relied on the old "> 999 declines" trigger), and new tests for payment-sim,
  `PaymentService` and the webhook signature.
* Notification of the checkout link (SMS/e-mail) and the ops search view's PENDING_PAYMENT status.
