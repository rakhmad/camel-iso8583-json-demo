# Design Spec: ISO 8583 Bridge Web UI

**Date:** 2026-05-14
**Branch:** `feature/web-ui` (off `refactor/camel-rest-dsl`)
**Status:** Approved

---

## 1. Goal

Add a live dashboard at `/` that shows all transactions and their full Camel pipeline trace — from the incoming JSON request through ISO 8583 encoding, switch communication, and back to the JSON result. No new dependencies. No build step.

---

## 2. Scope

In scope:
- Static `index.html` served by `platform-http` from `META-INF/resources/`
- New `GET /api/v1/trace/{transactionId}` endpoint in `RestApiRoute`
- PAN masking before any data leaves the server

Out of scope:
- Authentication / access control
- Persistent trace storage (BacklogTracer is in-memory, last 500 exchanges)
- BacklogTracer standby mode toggle (already configurable via Camel dev console)

---

## 3. Architecture

```
Browser
  │  GET /                          → index.html (static, platform-http)
  │  GET /api/v1/transactions       → RestApiRoute (existing, polled every 2s)
  │  GET /api/v1/trace/{id}         → RestApiRoute (new, on row click)
  │
Quarkus / Camel
  │  RestApiRoute.direct:rest-trace  → inject CamelContext → BacklogTracer.dumpAllTracedMessages()
  │                                 → filter by transactionId → mask PAN → return steps[]
```

One new model class (`TraceStep`). The trace endpoint is a new `from("direct:rest-trace")` sub-route in the existing `RestApiRoute.java`.

---

## 4. Backend: Trace Endpoint

### 4.1 Route Definition

Added to `RestApiRoute.configure()`:

```java
rest("/trace")
    .get("/{transactionId}")
        .produces("application/json")
        .to("direct:rest-trace");

from("direct:rest-trace")
    .routeId("rest-trace")
    .process(exchange -> {
        String txId = exchange.getIn().getHeader("transactionId", String.class);
        BacklogTracer tracer = (BacklogTracer) camelContext.getExtension(BacklogTracer.class);
        List<TraceStep> steps = tracer.dumpAllTracedMessages().stream()
            .filter(m -> m.getMessage() != null && m.getMessage().contains(txId))
            .sorted(Comparator.comparing(BacklogTracerEventMessage::getTimestamp))
            .map(m -> toTraceStep(m))   // masks PAN, truncates body
            .collect(Collectors.toList());
        exchange.getMessage().setBody(steps);
    })
    .marshal(json());
```

### 4.2 CamelContext Injection

`RestApiRoute` already has `@Inject` fields. Add:

```java
@Inject
CamelContext camelContext;
```

### 4.3 TraceStep Model

New class `src/main/java/id/redhat/razhari/model/TraceStep.java`:

```java
public class TraceStep {
    public int    step;
    public String routeId;
    public String node;
    public String timestamp;   // ISO-8601
    public String bodyType;    // simple class name
    public String bodySummary; // ≤200 chars, PAN masked
}
```

### 4.4 PAN Masking

Applied before `bodySummary` is set. Regex replaces 13–19 digit sequences, keeping first 6 and last 4:

```java
private static final Pattern PAN_PATTERN =
    Pattern.compile("(\\d{6})\\d{3,9}(\\d{4})");

String masked = PAN_PATTERN.matcher(raw).replaceAll("$1******$2");
```

### 4.5 Body Summarisation

```java
String raw = msg.getMessage() != null ? msg.getMessage() : "(empty)";
String masked = PAN_PATTERN.matcher(raw).replaceAll("$1******$2");
step.bodySummary = masked.length() > 200 ? masked.substring(0, 197) + "..." : masked;
// bodyType: derive from the serialized body string (e.g. check for "IsoMessage", "TransactionState")
// or use whatever type metadata BacklogTracerEventMessage exposes in Camel 4.x
step.bodyType = deriveBodyType(msg);
```

---

