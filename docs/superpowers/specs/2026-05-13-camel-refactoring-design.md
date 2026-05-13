# Design Spec: Camel REST DSL Refactoring

**Date:** 2026-05-13
**Branch:** `refactor/camel-rest-dsl`
**Status:** Approved
**Follow-on:** Web UI branch (separate spec)

---

## 1. Goal

Make the application a full Apache Camel application by migrating the REST layer from Quarkus JAX-RS to Camel REST DSL, replacing shell-script-based seed data with a dev-profile Camel timer route, enabling Camel's BacklogTracer and standard tracer, and adding targeted in-code documentation.

The web UI (showing ISO 8583 ↔ JSON transformation steps in a browser) is out of scope for this branch — it depends on the BacklogTracer API established here.

---

## 2. What Changes and Why

### 2.1 REST Layer: JAX-RS → Camel REST DSL

**Current state:** `TransactionResource.java` is a standard Quarkus JAX-RS resource (`@Path`, `@GET`, `@POST`). It uses `IsoMessageSender` — an `@ApplicationScoped` wrapper around `ProducerTemplate` introduced solely to make the template mockable in tests, since `ProducerTemplate` is `@Dependent` scope and cannot be `@InjectMock`ed directly.

**New state:** A single `RestApiRoute.java` RouteBuilder using:
- `restConfiguration().component("platform-http")` — routes REST traffic through Quarkus's built-in Vert.x HTTP server (no separate HTTP server; same port 8080)
- `rest("/transactions")` with four endpoint definitions
- Each endpoint routes to a `direct:` sub-route containing the business logic

`IsoMessageSender.java` is deleted — no longer needed because the async dispatch (`asyncSendBody`) happens inside a Camel route processor, which is testable via `AdviceWith` without mocking the template itself.

**Why platform-http?** It integrates with the Vert.x event loop Quarkus already runs. The alternatives (`camel-quarkus-servlet`, `camel-quarkus-netty-http`) either use an older servlet bridge or start a second HTTP server — both are less idiomatic for Quarkus 3.x.

### 2.2 Seed Data: Shell Scripts → Camel Timer Route

**Current state:** `scripts/seed/*.json` files are submitted via `scripts/demo.sh` (curl loop). Requires the demo operator to run a separate terminal and script.

**New state:** `SeedDataRoute.java` annotated `@IfBuildProfile("dev")` fires exactly once, 3 seconds after Camel starts (`timer:seed?delay={{seed.delay-ms}}&repeatCount=1`). It builds a list of four `TransactionRequest` objects, splits them, and routes each through `direct:rest-submit` — the identical path a real REST POST takes.

`scripts/seed/*.json` files are removed. `scripts/demo.sh` is simplified to a polling-only script that reads results without submitting anything (the seed data is already there).

**Why Camel timer, not `@Observes StartupEvent`?** Stays pure Camel. The configurable delay avoids the race condition where the Netty client routes aren't yet connected to the switch at CDI startup time.

### 2.3 Camel Tracer

Two mechanisms, both scoped to the dev profile:

| Mechanism | Config property | Default | Purpose |
|---|---|---|---|
| BacklogTracer | `camel.main.backlog-tracing=true` | **on** in dev | Stores last 500 exchanges in memory; follow-on UI branch reads this |
| Standard tracer | `camel.main.tracing=false` | **off** in dev | Logs every exchange step to console; useful but noisy — toggle manually |

Production (`%prod.*` or no override): both off.

### 2.4 In-Code Documentation

Targeted comments, not exhaustive Javadoc everywhere:

- **Routes** (`RestApiRoute`, `ISO8583SendRoute`, `ISO8583ServerRoute`, `CleanupRoute`): one-line class Javadoc stating role in the flow; inline comments on non-obvious steps
- **Processors** (`JsonToIsoProcessor`, `IsoToJsonProcessor`): class Javadoc; per-field comment stating ISO 8583 field number, type, and significance
- **Codec** (`ISO8583Decoder`, `ISO8583Encoder`): comment on 2-byte length prefix framing; note on `ByteToMessageDecoder` non-`@Sharable` constraint

### 2.5 Refactoring Documentation

New file: `docs/refactoring-v2.md` — covers what changed, dependency diff, new route map, and how to run the app after the refactor.

