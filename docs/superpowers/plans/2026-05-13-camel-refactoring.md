# Camel REST DSL Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Quarkus JAX-RS REST layer with Camel REST DSL using platform-http, add a dev-profile startup seed route, enable Camel tracing, add targeted code comments, and write a refactoring doc.

**Architecture:** All HTTP traffic is handled by a single `RestApiRoute` using `restConfiguration().component("platform-http").contextPath("/api/v1")`. Each REST endpoint routes to a `direct:` sub-route for business logic. A `SeedDataRoute` (dev profile only) fires once on startup via a Camel timer to pre-populate demo transactions through the same `direct:rest-submit` path real requests use.

**Tech Stack:** Quarkus 3.27, Apache Camel 4.14, camel-quarkus-platform-http, camel-quarkus-rest, camel-quarkus-jackson, j8583 (via camel-quarkus-iso8583), Netty, JUnit 5, RestAssured, Mockito.

---

## Context for the implementer

Branch: `refactor/camel-rest-dsl` (already created). Working directory: `/Users/razhari/tmp/camel-iso-json`.

**Spec:** `docs/superpowers/specs/2026-05-13-camel-refactoring-design.md`

**Key package:** `id.redhat.razhari` under `src/main/java/`.

**What exists today:**
- `rest/TransactionResource.java` — JAX-RS resource (`@Path`, `@GET`, `@POST`) — **to be deleted**
- `route/IsoMessageSender.java` — `@ApplicationScoped` wrapper around `ProducerTemplate` introduced so tests could `@InjectMock` it — **to be deleted** (no longer needed)
- `route/ISO8583SendRoute.java`, `ISO8583ServerRoute.java`, `CleanupRoute.java` — existing Camel routes, **not replaced**, only getting comments in Task 6
- `src/test/java/.../rest/TransactionResourceTest.java` — existing test **replaced** by `TransactionRestRouteTest.java`

**Why `IsoMessageSender` is deleted:** In the new design `RestApiRoute` injects `ProducerTemplate` directly. Since the REST logic is now inside a Camel route, tests verify behaviour through HTTP (RestAssured + mocked store) rather than through CDI mock injection of the sender.

**Why `quarkus.http.root-path` is removed:** `platform-http` does not reliably inherit `quarkus.http.root-path`. The base path `/api/v1` is set explicitly via `restConfiguration().contextPath("/api/v1")`. This is a safe change — the non-application endpoints (`/q/health`, `/q/dev`) use `quarkus.http.non-application-root-path` and are unaffected.

**Test path impact:** After removing `quarkus.http.root-path`, Quarkus no longer sets RestAssured's `basePath` to `/api/v1`. All test calls must use the full path `/api/v1/transactions/...`.

---

## Task 1: Swap dependencies and remove old REST files

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`
- Delete: `src/main/java/id/redhat/razhari/rest/TransactionResource.java`
- Delete: `src/main/java/id/redhat/razhari/route/IsoMessageSender.java`

- [ ] **Step 1: Update pom.xml — remove quarkus-rest dependencies, add Camel REST deps**

Replace the two Quarkus REST dependencies:
```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-jackson</artifactId>
        </dependency>
```
with:
```xml
        <dependency>
            <groupId>org.apache.camel.quarkus</groupId>
            <artifactId>camel-quarkus-platform-http</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.camel.quarkus</groupId>
            <artifactId>camel-quarkus-rest</artifactId>
        </dependency>
