# API Contracts

All endpoints versioned via URL prefix `/v1/`. Auth: `Authorization: Bearer <subscriber-JWT>` unless noted.

---

## Catalog & Eligibility

| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/v1/offers` | Subscriber JWT | Eligibility-filtered list for caller's subscriber profile |
| `GET` | `/v1/offers/{offerCode}` | Subscriber JWT | Offer detail + `priceSnapshotId` |
| `POST` | `/v1/offers/{offerCode}:evaluate` | Subscriber JWT | Rule-level eligibility result ("why can't I buy this?") |

---

## Orders — command side

### `POST /v1/orders`

**Required header:** `Idempotency-Key: <UUIDv4>`

Request:
```json
{
  "offerCode": "OTT_NETFLIX_6M",
  "productType": "DIGITAL_SUBSCRIPTION",
  "billingMode": "BILL_TO_MOBILE",
  "priceSnapshotId": "ps_2026_05_abc123",
  "amount": 599,
  "currency": "INR"
}
```
`subscriberId` is derived from JWT — never in body. `productType` is client-sent and
verified against catalog (DD-22); `billingMode` (`PAY_NOW` | `BILL_TO_MOBILE`) selects
the saga flow (DD-23).

Responses:
- `202 Accepted` + `Location: /v1/orders/{orderId}` — accepted, fulfillment async
- `400 Bad Request` — missing/malformed `Idempotency-Key`, invalid body
- `404 Not Found` — offer not found
- `409 Conflict` — same key + different payload, OR active entitlement already exists for this offer

### `POST /v1/orders/{orderId}:cancel`

**Required header:** `Idempotency-Key: <UUIDv4>`

Responses:
- `202 Accepted` — cancel accepted, compensation may be async
- `409 Conflict` — order not in cancellable state (cooling-off window expired, or already terminal)

---

## Orders — query side

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/orders` | Paginated. Query params: `status`, `from`, `to`, `page`, `size` |
| `GET` | `/v1/orders/{orderId}` | Detail with `timeline` array |
| `GET` | `/v1/orders/{orderId}/timeline` | Timeline only (audit view) |
| `GET` | `/v1/entitlements` | Subscriber's active benefits ("My Benefits" screen) |

Sample `GET /v1/orders/{orderId}` response:
```json
{
  "orderId": "ord_01H...",
  "offerCode": "OTT_NETFLIX_6M",
  "offerName": "Netflix 6-month bundle",
  "amount": 599,
  "currency": "INR",
  "productType": "DIGITAL_SUBSCRIPTION",
  "status": "COMPLETED",
  "placedAt": "2026-05-27T10:00:00Z",
  "confirmedAt": "2026-05-27T10:00:02Z",
  "completedAt": "2026-05-27T10:00:03Z",
  "fulfilment": { "type": "DIGITAL_SUBSCRIPTION", "externalRef": "ott_ent_..." },
  "timeline": [
    { "at": "2026-05-27T10:00:00Z", "status": "PLACED" },
    { "at": "2026-05-27T10:00:02Z", "status": "CONFIRMED" },
    { "at": "2026-05-27T10:00:03Z", "status": "COMPLETED" }
  ]
}
```

---

## OIDC endpoints (Gateway as OpenID Provider)

| Path | Purpose |
|---|---|
| `GET /oidc/authorize` | Authorization Code + PKCE start |
| `POST /oidc/token` | Token exchange |
| `GET /oidc/userinfo` | ID claims for the Relying Party (OTT) |
| `GET /oidc/.well-known/openid-configuration` | Discovery document |
| `GET /oidc/jwks` | Public keys |

---

## Service-to-service (not exposed via Gateway)

| Caller | Path | Auth | Notes |
|---|---|---|---|
| Order Saga → OTT | `POST /admin/entitlements` | OAuth2 client credentials | Provision entitlement after order success |
| Order Saga → OTT | `DELETE /admin/entitlements/{externalRef}` | OAuth2 client credentials | Compensation (revoke) |
| OTT → Gateway | `GET /oidc/userinfo` | Bearer access token | OTT verifies subscriber identity |

`POST /admin/entitlements` body:
```json
{
  "subscriberId": "sub_...",
  "offerCode": "OTT_NETFLIX_6M",
  "validFrom": "2026-05-27T10:00:00Z",
  "validUntil": "2026-11-27T10:00:00Z",
  "sagaId": "saga_01H..."
}
```
Idempotent on `sagaId`. Returns `409` if active entitlement already exists for `(subscriberId, offerCode)`.

---

## Error response shape (standard across all services)

```json
{
  "error": "ENTITLEMENT_ALREADY_ACTIVE",
  "message": "An active entitlement already exists for this offer.",
  "correlationId": "uuid",
  "timestamp": "2026-05-27T10:00:00Z"
}
```
