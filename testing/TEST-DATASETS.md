# VA-BAGS — Test Datasets & Path Coverage

Import `VA-BAGS.postman_collection.json` into Postman. Variables default to direct
service ports; set `gateway`/`order`/`catalog` to taste. No auth (JWT/OIDC deferred).

| Service | Direct | Via gateway (`:8080`) |
|---|---|---|
| catalog | `:8085` | `/v1/offers/**` |
| order (command+query) | `:8081` | `/v1/orders/**` |
| inventory | `:8082` | saga participant (no inbound REST) |
| billing | `:8083` | saga participant (no inbound REST) |
| fulfilment | `:8086` | saga participant (no inbound REST) |
| notification | `:8084` | event consumer only (no inbound REST) |

Order placement is **async** (202 + `Location`). The saga runs across order →
inventory → billing → fulfilment; poll `GET /v1/orders/{orderId}` until terminal
(`COMPLETED` / `FAILED` / `CANCELLED` / `CANCELLED_REFUNDED`). `CONFIRMED` is an
intermediate milestone; the order then settles to `COMPLETED` (DD-23). A pre-pivot
decline or cancel rolls back to `FAILED` / `CANCELLED` (nothing charged); a non-transient
post-pivot fulfil failure or late cancel **forward-recovers** to `CANCELLED_REFUNDED`
(DD-26).

---

## Seed data (must match for predictable results)

**Catalog offers** (`CatalogSeeder`, MongoDB `vab_catalog.offers`) — across the three
**product types** (Design/09):

| offerCode | productType | amount | minPlanTier | region | maxDeviceAgeMonths | minKycLevel | status |
|---|---|---|---|---|---|---|---|
| `OTT_NETFLIX_6M` | DIGITAL_SUBSCRIPTION | 599 | BASIC | IN | — | MINIMAL | PUBLISHED |
| `OTT_PRIME_12M` | DIGITAL_SUBSCRIPTION | 999 | PLUS | IN | — | MINIMAL | PUBLISHED |
| `OTT_HOTSTAR_3M` | DIGITAL_SUBSCRIPTION | 499 | BASIC | IN | — | MINIMAL | PUBLISHED |
| `SW_MSOFFICE_1Y` | SOFTWARE_LICENSE | 899 | BASIC | IN | — | MINIMAL | PUBLISHED |
| `SW_ANTIVIRUS_1Y` | SOFTWARE_LICENSE | 499 | BASIC | IN | — | MINIMAL | PUBLISHED |
| `ACC_BUDS_PRO` | PHYSICAL_GOOD | 1499 | PREMIUM | IN | 24 | FULL | PUBLISHED |
| `ACC_POWERBANK_20K` | PHYSICAL_GOOD | 899 | BASIC | IN | — | MINIMAL | PUBLISHED |
| `OTT_LEGACY_3M` | DIGITAL_SUBSCRIPTION | 399 | BASIC | IN | — | NONE | **WITHDRAWN** |

**Inventory items** (Postgres `inventory.inventory_items`) — **all three** product
types are now finite and have a row (DD-23); `DIGITAL_SUBSCRIPTION` is a seeded count.

| offer_code | type | total | reserved | allocated | notes |
|---|---|---|---|---|---|
| `ACC_BUDS_PRO` | PHYSICAL_GOOD | 30 | 0 | 0 | stock count |
| `ACC_POWERBANK_20K` | PHYSICAL_GOOD | **5** | 0 | 0 | low stock — exhaustion test |
| `SW_MSOFFICE_1Y` | SOFTWARE_LICENSE | 5 | 0 | 0 | 5 keys in `license_keys` |
| `SW_ANTIVIRUS_1Y` | SOFTWARE_LICENSE | **3** | 0 | 0 | **3 keys** — pool-exhaustion test |
| `OTT_NETFLIX_6M` | DIGITAL_SUBSCRIPTION | 100 | 0 | 0 | seeded count (V4) |
| `OTT_PRIME_12M` | DIGITAL_SUBSCRIPTION | 100 | 0 | 0 | seeded count (V4) |
| `OTT_HOTSTAR_3M` | DIGITAL_SUBSCRIPTION | 100 | 0 | 0 | seeded count (V4) |