---

## 3. File Map

### Deleted
```
src/main/java/id/redhat/razhari/rest/TransactionResource.java
src/main/java/id/redhat/razhari/route/IsoMessageSender.java
scripts/seed/01-visa-auth.json
scripts/seed/02-mastercard-auth.json
scripts/seed/03-eur-auth.json
scripts/seed/04-high-value-auth.json
```

### Created
```
src/main/java/id/redhat/razhari/route/RestApiRoute.java
src/main/java/id/redhat/razhari/route/SeedDataRoute.java
docs/refactoring-v2.md
```

### Modified
```
pom.xml                          — dependency swap
src/main/resources/application.properties   — tracer + seed config
src/main/java/id/redhat/razhari/route/ISO8583SendRoute.java    — comments
src/main/java/id/redhat/razhari/route/ISO8583ServerRoute.java  — comments
src/main/java/id/redhat/razhari/route/CleanupRoute.java        — comments
src/main/java/id/redhat/razhari/codec/ISO8583Decoder.java      — comments
src/main/java/id/redhat/razhari/codec/ISO8583Encoder.java      — comments
src/main/java/id/redhat/razhari/processor/JsonToIsoProcessor.java  — comments
src/main/java/id/redhat/razhari/processor/IsoToJsonProcessor.java  — comments
src/test/java/id/redhat/razhari/rest/TransactionResourceTest.java  — rewritten
scripts/demo.sh                  — simplified to poll-only
```

---

## 4. Route Map (After Refactor)

```
REST incoming (platform-http :8080)
  POST   /transactions          → direct:rest-submit
  GET    /transactions/{id}     → direct:rest-get-by-id
  GET    /transactions/{id}/status → direct:rest-get-status
  GET    /transactions          → direct:rest-list

direct:rest-submit
  → build TransactionState (store.save, stanGenerator.next)
  → asyncSendBody("direct:send-iso8583", state)
  → return {transactionId, 202}

direct:send-iso8583
  → jsonToIsoProcessor (TransactionState → IsoMessage)
  → netty:tcp://switch:8583 (sync=true, request-reply)
  → isoToJsonProcessor (IsoMessage → store.update COMPLETED)
  [onException → store.update FAILED]

netty:tcp://0.0.0.0:9583  (inbound from switch)
  → isoToJsonProcessor (store.save RECEIVED)
  → build 0210/0410 ACK → return to switch

timer:cleanup  (every cleanup-interval-ms)
  → move PENDING older than timeout-ms → TIMEOUT

timer:seed  (once, delay seed.delay-ms, dev profile only)
  → split [4 × TransactionRequest]
  → direct:rest-submit
```

---

## 5. Dependency Changes

| Action | Artifact |
|---|---|
| Remove | `io.quarkus:quarkus-rest` |
| Remove | `io.quarkus:quarkus-rest-jackson` |
| Add | `org.apache.camel.quarkus:camel-quarkus-platform-http` |
| Add | `org.apache.camel.quarkus:camel-quarkus-rest` |
| Keep | `org.apache.camel.quarkus:camel-quarkus-jackson` (already present) |

---

## 6. Configuration Changes

```properties
# Seed data (dev only)
%dev.seed.delay-ms=3000

# Camel tracer (dev only)
%dev.camel.main.backlog-tracing=true
%dev.camel.main.backlog-tracing-standby=false
%dev.camel.main.tracing=false
```

---

## 7. Testing Strategy

`TransactionResourceTest` is renamed to `TransactionRestRouteTest` and rewritten:

- Still uses `@QuarkusTest` + `RestAssured` — HTTP still served by Vert.x on port 8080
- `@InjectMock TransactionStore store` — unchanged, still mockable
- `AdviceWith` stubs `direct:send-iso8583` → `mock:send-iso8583` so submit tests don't need a live switch
- `FullFlowIT` unchanged — end-to-end test still uses `MockISO8583Switch` + Awaitility

---

## 8. Out of Scope

- Web UI (separate branch, depends on BacklogTracer API established here)
- Any changes to `TransactionStore` interface or implementations
- Any changes to the ISO 8583 codec or channel initializers (beyond comments)
- Infinispan / PostgreSQL / broker integrations (v2/v3/v4 roadmap items)