```
Both new artifacts are managed by the `quarkus-camel-bom` already in `<dependencyManagement>` — no version needed.

- [ ] **Step 2: Remove `quarkus.http.root-path` from main application.properties**

In `src/main/resources/application.properties`, delete this line:
```
quarkus.http.root-path=/api/v1
```

- [ ] **Step 3: Remove `quarkus.http.root-path` from test application.properties**

In `src/test/resources/application.properties`, delete this line:
```
quarkus.http.root-path=/api/v1
```

- [ ] **Step 4: Delete the old REST resource**

```bash
rm src/main/java/id/redhat/razhari/rest/TransactionResource.java
```

- [ ] **Step 5: Delete IsoMessageSender**

```bash
rm src/main/java/id/redhat/razhari/route/IsoMessageSender.java
```

- [ ] **Step 6: Verify compile fails as expected**

```bash
./mvnw compile -q 2>&1 | head -30
```

Expected: compilation errors about `jakarta.ws.rs` package not found and `IsoMessageSender` not found. This is correct — the old files are gone and the new route doesn't exist yet.

---

## Task 2: Implement RestApiRoute (TDD)

**Files:**
- Create: `src/main/java/id/redhat/razhari/route/RestApiRoute.java`
- Create: `src/test/java/id/redhat/razhari/rest/TransactionRestRouteTest.java`
- Delete: `src/test/java/id/redhat/razhari/rest/TransactionResourceTest.java`
- Modify: `src/test/java/id/redhat/razhari/functional/FullFlowIT.java`

- [ ] **Step 1: Delete the old REST test**

```bash
rm src/test/java/id/redhat/razhari/rest/TransactionResourceTest.java
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/id/redhat/razhari/rest/TransactionRestRouteTest.java`:

```java
package id.redhat.razhari.rest;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.TransactionStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class TransactionRestRouteTest {

    @InjectMock
    TransactionStore store;

    @Test
    void POST_returns_202_with_transaction_id() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "mti": "0200",
                  "pan": "4111111111111111",
                  "amount": 10000,
                  "currency": "840",
                  "terminalId": "TERM0001",
                  "merchantId": "MERCH001"
                }
                """)
        .when()
            .post("/api/v1/transactions")
        .then()
            .statusCode(202)
            .body("transactionId", matchesPattern("[0-9a-f-]{36}"));

        verify(store).save(any(TransactionState.class));
    }

    @Test
    void GET_by_id_returns_200_with_state_when_found() {
        TransactionState state = pendingState("test-id", "000001");
        when(store.findById("test-id")).thenReturn(Optional.of(state));

        given()
        .when()
            .get("/api/v1/transactions/test-id")
        .then()
            .statusCode(200)
            .body("transactionId", equalTo("test-id"))
            .body("status", equalTo("PENDING"));
    }

    @Test
    void GET_by_id_returns_404_when_not_found() {
        when(store.findById("unknown")).thenReturn(Optional.empty());

        given()
        .when()
            .get("/api/v1/transactions/unknown")
        .then()
            .statusCode(404);
    }

    @Test
    void GET_status_returns_lightweight_status_object() {
        TransactionState state = pendingState("id-1", "000001");
        when(store.findById("id-1")).thenReturn(Optional.of(state));

        given()
        .when()
            .get("/api/v1/transactions/id-1/status")
        .then()
            .statusCode(200)
            .body("status", equalTo("PENDING"))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue());
    }

    @Test
    void GET_list_returns_inbound_events_when_type_inbound() {
        TransactionState received = pendingState("id-1", "000001");
        received.status = TransactionStatus.RECEIVED;
        when(store.findByStatus(TransactionStatus.RECEIVED)).thenReturn(List.of(received));

        given()
            .queryParam("type", "inbound")
        .when()
            .get("/api/v1/transactions")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1));
    }

    @Test
    void GET_list_returns_all_when_no_type_param() {
        when(store.findAll()).thenReturn(List.of(
            pendingState("id-1", "000001"),
            pendingState("id-2", "000002")
        ));

        given()
        .when()
            .get("/api/v1/transactions")
        .then()
            .statusCode(200)
            .body("size()", equalTo(2));
    }

    private TransactionState pendingState(String id, String stan) {
        TransactionState s = new TransactionState();
        s.id = id;
        s.stan = stan;
        s.status = TransactionStatus.PENDING;
        s.createdAt = Instant.now();
        s.updatedAt = Instant.now();
        return s;
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
./mvnw test -Dtest=TransactionRestRouteTest -q 2>&1 | tail -20
```

Expected: compile error or 404 responses — `RestApiRoute` does not exist yet.

- [ ] **Step 4: Implement RestApiRoute**

Create `src/main/java/id/redhat/razhari/route/RestApiRoute.java`:

```java
package id.redhat.razhari.route;

import id.redhat.razhari.model.*;
import id.redhat.razhari.store.TransactionStore;
import id.redhat.razhari.util.StanGenerator;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Camel REST DSL entry point for all JSON API endpoints.
 * Uses platform-http (Quarkus Vert.x) as the HTTP transport — no separate server.
 * Each REST endpoint delegates to a direct: sub-route for business logic,
 * keeping the REST definition separate from the processing logic.
 */
@ApplicationScoped
public class RestApiRoute extends RouteBuilder {

    @Inject TransactionStore store;
    @Inject StanGenerator stanGenerator;
    @Inject ProducerTemplate producerTemplate;

    @Override
    public void configure() {
        restConfiguration()
            .component("platform-http")
            .contextPath("/api/v1")
            .bindingMode(RestBindingMode.json)
            .dataFormatProperty("prettyPrint", "true");

        rest("/transactions")
            .post()
                .consumes("application/json")
                .produces("application/json")
                .type(TransactionRequest.class)
                .to("direct:rest-submit")
            .get("/{id}")
                .produces("application/json")
                .to("direct:rest-get-by-id")
            .get("/{id}/status")
                .produces("application/json")
                .to("direct:rest-get-status")
            .get()
                .produces("application/json")
                .to("direct:rest-list");

        // Submit: save state as PENDING, fire async send to switch, return 202 + UUID
        from("direct:rest-submit")
            .routeId("rest-submit")
            .process(exchange -> {
                TransactionRequest req = exchange.getIn().getBody(TransactionRequest.class);

                TransactionState state = new TransactionState();
                state.id = UUID.randomUUID().toString();
                state.stan = stanGenerator.next();
                state.status = TransactionStatus.PENDING;
                state.request = req;
                state.createdAt = Instant.now();
                state.updatedAt = Instant.now();

                store.save(state);
                // Fire-and-forget: ISO8583SendRoute picks this up and contacts the switch
                producerTemplate.asyncSendBody("direct:send-iso8583", state);

                exchange.getMessage().setBody(Map.of("transactionId", state.id));
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 202);
            });

        // Poll by UUID: returns full TransactionResponse or 404
        from("direct:rest-get-by-id")
            .routeId("rest-get-by-id")
            .process(exchange -> {
                String id = exchange.getIn().getHeader("id", String.class);
                store.findById(id).ifPresentOrElse(
                    s -> exchange.getMessage().setBody(toResponse(s)),
                    () -> {
                        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                        exchange.getMessage().setBody(null);
                    }
                );
            });

        // Lightweight status check: returns {status, createdAt, updatedAt} or 404
        from("direct:rest-get-status")
            .routeId("rest-get-status")
            .process(exchange -> {
                String id = exchange.getIn().getHeader("id", String.class);
                store.findById(id).ifPresentOrElse(
                    s -> exchange.getMessage().setBody(Map.of(
                        "status",    s.status,
                        "createdAt", s.createdAt,
                        "updatedAt", s.updatedAt
                    )),
                    () -> {
                        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                        exchange.getMessage().setBody(null);
                    }
                );
            });

        // List: ?type=inbound returns RECEIVED events; no param returns all
        from("direct:rest-list")
            .routeId("rest-list")
            .process(exchange -> {
                String type = exchange.getIn().getHeader("type", String.class);
                List<TransactionState> states = "inbound".equals(type)
                    ? store.findByStatus(TransactionStatus.RECEIVED)
                    : store.findAll();
                exchange.getMessage().setBody(
                    states.stream().map(this::toResponse).collect(Collectors.toList())
                );
            });
    }

    private TransactionResponse toResponse(TransactionState s) {
        TransactionResponse r = new TransactionResponse();
        r.transactionId = s.id;
        r.status        = s.status;
        r.createdAt     = s.createdAt;
        r.updatedAt     = s.updatedAt;
        r.result        = s.result;
        return r;
    }
}
```

- [ ] **Step 5: Update FullFlowIT to use full paths**

In `src/test/java/id/redhat/razhari/functional/FullFlowIT.java`, replace every occurrence of `"/transactions"` with `"/api/v1/transactions"`:

```java
// Line ~31 — submit POST
.post("/api/v1/transactions")

// Line ~42 — poll status
.get("/api/v1/transactions/" + transactionId + "/status")

// Line ~50 — get full result
.get("/api/v1/transactions/" + transactionId)

// Line ~61 — 404 check
.get("/api/v1/transactions/does-not-exist")
```

- [ ] **Step 6: Run all unit tests**

```bash
./mvnw test -q
```

Expected output: `BUILD SUCCESS` — all tests pass including `TransactionRestRouteTest`, `InMemoryTransactionStoreTest`, `StanGeneratorTest`, `ISO8583CodecTest`, `JsonToIsoProcessorTest`, `IsoToJsonProcessorTest`.

If `POST_returns_202_with_transaction_id` fails with a 415 (Unsupported Media Type), add `.consumes("application/json")` is already present — check that `camel-quarkus-jackson` is in `pom.xml` (it should be from v1 implementation).

If any test gets a 404, verify `restConfiguration().contextPath("/api/v1")` is set and `./mvnw compile` succeeds cleanly first.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/id/redhat/razhari/route/RestApiRoute.java \
        src/test/java/id/redhat/razhari/rest/TransactionRestRouteTest.java \
        src/test/java/id/redhat/razhari/functional/FullFlowIT.java \
        pom.xml \
        src/main/resources/application.properties \
        src/test/resources/application.properties
git rm src/main/java/id/redhat/razhari/rest/TransactionResource.java \
       src/main/java/id/redhat/razhari/route/IsoMessageSender.java \
       src/test/java/id/redhat/razhari/rest/TransactionResourceTest.java
git commit -m "refactor: replace JAX-RS REST layer with Camel REST DSL (platform-http)"
```

---

## Task 3: Add SeedDataRoute (dev profile startup data)

**Files:**
- Create: `src/main/java/id/redhat/razhari/route/SeedDataRoute.java`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Create SeedDataRoute**

Create `src/main/java/id/redhat/razhari/route/SeedDataRoute.java`:

```java
package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionRequest;
import io.quarkus.arc.profile.IfBuildProfile;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Dev-only route that fires once on startup to pre-populate demo transactions.
 * Each request flows through direct:rest-submit — the same path as a real REST POST —
 * so the switch receives live ISO 8583 messages and responds with 0210 approvals.
 * Not active in test or prod profiles (@IfBuildProfile("dev")).
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class SeedDataRoute extends RouteBuilder {

    @ConfigProperty(name = "seed.delay-ms", defaultValue = "3000")
    long delayMs;

    @Override
    public void configure() {
        // delay gives Camel routes and the mock switch time to be ready before the first message
        from("timer:seed?delay=" + delayMs + "&repeatCount=1")
            .routeId("seed-data")
            .process(exchange -> exchange.getMessage().setBody(List.of(
                request("0200", "4111111111111111", 10000L, "840", "TERM0001", "COFFEESHOP001"),
                request("0200", "5500000000000004", 25099L, "840", "TERM0002", "RESTAURANT001"),
                request("0200", "4000000000000002",  4999L, "978", "TERM0003", "BOOKSHOP_EU01"),
                request("0200", "4111111111111111", 999999L, "840", "TERM0001", "JEWELER001    ")
            )))
            .split(body()).parallelProcessing(false)
                .to("direct:rest-submit");
    }

    private TransactionRequest request(String mti, String pan, long amount,
                                        String currency, String terminalId, String merchantId) {
        TransactionRequest r = new TransactionRequest();
        r.mti        = mti;
        r.pan        = pan;
        r.amount     = amount;
        r.currency   = currency;
        r.terminalId = terminalId;
        r.merchantId = merchantId;
        return r;
    }
}
```

- [ ] **Step 2: Add seed config to application.properties**

Append to `src/main/resources/application.properties`:

```properties
# Seed data — fires once at startup in dev mode (SeedDataRoute)
%dev.seed.delay-ms=3000
```

- [ ] **Step 3: Verify unit tests still pass (SeedDataRoute is inactive in test profile)**

```bash
./mvnw test -q
```

Expected: `BUILD SUCCESS`. `SeedDataRoute` is annotated `@IfBuildProfile("dev")` so it is not instantiated during `@QuarkusTest` (which runs in the `test` profile). No seed traffic during tests.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/id/redhat/razhari/route/SeedDataRoute.java \
        src/main/resources/application.properties
git commit -m "feat: add dev-profile startup seed route (Camel timer, fires once after 3s)"
```

---

## Task 4: Enable Camel Tracer

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add tracer config**

Append to `src/main/resources/application.properties`:

```properties
# Camel tracer — dev only
# BacklogTracer stores the last 500 exchanges in memory (queried by the follow-on UI branch)
%dev.camel.main.backlog-tracing=true
%dev.camel.main.backlog-tracing-standby=false
# Standard tracer logs every exchange step to console — off by default (noisy with seed traffic)
# Toggle to true in dev console without restart to debug a specific flow
%dev.camel.main.tracing=false
```

- [ ] **Step 2: Run unit tests to confirm no regression**

```bash
./mvnw test -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "feat: enable Camel BacklogTracer in dev profile"
```

---

## Task 5: Simplify demo.sh and remove seed JSON files

**Files:**
- Modify: `scripts/demo.sh`
- Delete: `scripts/seed/01-visa-auth.json`, `scripts/seed/02-mastercard-auth.json`, `scripts/seed/03-eur-auth.json`, `scripts/seed/04-high-value-auth.json`

- [ ] **Step 1: Delete seed JSON files**

```bash
rm scripts/seed/01-visa-auth.json \
   scripts/seed/02-mastercard-auth.json \
   scripts/seed/03-eur-auth.json \
   scripts/seed/04-high-value-auth.json
rmdir scripts/seed
```

- [ ] **Step 2: Rewrite demo.sh as a poll-only script**

Replace the entire contents of `scripts/demo.sh` with:

```bash
#!/usr/bin/env bash
# Demo: polls all transactions and shows their current state.
# Seed data is injected automatically at app startup (SeedDataRoute, dev profile).
#
# Prerequisites:
#   Terminal 1: ./scripts/mock-switch.sh    (mock payment switch on port 8583)
#   Terminal 2: ./mvnw quarkus:dev          (bridge app — seed fires 3s after start)
#   Terminal 3: ./scripts/demo.sh           (this script — poll results)
#
# Usage: ./scripts/demo.sh [BASE_URL]
#        BASE_URL defaults to http://localhost:8080/api/v1

set -euo pipefail

BASE_URL="${1:-http://localhost:8080/api/v1}"
POLL_MAX=40
POLL_SLEEP=1

format_json() {
    if command -v jq &>/dev/null; then jq .; else python3 -m json.tool; fi
}

extract() {
    local field="$1"
    if command -v jq &>/dev/null; then
        jq -r ".$field // empty"
    else
        python3 -c "import sys,json; v=json.load(sys.stdin).get(sys.argv[1],''); print('' if v is None else v)" "$field"
    fi
}

wait_for_app() {
    echo "Waiting for app at $BASE_URL ..."
    for _ in $(seq 1 30); do
        if curl -s --connect-timeout 1 --max-time 2 -o /dev/null \
               "$BASE_URL/transactions?type=inbound" 2>/dev/null; then
            echo "App is ready."
            return 0
        fi
        sleep 1; echo -n "."
    done
    echo ""; echo "ERROR: App not reachable after 30s."; exit 1
}

poll_all_to_completion() {
    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo "  Waiting for seed transactions to complete..."
    echo "════════════════════════════════════════════════════════════════"

    for _ in $(seq 1 $POLL_MAX); do
        sleep $POLL_SLEEP
        RESULT=$(curl -s "$BASE_URL/transactions")
        COUNT=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d))" 2>/dev/null || echo "0")
        PENDING=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(sum(1 for t in d if t['status']=='PENDING'))" 2>/dev/null || echo "?")

        echo -n "  transactions=$COUNT  pending=$PENDING  "
        if [ "$COUNT" -ge 4 ] && [ "$PENDING" = "0" ] 2>/dev/null; then
            echo "— all done!"
            break
        fi
        echo ""
    done
}

show_results() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  All Transactions"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    curl -s "$BASE_URL/transactions" | format_json | sed 's/^/  /'

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Inbound Events (switch-initiated)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    curl -s "$BASE_URL/transactions?type=inbound" | format_json | sed 's/^/  /'
    echo ""
}

wait_for_app
poll_all_to_completion
show_results
echo "Demo complete."
```

- [ ] **Step 3: Verify script is executable**

```bash
chmod +x scripts/demo.sh
```

- [ ] **Step 4: Commit**

```bash
git rm scripts/seed/01-visa-auth.json \
       scripts/seed/02-mastercard-auth.json \
       scripts/seed/03-eur-auth.json \
       scripts/seed/04-high-value-auth.json
git add scripts/demo.sh
git commit -m "refactor: replace seed JSON files with Camel startup route; demo.sh is now poll-only"
```

---

## Task 6: Add code comments to routes and processors

**Files:**
- Modify: `src/main/java/id/redhat/razhari/route/ISO8583SendRoute.java`
- Modify: `src/main/java/id/redhat/razhari/route/ISO8583ServerRoute.java`
- Modify: `src/main/java/id/redhat/razhari/route/CleanupRoute.java`
- Modify: `src/main/java/id/redhat/razhari/processor/JsonToIsoProcessor.java`
- Modify: `src/main/java/id/redhat/razhari/processor/IsoToJsonProcessor.java`
- Modify: `src/main/java/id/redhat/razhari/codec/ISO8583Decoder.java`
- Modify: `src/main/java/id/redhat/razhari/codec/ISO8583Encoder.java`

- [ ] **Step 1: Update ISO8583SendRoute.java**

Replace entire file content:

```java
package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import org.apache.camel.builder.RouteBuilder;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

/**
 * Outbound route: receives a TransactionState from direct:send-iso8583,
 * converts it to an ISO 8583 0200 message via JsonToIsoProcessor,
 * sends it synchronously to the payment switch over TCP (Netty),
 * and processes the 0210 response via IsoToJsonProcessor to mark the
 * transaction COMPLETED in the TransactionStore.
 *
 * On any exception the transaction is marked FAILED. The exception is
 * handled so it does not propagate back to the REST caller (the submit
 * is already a fire-and-forget asyncSendBody call).
 */
@ApplicationScoped
public class ISO8583SendRoute extends RouteBuilder {

    @Override
    public void configure() {
        onException(Exception.class)
            .handled(true)
            .process(exchange -> {
                // Retrieve the state stashed before the processor replaced the body.
                // Marking FAILED here ensures the poll endpoint reflects the error.
                TransactionState state = exchange.getProperty(
                    "originalState", TransactionState.class);
                if (state != null) {
                    state.status = TransactionStatus.FAILED;
                    state.updatedAt = Instant.now();
                }
            })
            .log("ISO8583 send failed: ${exception.message}");

        from("direct:send-iso8583")
            .process(exchange -> {
                // Stash the original state before JsonToIsoProcessor replaces
                // the exchange body with an IsoMessage. Needed by the onException handler.
                exchange.setProperty("originalState",
                    exchange.getIn().getBody(TransactionState.class));
            })
            .process("jsonToIsoProcessor")   // TransactionState → IsoMessage (0200)
            .to("netty:tcp://{{camel.iso8583.switch.host}}:{{camel.iso8583.switch.port}}"
                + "?clientInitializerFactory=#iso8583ClientInitializer"
                + "&sync=true"          // request-reply: blocks until 0210 response arrives
                + "&reuseChannel=true"
                + "&connectTimeout={{camel.iso8583.netty.connect-timeout}}")
            .process("isoToJsonProcessor");  // IsoMessage (0210) → store update → COMPLETED
    }
}
```

- [ ] **Step 2: Update ISO8583ServerRoute.java**

Replace entire file content:

```java
package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionStatus;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;

/**
 * Inbound TCP server route: listens for unsolicited messages from the payment
 * switch (reversals, advices, network management).
 *
 * For each inbound ISO 8583 frame:
 *   1. IsoToJsonProcessor stores the message as a RECEIVED event in the TransactionStore.
 *   2. An acknowledgment (MTI + 0x0010, e.g. 0200→0210, 0400→0410) is built with
 *      the echoed STAN (field 11) and response code 00, then sent back to the switch.
 *
 * The ACK MTI convention (+0x0010) follows the ISO 8583 response pairing standard.
 */
@ApplicationScoped
public class ISO8583ServerRoute extends RouteBuilder {

    @Inject
    MessageFactory<IsoMessage> messageFactory;

    @Override
    public void configure() {
        onException(Exception.class)
            .handled(true)
            .log("ISO8583 server error: ${exception.message}");

        from("netty:tcp://0.0.0.0:{{camel.iso8583.server.port}}"
             + "?serverInitializerFactory=#iso8583ServerInitializer"
             + "&sync=true"       // sync=true required: Netty waits for the ACK body to send back
             + "&keepAlive=true")
            .process(exchange -> {
                // Stash the raw IsoMessage before isoToJsonProcessor replaces the body.
                // It is needed below to build the acknowledgment.
                exchange.setProperty("incomingMsg",
                    exchange.getIn().getBody(IsoMessage.class));
            })
            .process("isoToJsonProcessor")  // stores the inbound message as RECEIVED
            .process(exchange -> {
                IsoMessage incoming = exchange.getProperty("incomingMsg", IsoMessage.class);
                // ISO 8583 response MTI is request MTI + 0x0010 (e.g. 0200→0210, 0400→0410)
                int responseMti = incoming.getType() + 0x0010;
                IsoMessage ack = messageFactory.newMessage(responseMti);
                // Echo field 11 (STAN) so the switch can correlate the acknowledgment
                if (incoming.hasField(11)) {
                    ack.setValue(11, incoming.getField(11).toString(),
                        IsoType.NUMERIC, 6);
                }
                ack.setValue(39, "00", IsoType.ALPHA, 2); // response code 00 = approved
                exchange.getIn().setBody(ack);
            });
    }
}
```

- [ ] **Step 3: Update CleanupRoute.java**

Replace entire file content:

```java
package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.TransactionStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

/**
 * Periodic cleanup route that moves PENDING transactions to TIMEOUT
 * once they exceed the configured TTL.
 *
 * Fires every {@code camel.iso8583.transaction.cleanup-interval-ms} milliseconds.
 * A transaction is considered timed out when its {@code createdAt} timestamp is
 * older than {@code camel.iso8583.transaction.timeout-ms} from now.
 *
 * This is the only mechanism that moves transactions out of PENDING when the
 * switch never responds (network partition, switch down, etc.).
 */
@ApplicationScoped
public class CleanupRoute extends RouteBuilder {

    @Inject
    TransactionStore store;

    @ConfigProperty(name = "camel.iso8583.transaction.timeout-ms", defaultValue = "30000")
    long timeoutMs;

    @Override
    public void configure() {
        from("timer:cleanup?period={{camel.iso8583.transaction.cleanup-interval-ms}}")
            .process(exchange -> {
                Instant threshold = Instant.now().minusMillis(timeoutMs);
                store.findPendingOlderThan(threshold).forEach(state -> {
                    state.status = TransactionStatus.TIMEOUT;
                    state.updatedAt = Instant.now();
                    store.update(state);
                });
            })
            .log("Cleanup: moved expired PENDING transactions to TIMEOUT");
    }
}
```

- [ ] **Step 4: Update JsonToIsoProcessor.java**

Replace entire file content:

```java
package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionRequest;
import id.redhat.razhari.model.TransactionState;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Camel processor: converts a {@link TransactionState} (carrying a JSON request)
 * into a j8583 {@link IsoMessage} ready to be sent to the payment switch.
 *
 * ISO 8583 field mapping:
 *   Field  2 — PAN (Primary Account Number), LLVAR: variable-length, 2-digit length prefix
 *   Field  4 — Amount, NUMERIC-12: minor currency units (cents), zero-padded
 *   Field 11 — STAN (System Trace Audit Number), NUMERIC-6: correlation key echoed in response
 *   Field 41 — Terminal ID, ALPHA-8: space-padded on the right
 *   Field 42 — Merchant ID, ALPHA-15: space-padded on the right
 *   Field 49 — Currency code, NUMERIC-3: ISO 4217 numeric (840=USD, 978=EUR)
 *
 * The MTI string (e.g. "0200") is parsed as a hex integer: Integer.parseInt("0200", 16) = 512.
 * j8583's newMessage() accepts this integer form.
 */
@ApplicationScoped
@Named("jsonToIsoProcessor")
public class JsonToIsoProcessor implements Processor {

    private final MessageFactory<IsoMessage> factory;

    @Inject
    public JsonToIsoProcessor(MessageFactory<IsoMessage> factory) {
        this.factory = factory;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        TransactionState state = exchange.getIn().getBody(TransactionState.class);
        TransactionRequest req = state.request;

        // MTI "0200" parsed as hex: Integer.parseInt("0200", 16) = 0x0200 = 512
        int mti = Integer.parseInt(req.mti, 16);
        IsoMessage msg = factory.newMessage(mti);

        msg.setValue(2,  req.pan,                            IsoType.LLVAR,   0);
        msg.setValue(4,  String.format("%012d", req.amount), IsoType.NUMERIC, 12);
        msg.setValue(11, state.stan,                         IsoType.NUMERIC, 6);
        msg.setValue(41, req.terminalId,                     IsoType.ALPHA,   8);
        msg.setValue(42, req.merchantId,                     IsoType.ALPHA,   15);
        msg.setValue(49, req.currency,                       IsoType.NUMERIC, 3);

        exchange.getIn().setBody(msg);
    }
}
```

- [ ] **Step 5: Update IsoToJsonProcessor.java**

Replace entire file content:

```java
package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.TransactionStore;
import com.solab.iso8583.IsoMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Camel processor: converts an inbound j8583 {@link IsoMessage} into a
 * {@link TransactionState} store update or a new RECEIVED event.
 *
 * Two cases:
 *   1. STAN (field 11) matches an existing PENDING state → mark COMPLETED, store result fields.
 *   2. No matching STAN → switch-initiated message (reversal, advice); store as RECEIVED.
 *
 * ISO 8583 response fields extracted:
 *   Field 11 — STAN: used as the correlation key to find the original request
 *   Field 37 — Retrieval Reference Number (RRN): 12-char switch-assigned reference
 *   Field 38 — Authorization Code: 6-char approval code from the issuer
 *   Field 39 — Response Code: 2-char result ("00" = approved, "05" = declined, etc.)
 *
 * ALPHA fields from j8583 are space-padded to their declared length; .trim() removes padding.
 */
@ApplicationScoped
@Named("isoToJsonProcessor")
public class IsoToJsonProcessor implements Processor {

    private final TransactionStore store;

    @Inject
    public IsoToJsonProcessor(TransactionStore store) {
        this.store = store;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        IsoMessage msg = exchange.getIn().getBody(IsoMessage.class);
        // Field 11 (STAN) is the correlation key between request and response
        String stan = msg.hasField(11) ? msg.getField(11).toString().trim() : null;

        Optional<TransactionState> existing = stan != null ? store.findByStan(stan) : Optional.empty();

        if (existing.isPresent()) {
            // Outbound response: switch replied to our 0200 with a 0210
            TransactionState state = existing.get();
            state.status = TransactionStatus.COMPLETED;
            state.updatedAt = Instant.now();
            state.result = extractFields(msg);
            store.update(state);
        } else {
            // Unsolicited inbound: reversal, advice, or network management from the switch
            TransactionState state = new TransactionState();
            state.id = UUID.randomUUID().toString();
            state.stan = stan;
            state.status = TransactionStatus.RECEIVED;
            state.createdAt = Instant.now();
            state.updatedAt = Instant.now();
            state.result = extractFields(msg);
            store.save(state);
        }
    }

    private Map<String, String> extractFields(IsoMessage msg) {
        Map<String, String> result = new HashMap<>();
        result.put("mti", String.format("%04x", msg.getType()).toUpperCase());
        // .trim() removes space padding that j8583 adds to ALPHA-type fields
        if (msg.hasField(11)) result.put("stan",         msg.getField(11).toString().trim());
        if (msg.hasField(37)) result.put("retrievalRef", msg.getField(37).toString().trim());
        if (msg.hasField(38)) result.put("authCode",     msg.getField(38).toString().trim());
        if (msg.hasField(39)) result.put("responseCode", msg.getField(39).toString().trim());
        return result;
    }
}
```

- [ ] **Step 6: Update ISO8583Decoder.java**

Replace entire file content:

```java
package id.redhat.razhari.codec;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.MessageFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Netty inbound handler: reads a length-prefixed ISO 8583 frame from the TCP stream
 * and delegates field parsing to j8583's MessageFactory.
 *
 * Frame format (agreed with payment switch):
 *   [2 bytes unsigned big-endian length][N bytes ISO 8583 payload]
 *
 * ByteToMessageDecoder is NOT @Sharable — a new instance must be created per channel.
 * This is enforced by ISO8583ServerInitializer and ISO8583ClientInitializer which
 * call new ISO8583Decoder(factory) inside ChannelInitializer.initChannel().
 *
 * isoOffset=0 is passed to parseMessage() because the j8583.xml config omits the
 * {@code <header>} element — there is no additional header beyond the Netty length prefix.
 */
public class ISO8583Decoder extends ByteToMessageDecoder {

    private final MessageFactory<IsoMessage> factory;

    public ISO8583Decoder(MessageFactory<IsoMessage> factory) {
        this.factory = factory;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 2) return;            // wait for length header
        int length = in.getUnsignedShort(in.readerIndex());
        if (in.readableBytes() < length + 2) return;  // wait for full frame
        in.skipBytes(2);
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        out.add(factory.parseMessage(bytes, 0));       // isoOffset=0: no extra header bytes
    }
}
```

- [ ] **Step 7: Update ISO8583Encoder.java**

Replace entire file content:

```java
package id.redhat.razhari.codec;

import com.solab.iso8583.IsoMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Netty outbound handler: serialises an IsoMessage to a length-prefixed TCP frame.
 *
 * Frame format:
 *   [2 bytes unsigned big-endian length][N bytes ISO 8583 payload from IsoMessage.writeData()]
 *
 * The 2-byte length prefix is the contract shared with ISO8583Decoder and the payment switch.
 * It allows the decoder to know exactly how many bytes to wait for before attempting to parse.
 */
public class ISO8583Encoder extends MessageToByteEncoder<IsoMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, IsoMessage msg, ByteBuf out) throws Exception {
        byte[] payload = msg.writeData();
        out.writeShort(payload.length);  // 2-byte length prefix
        out.writeBytes(payload);
    }
}
```

- [ ] **Step 8: Run all tests to confirm nothing broken**

```bash
./mvnw test -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/id/redhat/razhari/route/ISO8583SendRoute.java \
        src/main/java/id/redhat/razhari/route/ISO8583ServerRoute.java \
        src/main/java/id/redhat/razhari/route/CleanupRoute.java \
        src/main/java/id/redhat/razhari/processor/JsonToIsoProcessor.java \
        src/main/java/id/redhat/razhari/processor/IsoToJsonProcessor.java \
        src/main/java/id/redhat/razhari/codec/ISO8583Decoder.java \
        src/main/java/id/redhat/razhari/codec/ISO8583Encoder.java
git commit -m "docs: add Javadoc and inline comments to routes, processors, and codec"
```

---

## Task 7: Create refactoring documentation

**Files:**
- Create: `docs/refactoring-v2.md`

- [ ] **Step 1: Create docs/refactoring-v2.md**

Create `docs/refactoring-v2.md`:

````markdown
# Refactoring v2 — Full Camel REST DSL Migration

**Branch:** `refactor/camel-rest-dsl`
**Date:** 2026-05-13

## What Changed

### 1. REST Layer: JAX-RS → Camel REST DSL

`TransactionResource.java` (JAX-RS) and `IsoMessageSender.java` (its ProducerTemplate wrapper)
are replaced by a single `RestApiRoute.java`.

`RestApiRoute` uses `restConfiguration().component("platform-http").contextPath("/api/v1")`
and four `rest("/transactions")` endpoint definitions. Each routes to a `direct:` sub-route:

| Endpoint                        | Sub-route              |
|---------------------------------|------------------------|
| POST   /api/v1/transactions     | direct:rest-submit     |
| GET    /api/v1/transactions/{id}| direct:rest-get-by-id  |
| GET    /api/v1/transactions/{id}/status | direct:rest-get-status |
| GET    /api/v1/transactions     | direct:rest-list       |

**Why platform-http?** It integrates with Quarkus's built-in Vert.x HTTP server — no second
HTTP server is started. `camel-quarkus-jackson` handles JSON binding automatically.

**Why IsoMessageSender was deleted:** It existed only as a CDI scope workaround for testing.
With the REST logic now inside a Camel route, `ProducerTemplate` is injected directly into
`RestApiRoute` and the async dispatch is tested implicitly through the full HTTP flow.

### 2. Startup Seed Data: Shell Scripts → Camel Timer Route

`SeedDataRoute.java` (annotated `@IfBuildProfile("dev")`) fires once 3 seconds after Camel
starts, using `timer:seed?delay={{seed.delay-ms}}&repeatCount=1`. It splits a list of four
`TransactionRequest` objects and routes each through `direct:rest-submit` — identical to a
real REST POST. The switch receives live ISO 8583 messages and responds with 0210 approvals.

The `scripts/seed/` directory and its JSON files are deleted. `scripts/demo.sh` is now
poll-only: it waits for the four seed transactions to reach a terminal state and prints results.

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

Javadoc added to: `RestApiRoute`, `ISO8583SendRoute`, `ISO8583ServerRoute`, `CleanupRoute`,
`JsonToIsoProcessor`, `IsoToJsonProcessor`, `ISO8583Decoder`, `ISO8583Encoder`.

Key inline comments document:
- ISO 8583 field number semantics (PAN, STAN, amount format, currency code, auth code)
- The `originalState` exchange property pattern (stash before processor replaces body)
- The ACK MTI derivation (`incoming.getType() + 0x0010`)
- `ByteToMessageDecoder` non-`@Sharable` constraint and why new instances per channel
- The 2-byte length prefix framing contract between encoder and decoder

## Dependency Changes

| Action | Artifact |
|--------|---------|
| Removed | `io.quarkus:quarkus-rest` |
| Removed | `io.quarkus:quarkus-rest-jackson` |
| Added   | `org.apache.camel.quarkus:camel-quarkus-platform-http` |
| Added   | `org.apache.camel.quarkus:camel-quarkus-rest` |

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
````

- [ ] **Step 2: Commit**

```bash
git add docs/refactoring-v2.md
git commit -m "docs: add refactoring-v2.md covering all changes in this branch"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|---|---|
| Replace JAX-RS with Camel REST DSL (platform-http) | Task 2 |
| Delete `TransactionResource.java` | Task 1 |
| Delete `IsoMessageSender.java` | Task 1 |
| Drop `quarkus-rest`, add `camel-quarkus-platform-http` + `camel-quarkus-rest` | Task 1 |
| Rewrite `TransactionResourceTest` → `TransactionRestRouteTest` | Task 2 |
| Update `FullFlowIT` paths | Task 2 |
| `SeedDataRoute` with `@IfBuildProfile("dev")` and timer | Task 3 |
| `%dev.seed.delay-ms=3000` config | Task 3 |
| Remove seed JSON files and simplify demo.sh | Task 5 |
| BacklogTracer enabled in dev | Task 4 |
| Standard tracer off by default in dev | Task 4 |
| Comments on routes and processors | Task 6 |
| `docs/refactoring-v2.md` | Task 7 |
| Branch `refactor/camel-rest-dsl` | Already created |