**License keys** (`inventory.license_keys`, seeded by the same migration):

| offer_code | keys | status |
|---|---|---|
| `SW_MSOFFICE_1Y` | `MSOFF-1Y-AAAA-0001` … `0005` | FREE |
| `SW_ANTIVIRUS_1Y` | `NORTON-1Y-BBBB-0001` … `0003` | FREE |

> The saga holds inventory with a **type-agnostic** command `(offerCode, qty=1)`;
> inventory resolves the type from the stored item and dispatches internally (Design/09).
> The hold differs by **payment mode** (DD-23): `PAY_NOW` does a two-phase
> `reserve` → `commit` (a temporary hold the sweeper auto-releases on expiry), while
> `BILL_TO_MOBILE` does a one-step `allocate`. Either way `PHYSICAL_GOOD`/`DIGITAL_SUBSCRIPTION`
> move a count and `SOFTWARE_LICENSE` takes one concrete key (FREE → ALLOCATED), returned
> as `activationKey`. **All three types are finite**, so all three can exhaust.
> Failure reasons flavour by type: `OUT_OF_STOCK` (physical/digital count) /
> `POOL_EXHAUSTED` (license). An offer with no inventory row (e.g. the withdrawn
> `OTT_LEGACY_3M`) ⇒ `ITEM_NOT_FOUND`.

**Billing rules (DD-23/DD-26):** for `PAY_NOW`, `amount > 999` ⇒ `BillingDeclined` at the
authorize step (triggers compensation → `FAILED`); a capture `amount == 777` ⇒
`BillingCaptureFailed` **withFailure** at the pivot (DD-26) — the pivot failed to commit,
so the holds roll back and the order ends `FAILED` (nothing captured → no refund). For
`BILL_TO_MOBILE`, `checkAccountLimit` rejects a `SUSPENDED` account or an amount above the
subscriber's credit limit (default 1000) ⇒ `AccountLimitExceeded`. A non-transient
**fulfil** failure (offer code containing `FAIL`) is a post-pivot success-outcome that
forward-recovers to `CANCELLED_REFUNDED` (DD-26).

> Note `ACC_BUDS_PRO` (1499) sits **above** the billing limit and requires
> `PREMIUM`/`FULL`/device-age ≤ 24 — use it for billing-decline / strict-eligibility
> cases, **not** as a happy path. `ACC_POWERBANK_20K` (899) is the physical happy path.

---

## Catalog — read paths

`SubscriberProfile` = `{ planTier, region, deviceAgeMonths, kycLevel }`. Null fields
don't fail their rule. Eligibility passes when: `planTier ≥ minPlanTier`,
`region ∈ allowedRegions`, `deviceAgeMonths ≤ maxDeviceAgeMonths`, `kycLevel ≥ minKycLevel`.

| Path | Request | Expect |
|---|---|---|
| List — broad | `GET /v1/offers?planTier=PREMIUM&region=IN&deviceAgeMonths=12&kycLevel=FULL` | all 7 published offers |
| List — narrow | `GET /v1/offers?planTier=BASIC&region=IN&kycLevel=MINIMAL` | the BASIC/MINIMAL offers (Netflix, Hotstar, both SW, powerbank); not Prime (PLUS) or Buds (PREMIUM/FULL) |
| List — no params | `GET /v1/offers` | published offers whose rules pass with an all-null profile |
| Detail OK | `GET /v1/offers/OTT_NETFLIX_6M` | 200 + `priceSnapshotId` + `productType` |
| Detail withdrawn | `GET /v1/offers/OTT_LEGACY_3M` | 404 (withdrawn filtered) |
| Detail unknown | `GET /v1/offers/NOPE_404` | 404 |
| Evaluate eligible | `POST /v1/offers/ACC_BUDS_PRO:evaluate` body `{PREMIUM,IN,12,FULL}` | eligible=true |
| Evaluate ineligible | `POST /v1/offers/ACC_BUDS_PRO:evaluate` body `{BASIC,IN,40,NONE}` | eligible=false (tier+device-age+KYC all fail) |

## Catalog — admin paths

