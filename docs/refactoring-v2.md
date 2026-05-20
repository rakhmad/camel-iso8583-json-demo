# Refactoring v2 — Full Camel REST DSL Migration

**Branch:** `refactor/camel-rest-dsl`
**Date:** 2026-05-13

## What Changed

### 1. REST Layer: JAX-RS → Camel REST DSL

`TransactionResource.java` (JAX-RS) and `IsoMessageSender.java` (its ProducerTemplate wrapper)
are replaced by `RestApiRoute.java` and `IsoDispatcher.java`.

`RestApiRoute` uses `restConfiguration().component("platform-http").contextPath("/api/v1")`
and four `rest("/transactions")` endpoint definitions. Each routes to a `direct:` sub-route:

| Endpoint                                | Sub-route              |
|-----------------------------------------|------------------------|
| POST   /api/v1/transactions             | direct:rest-submit     |
| GET    /api/v1/transactions/{id}        | direct:rest-get-by-id  |
| GET    /api/v1/transactions/{id}/status | direct:rest-get-status |
| GET    /api/v1/transactions             | direct:rest-list       |

**Why platform-http?** It integrates with Quarkus's built-in Vert.x HTTP server — no second
HTTP server is started. `camel-quarkus-jackson` handles JSON binding automatically.

**Why IsoDispatcher?** `ProducerTemplate` is `@Dependent`-scoped in Camel's CDI and cannot be
`@InjectMock`ed in `@QuarkusTest`. `IsoDispatcher` is a thin `@ApplicationScoped` wrapper that
exposes a typed `dispatch(TransactionState)` method — identical in purpose to the old
`IsoMessageSender` but scoped correctly for test isolation.

**`RestBindingMode.off` + explicit JacksonDataFormat:** Camel's default Jackson DataFormat does
not include `JavaTimeModule`, causing `InvalidDefinitionException` for `java.time.Instant`.
Using `RestBindingMode.off` with a `JacksonDataFormat` constructed from Quarkus's CDI-managed
`ObjectMapper` (which has all modules auto-configured) avoids this problem.

### 2. Startup Seed Data: Shell Scripts → Camel Timer Route

`SeedDataRoute.java` (annotated `@IfBuildProfile("dev")`) fires once 3 seconds after Camel
starts, using `timer:seed?delay={{seed.delay-ms}}&repeatCount=1`. It splits a list of four
`TransactionRequest` objects and routes each through `direct:rest-submit` — identical to a
real REST POST. The switch receives live ISO 8583 messages and responds with 0210 approvals.

The `scripts/seed/` directory and its JSON files are deleted. `scripts/demo.sh` is now
poll-only: it waits for the four seed transactions to reach a terminal state and prints results.

**Note on Camel route discovery:** `@IfBuildProfile("dev")` suppresses CDI instantiation but
not Camel Quarkus's own classpath route scanner. Test properties include
`quarkus.camel.routes-discovery.exclude-patterns=id/redhat/razhari/route/SeedDataRoute`
to prevent the timer from firing during unit tests.

### 3. Camel Tracer

Two tracing mechanisms are enabled in the dev profile:

| Mechanism       | Property                                  | Default |
|-----------------|-------------------------------------------|---------|
| BacklogTracer   | `camel.main.backlog-tracing=true`         | **on**  |
| Standard tracer | `camel.main.tracing=false`                | **off** |

BacklogTracer stores the last 500 exchanges in memory. The follow-on UI branch will expose this
via a REST endpoint to show ISO 8583 ↔ JSON transformation steps in a browser.

Standard tracer logs every exchange step to the console. It is off by default (noisy with seed
traffic) but can be toggled live in the Camel dev console without an app restart.

### 4. Code Comments

Javadoc added to: `RestApiRoute`, `SeedDataRoute`, `ISO8583SendRoute`, `ISO8583ServerRoute`,
`CleanupRoute`, `JsonToIsoProcessor`, `IsoToJsonProcessor`, `ISO8583Decoder`, `ISO8583Encoder`.

Key inline comments document:
- ISO 8583 field number semantics (PAN, STAN, amount format, currency code, auth code)
- The `originalState` exchange property pattern (stash before processor replaces body)
- The ACK MTI derivation (`incoming.getType() + 0x0010`)
- `ByteToMessageDecoder` non-`@Sharable` constraint and why new instances per channel
- The 2-byte length prefix framing contract between encoder and decoder

## Dependency Changes

| Action  | Artifact                                          |
|---------|---------------------------------------------------|
| Removed | `io.quarkus:quarkus-rest`                         |
| Removed | `io.quarkus:quarkus-rest-jackson`                 |
| Added   | `org.apache.camel.quarkus:camel-quarkus-platform-http` |
| Added   | `org.apache.camel.quarkus:camel-quarkus-rest`     |

## Running After This Refactor

```bash
# Terminal 1 — mock switch
./scripts/mock-switch.sh

# Terminal 2 — app (seed fires automatically after 3s)
./mvnw quarkus:dev

# Terminal 3 — poll results
./scripts/demo.sh
```

Seed transactions appear as COMPLETED ~4 seconds after the app starts.

## What's Next (Follow-on Branch)

A web UI branch will add a static HTML dashboard at `/` that:
- Polls `GET /api/v1/transactions` every 2 seconds for a live transaction table
- Uses the BacklogTracer API to show ISO 8583 field values and JSON results side-by-side
- Displays the transformation steps for each exchange
