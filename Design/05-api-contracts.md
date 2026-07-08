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
| `GET` | `/v1/ops/orders` | Ops dashboard search over `order_search_v1`. Filters: `status`, `offerCode`, `from`, `to`, `limit` (§B3) |

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

## OIDC endpoints (OpenID Provider = self-hosted Keycloak, DD-29)

Provided by **Keycloak's `vab` realm**, not by the gateway (the gateway is an edge resource server, not the OP).

| Path | Purpose |
|---|---|
| `GET /realms/vab/protocol/openid-connect/auth` | Authorization Code + PKCE start |
| `POST /realms/vab/protocol/openid-connect/token` | Token exchange (auth-code + PKCE / client-credentials) |
| `GET /realms/vab/protocol/openid-connect/userinfo` | ID claims for the Relying Party (OTT) |
| `GET /realms/vab/.well-known/openid-configuration` | Discovery document |
| `GET /realms/vab/protocol/openid-connect/certs` | JWKS public keys |

---

## OTT subscriber surface (§A-2)

Served by ott-service, protected by **OIDC login** (Authorization Code + PKCE, session) — not the Bearer provisioning API. The subscriber is identified by the `subscriberId` id-token claim.

| Method / Path | Auth | Notes |
|---|---|---|
| `GET /v1/videos` | Session (logged-in subscriber) | Seeded catalog: `[{id, title, offerCode}]` |
| `GET /v1/videos/{id}/stream` | Session + entitlement | `200 {"message":"Playing video: <title>"}` if an ACTIVE entitlement for the video's `offerCode` exists; `403` otherwise; `404` unknown id. No real media. |

---

## Edge authentication (§A-3/A5, DD-31)

The gateway is an OAuth2 **resource server** (Keycloak JWKS). Callers send `Authorization: Bearer <access_token>`; the token is **relayed** downstream and order-service re-validates it. Identity is the `subscriberId` **claim**, never client input.

| Route | Auth |
|---|---|
| `GET /v1/offers/**` (catalog browse) | **public** |
| `POST /v1/orders`, `POST /v1/orders/{id}/cancel` | authenticated — subject = JWT `subscriberId` (no body/param) |
| `GET /v1/orders`, `GET /v1/entitlements` | authenticated — "my orders" / "my benefits", scoped to the JWT subject |
| `GET /v1/orders/{id}`, `.../timeline` | authenticated |
| `POST /v1/orders/{id}/{retry,complete,revoke}-*`, `GET /v1/ops/**` | **`vab-admin`** realm role |

Unauthenticated → `401`; authenticated but lacking the role → `403`.

---

## Service-to-service (not exposed via Gateway)

| Caller | Path | Auth | Notes |
|---|---|---|---|
| Order Saga → OTT | `POST /ott/v1/entitlements` | OAuth2 client credentials (scope `ott:provision`, `aud: ott-service`) | Provision entitlement after order success (as-built path; the original design wrote `/admin/entitlements`) |
| Order Saga → OTT | `DELETE /ott/v1/entitlements/{...}` | OAuth2 client credentials | Compensation (revoke) |
| OTT → Keycloak | `GET /realms/vab/protocol/openid-connect/userinfo` | Bearer access token | OTT verifies subscriber identity |

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