| Path | Request | Expect |
|---|---|---|
| Create | `POST /v1/offers` (body = a new offer) | 201, evicts caches |
| Update | `PUT /v1/offers/OTT_HOTSTAR_3M` (price 499→399) | 200, evicts caches |
| Withdraw | `POST /v1/offers/OTT_HOTSTAR_3M:withdraw` | 200; subsequent detail GET ⇒ 404 |
| Withdraw unknown | `POST /v1/offers/NOPE_404:withdraw` | 404 |

`OfferRequest` body shape:
```json
{ "offerCode":"SW_VPN_1Y","name":"...","description":"...","productType":"SOFTWARE_LICENSE",
  "amount":499,"currency":"INR","priceSnapshotId":"ps_...","minPlanTier":"BASIC",
  "allowedRegions":"IN","maxDeviceAgeMonths":null,"minKycLevel":"MINIMAL" }
```

---

## Orders — command + saga outcomes

`POST /v1/orders`, header `Idempotency-Key: <UUIDv4>`, body
`{ subscriberId, offerCode, productType, priceSnapshotId, amount, currency, billingMode }`.

> `productType` is client-sent and **verified** against the catalog (fail-open): the
> order service prefers the catalog's resolved value when reachable, falls back to the
> client value otherwise (DD-22). The resolved type drives the inventory dispatch and
> the fulfilment artifact; `billingMode` (`PAY_NOW` / `BILL_TO_MOBILE`) drives which
> saga flow runs (DD-23).

Two saga flows by `billingMode` (DD-23; pivot + recovery revised by DD-26).
**PAY_NOW:** reserve → authorize → commit → **capture (pivot) → confirm** → fulfil →
complete. **BILL_TO_MOBILE:** checkAccountLimit → allocate → **appendLedger (pivot) →
confirm** → fulfil → complete. The **pivot is the charge** (`capture` / `appendLedger`)
and the order is `CONFIRMED` immediately after it; steps before it roll back LIFO,
steps after it are forward-only. A PAY_NOW hard capture decline is the pivot *failing to
commit* (`withFailure`) → rollback → `FAILED` (nothing captured → no refund). A
non-transient post-pivot fulfil failure (or late cancel) does not roll back — it
**forward-recovers** (refund/reverseLedger → release → `CANCELLED_REFUNDED`) — DD-26.

| Scenario | offerCode / productType | mode | amount | Saga flow | Terminal | Artifact |
|---|---|---|---|---|---|---|
| **Happy — DIGITAL (postpaid)** | `OTT_NETFLIX_6M` / DIGITAL_SUBSCRIPTION | BILL_TO_MOBILE | 599 | checkLimit → allocate → appendLedger → confirm → fulfil (entitlement) | **COMPLETED** | `externalRef` |
| **Happy — PHYSICAL (prepaid)** | `ACC_POWERBANK_20K` / PHYSICAL_GOOD | PAY_NOW | 899 | reserve → authorize → commit → capture → confirm → fulfil (shipment) | **COMPLETED** | `trackingRef` |
| **Happy — LICENSE (prepaid)** | `SW_ANTIVIRUS_1Y` / SOFTWARE_LICENSE | PAY_NOW | 499 | reserve (key) → authorize → commit → capture → confirm → fulfil (echo key) | **COMPLETED** | `activationKey` |
| **Billing decline (PAY_NOW)** | `ACC_POWERBANK_20K` / PHYSICAL_GOOD | PAY_NOW | 1500 | reserve OK → authorize declines → release reservation (LIFO) | **FAILED** | — |
| **Capture hard-decline (PAY_NOW, DD-26)** | `SW_ANTIVIRUS_1Y` / SOFTWARE_LICENSE | PAY_NOW | 777 | reserve → authorize → commit → capture **withFailure** (pivot) → rollback holds | **FAILED** | — (no charge) |
| **Fulfil failure → forward-recovery (DD-26)** | `OFF-FAIL` / PHYSICAL_GOOD | PAY_NOW | 499 | reserve → authorize → commit → capture → confirm → fulfil **DELIVERY_FAILED** → refund → release | **CANCELLED_REFUNDED** | — (refunded) |
| **Credit-limit exceeded (postpaid)** | `ACC_POWERBANK_20K` / PHYSICAL_GOOD | BILL_TO_MOBILE | 1500 | checkAccountLimit ⇒ `CREDIT_LIMIT_EXCEEDED` (before allocate) | **FAILED** | — |
| **Account suspended (postpaid)** | `OTT_NETFLIX_6M` / DIGITAL_SUBSCRIPTION (`subscriberId=sub-suspended`) | BILL_TO_MOBILE | 599 | checkAccountLimit ⇒ `ACCOUNT_SUSPENDED` | **FAILED** | — |
| **License pool exhausted** | `SW_ANTIVIRUS_1Y` / SOFTWARE_LICENSE | PAY_NOW | 499 | 4th order (pool=3) → reserve ⇒ `POOL_EXHAUSTED` | **FAILED** | — |
| **Physical out of stock** | `ACC_POWERBANK_20K` / PHYSICAL_GOOD | PAY_NOW | 899 | 6th order (stock=5) → reserve ⇒ `OUT_OF_STOCK` | **FAILED** | — |
| **Idempotent replay** | `OTT_NETFLIX_6M` / DIGITAL_SUBSCRIPTION | BILL_TO_MOBILE | 599 | same `Idempotency-Key` twice | same `orderId` | — |