## 5. Frontend: index.html

Single file at `src/main/resources/META-INF/resources/index.html`.
Vanilla HTML + CSS + JS. No external CDN dependencies (no network required to render).

### 5.1 Layout

```
┌──────────────────────────────────────────────────────┐
│  ISO 8583 Bridge — Live Transactions        [● LIVE] │
├────────────┬──────────┬───────────┬──────────────────┤
│ ID         │ Status   │ Amount    │ Updated          │
├────────────┼──────────┼───────────┼──────────────────┤
│ 3f2a…      │ COMPLETED│ $100.00   │ 10:00:03         │
│ ▼ expanded row                                       │
│   Step 1 · rest-submit · 10:00:00.123                │
│     TransactionRequest { mti:0200, pan:411111…}      │
│   Step 2 · jsonToIsoProcessor · 10:00:00.145         │
│     IsoMessage MTI=0200 F2=411111… F4=000000010000   │
│   Step 3 · netty · 10:00:00.201                      │
│     IsoMessage MTI=0210 F39=00 F38=AUTH01            │
│   Step 4 · isoToJsonProcessor · 10:00:00.215         │
│     COMPLETED responseCode=00 authCode=AUTH01        │
├────────────┼──────────┼───────────┼──────────────────┤
│ 8c1b…      │ PENDING  │ $250.99   │ 10:00:01         │
│ a9d4…      │ COMPLETED│ €49.99    │ 10:00:02         │
└────────────┴──────────┴───────────┴──────────────────┘
```

### 5.2 Behaviour

- **Poll:** `GET /api/v1/transactions` every 2 seconds. Updates rows in place — does not collapse expanded rows.
- **Expand:** Click a row → `GET /api/v1/trace/{id}` → render trace steps below row. Click again to collapse.
- **Status colours:** PENDING=amber, COMPLETED=green, FAILED=red, TIMEOUT/RECEIVED=grey.
- **Amount formatting:** Field `request.amount` (minor units) ÷ 100 with currency symbol. Currency field `request.currency`: 840=USD($), 978=EUR(€), default=display raw.
- **LIVE indicator:** Pulsing green dot. Turns red if the last poll fails (connection lost).

### 5.3 No External Dependencies

All CSS and JS inline in the HTML file. No CDN, no npm. Works offline after first load.

---

## 6. File Map

### Created
```
src/main/resources/META-INF/resources/index.html
src/main/java/id/redhat/razhari/model/TraceStep.java
```

### Modified
```
src/main/java/id/redhat/razhari/route/RestApiRoute.java   — new trace endpoint + CamelContext inject
```

---

## 7. Testing Strategy

- **Unit test** (`TraceEndpointTest.java`, `@QuarkusTest`): mock `CamelContext` → `BacklogTracer`; stub one trace entry; verify `GET /api/v1/trace/test-id` returns 200 with correct step shape and PAN masked.
- **Manual smoke test:** Start mock switch + `quarkus:dev`. Open `http://localhost:8080`. Confirm transactions appear within 5s (seed route fires at 3s). Click a row, confirm trace steps appear showing ISO 8583 field values.

---

## 8. Configuration

No new properties. BacklogTracer is already enabled by `%dev.camel.main.backlog-tracing=true` from the previous refactoring branch.

---

## 9. Correlation Strategy

The BacklogTracer captures every exchange step across all routes. A single transaction generates two Camel exchanges:

1. **E1** (`direct:rest-submit`): body is `TransactionRequest` JSON then `TransactionState` — contains the UUID
2. **E2** (`direct:send-iso8583`): body is `TransactionState` — also contains the UUID

Filtering `dumpAllTracedMessages()` for entries whose serialised message body contains the UUID captures both exchanges. Steps are sorted by `timestamp` to give chronological order across both exchanges.

Limitation: if the UUID appears in an unrelated exchange body by coincidence, it would be included. In practice this cannot happen since UUIDs are generated fresh per transaction.
