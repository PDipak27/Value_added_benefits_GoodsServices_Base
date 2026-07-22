# VA-BAGS Design Documents

| # | File | Contents |
|---|------|----------|
| 01 | [01-system-context.md](01-system-context.md) | System context, service map, sync/async edge matrix |
| 02 | [02-service-responsibilities.md](02-service-responsibilities.md) | Per-service bounded context, owns/doesn't-own |
| 03 | [03-order-service-deep-dive.md](03-order-service-deep-dive.md) | CQRS layout, event catalog, projections, idempotency |
| 04 | [04-saga-design.md](04-saga-design.md) | Saga state machine, step table, failure modes |
| 05 | [05-api-contracts.md](05-api-contracts.md) | REST surface, request/response sketches |
| 06 | [06-ott-auth.md](06-ott-auth.md) | OIDC flow, provisioning, OTT platform design |
| 07 | [07-infra-and-stack.md](07-infra-and-stack.md) | Stack choices, docker-compose services, repo layout |
| 08 | [08-design-decisions.md](08-design-decisions.md) | Design Decisions Report (ADR-style) |
| 09 | [09-product-types-redesign.md](09-product-types-redesign.md) | Product-types redesign (DIGITAL/LICENSE/PHYSICAL, fulfilment-service) |
| 10 | [10-drift-and-backlog.md](10-drift-and-backlog.md) | Iter 1–3 design vs current build: drift reconciliation + pending backlog |
| 11 | [11-deployment-k8s.md](11-deployment-k8s.md) | Local Kubernetes deployment — phased plan (containerize → k8s → CI/CD), concepts + interview crib |
| — | [diagrams.mmd](diagrams.mmd) | All Mermaid diagrams in one raw file (paste into mermaid.live) |