> **PAY_NOW billing decline** uses amount 1500 so the reservation succeeds and the
> failure isolates to authorize — LIFO compensation then releases the reserved unit
> (verify `reserved` drops back). **BILL_TO_MOBILE** guards earlier at `checkAccountLimit`,
> *before* any inventory is held, so a limit/suspended failure has nothing to release.
> **License exhaustion:** complete 3 orders for `SW_ANTIVIRUS_1Y` first (each consumes a
> key), then the 4th fails `POOL_EXHAUSTED`. Compensating an in-flight order returns its
> key to the FREE pool, so a later order succeeds again.

**Validation (4xx, no saga):**

| Scenario | Header | Expect |
|---|---|---|
| Missing key | (no `Idempotency-Key`) | 400 |
| Invalid key | `Idempotency-Key: not-a-uuid` | 400 (must be UUID) |

## Orders — query paths

| Path | Expect |
|---|---|
| `GET /v1/orders/{orderId}` | 200; read model incl. `productType` + `fulfilment` (one artifact), else read-your-writes fallback to Postgres |
| `GET /v1/orders/ord_does_not_exist` | 404 |
| `GET /v1/orders?subscriberId=sub_test_001` | list from read model (no fallback) |

---

## Suggested run order

1. **Health** — all services green.
2. **Catalog – Read** then **Catalog – Admin** (create → update → withdraw).
3. **Orders – Command → Place order** for each product type, in both `PAY_NOW` and
   `BILL_TO_MOBILE` modes (captures `{{orderId}}`); poll **Query → Get order by id**
   until `COMPLETED`; verify the per-type artifact.
4. **PAY_NOW billing decline** (physical, amount 1500) → poll to `FAILED`; verify the
   reservation returns. Then **BILL_TO_MOBILE** credit-limit (amount 1500) and
   suspended-account (`sub-suspended`) cases → `FAILED` before any hold.
5. **PAY_NOW capture hard-decline** (license/digital, amount 777, DD-26) → poll to
   `FAILED`; verify the holds roll back (nothing captured, no artifact).
6. **Fulfil failure → forward-recovery** (offer code containing `FAIL`, DD-26) → poll to
   `CANCELLED_REFUNDED`; verify the charge is refunded and inventory released.
7. **User cancel** (`POST /v1/orders/{id}/cancel`) → pre-pivot lands `CANCELLED`;
   post-pivot/pre-fulfil lands `CANCELLED_REFUNDED`; once terminal → `409`.
8. **License pool exhaustion** — 4 orders for `SW_ANTIVIRUS_1Y`; the 4th → `FAILED`.
9. **Physical out of stock** — 6 orders for `ACC_POWERBANK_20K`; the 6th → `FAILED`.
10. **Idempotent replay** — run twice, assert same `orderId`.
11. Validation cases (missing/invalid key) → 400.

> If an order is stuck at `PLACED`: CDC isn't relaying the outbox to Kafka — see
> `deploy/README.md` troubleshooting. Reaching `CONFIRMED`/`COMPLETED` depends on the
> full async chain.
