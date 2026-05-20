# Camel YAML DSL Refactoring Design

## Goal

Replace all Java `RouteBuilder` classes (except the dev-only `SeedDataRoute`) with Camel YAML DSL files consumable by Kaoto, while ensuring every Camel component used in routes is Red Hat supported.

## Architecture

The refactoring has two interdependent parts:

**Part 1 — Extract inline processors.** Every `exchange -> { ... }` lambda currently embedded in a Java `RouteBuilder` becomes its own `@Named` `Processor` class in the `processor/` package. YAML routes reference them by name via `process: ref: <beanName>`. All Java business logic stays in Java; YAML files contain only routing structure.

**Part 2 — Write YAML routes.** Four YAML files under `src/main/resources/camel/` replace the four Java `RouteBuilder` classes. Camel Quarkus auto-discovers all `*.yaml` files in that directory with no additional configuration.

**BacklogTracer activation** moves from `RestApiRoute.configure()` into a new `BacklogTracerActivator` CDI bean (`@IfBuildProfile("dev")`) that fires on Quarkus `StartupEvent`. This is cleaner than embedding infrastructure setup inside a route definition.

## New Processor Beans

All new processors live in `src/main/java/id/redhat/razhari/processor/` and implement `org.apache.camel.Processor`. Each is `@ApplicationScoped` + `@Named` so YAML routes can reference it.

| Class | Extracted from | Responsibility |
|---|---|---|
| `StashStateProcessor` | `ISO8583SendRoute` | Copies `TransactionState` to `originalState` exchange property before `jsonToIsoProcessor` replaces the body |
| `SendFailedProcessor` | `ISO8583SendRoute` | Marks the stashed `TransactionState` as `FAILED` with the caught exception message |
| `StashIncomingProcessor` | `ISO8583ServerRoute` | Copies the raw `IsoMessage` to `incomingMsg` exchange property before `isoToJsonProcessor` replaces the body |
| `BuildAckProcessor` | `ISO8583ServerRoute` | Builds a 0210/0410 ACK (`incomingMti + 0x0010`), echoes STAN (field 11), sets response code 00, sets as exchange body |
| `CleanupProcessor` | `CleanupRoute` | Finds `PENDING` transactions older than `camel.iso8583.transaction.timeout-ms` and moves them to `TIMEOUT` |
| `RestSubmitProcessor` | `RestApiRoute` | Creates `TransactionState` (UUID + STAN + PENDING), saves to `TransactionStore`, fires `isoDispatcher.dispatch()`, returns `{transactionId}` with HTTP 202 |
| `RestGetByIdProcessor` | `RestApiRoute` | Looks up `TransactionState` by UUID; sets body to `TransactionResponse` or HTTP 404 |
| `RestGetStatusProcessor` | `RestApiRoute` | Returns `{status, createdAt, updatedAt}` for a UUID or HTTP 404 |
| `RestListProcessor` | `RestApiRoute` | Returns all transactions or filters by `?type=inbound` (status `RECEIVED`) |
| `RestTraceProcessor` | `RestApiRoute` | Queries `BacklogTracer.dumpAllTracedMessages()` filtered by `transactionId` header, returns `List<TraceStep>` |

`RestGetByIdProcessor`, `RestGetStatusProcessor`, and `RestListProcessor` use the existing `toResponse(TransactionState)` helper logic — since helper methods can't be shared across standalone classes, each duplicates the two-line mapping inline (no abstraction warranted for two lines).

## YAML Route Files

### `src/main/resources/camel/iso8583-send-route.yaml`

```yaml
- onException:
    exception:
      - java.lang.Exception
    handled:
      constant: "true"
    steps:
      - process:
          ref: sendFailedProcessor
      - log:
          message: "ISO8583 send failed: ${exception.message}"

- route:
    id: send-iso8583
    from:
      uri: "direct:send-iso8583"
    steps:
      - process:
          ref: stashStateProcessor
      - process:
          ref: jsonToIsoProcessor
      - to:
          uri: "netty:tcp://{{camel.iso8583.switch.host}}:{{camel.iso8583.switch.port}}?clientInitializerFactory=#iso8583ClientInitializer&sync=true&reuseChannel=true&connectTimeout={{camel.iso8583.netty.connect-timeout}}"
      - process:
          ref: isoToJsonProcessor
```

### `src/main/resources/camel/iso8583-server-route.yaml`

```yaml
- onException:
    exception:
      - java.lang.Exception
    handled:
      constant: "true"
    steps:
      - log:
          message: "ISO8583 server error: ${exception.message}"

- route:
    id: iso8583-server
    from:
      uri: "netty:tcp://0.0.0.0:{{camel.iso8583.server.port}}?serverInitializerFactory=#iso8583ServerInitializer&sync=true&keepAlive=true"
    steps:
      - process:
          ref: stashIncomingProcessor
      - process:
          ref: isoToJsonProcessor
      - process:
          ref: buildAckProcessor
```

### `src/main/resources/camel/cleanup-route.yaml`

```yaml
- route:
    id: cleanup
    from:
      uri: "timer:cleanup?period={{camel.iso8583.transaction.cleanup-interval-ms}}"
    steps:
      - process:
          ref: cleanupProcessor
      - log:
          message: "Cleanup: moved expired PENDING transactions to TIMEOUT"
```

### `src/main/resources/camel/rest-routes.yaml`

```yaml
- restConfiguration:
    component: platform-http
    contextPath: /api/v1
    bindingMode: "off"

- rest:
    path: /transactions
    post:
      - consumes: application/json
        produces: application/json
        to: "direct:rest-submit"
    get:
      - path: /{id}
        produces: application/json
        to: "direct:rest-get-by-id"
      - path: /{id}/status
        produces: application/json
        to: "direct:rest-get-status"
      - path: ""
        produces: application/json
        to: "direct:rest-list"

- rest:
    path: /trace
    get:
      - path: /{transactionId}
        produces: application/json
        to: "direct:rest-trace"

- route:
    id: rest-submit
    from:
      uri: "direct:rest-submit"
    steps:
      - unmarshal:
          json:
            unmarshalType: id.redhat.razhari.model.TransactionRequest
      - process:
          ref: restSubmitProcessor
      - marshal:
          json: {}

- route:
    id: rest-get-by-id
    from:
      uri: "direct:rest-get-by-id"
    steps:
      - process:
          ref: restGetByIdProcessor
      - choice:
          when:
            - simple: "${exchangeProperty.skipMarshal} != true"
              steps:
                - marshal:
                    json: {}

- route:
    id: rest-get-status
    from:
      uri: "direct:rest-get-status"
    steps:
      - process:
          ref: restGetStatusProcessor
      - choice:
          when:
            - simple: "${exchangeProperty.skipMarshal} != true"
              steps:
                - marshal:
                    json: {}

- route:
    id: rest-list
    from:
      uri: "direct:rest-list"
    steps:
      - process:
          ref: restListProcessor
      - marshal:
          json: {}

- route:
    id: rest-trace
    from:
      uri: "direct:rest-trace"
    steps:
      - process:
          ref: restTraceProcessor
      - marshal:
          json: {}
```

## New Config Bean

### `src/main/java/id/redhat/razhari/config/BacklogTracerActivator.java`

`@ApplicationScoped` + `@IfBuildProfile("dev")`. Observes `StartupEvent`. Calls `setEnabled(true)` and `setRemoveOnDump(false)` on the `BacklogTracer` context plugin if present. Fires before the 3-second seed timer, so all seed-data traces are captured.

## Dependency Changes (`pom.xml`)

| Action | Artifact | Reason |
|---|---|---|
| **Add** | `camel-quarkus-yaml-dsl` | Required for YAML route loading; Red Hat supported |
| **Note** | `camel-quarkus-iso8583` | Community extension, NOT in the Red Hat supported list. Used only as a library carrier for `j8583` — no `iso8583:` URI appears in any route. Stays in the POM; replacing it requires sourcing `j8583` directly (outside scope). |

All other Camel Quarkus extensions currently in the POM (`netty`, `direct`, `timer`, `jackson`, `platform-http`, `rest`) are Red Hat supported.

## Files Changed

| Action | Path |
|---|---|
| **Add** | `src/main/resources/camel/iso8583-send-route.yaml` |
| **Add** | `src/main/resources/camel/iso8583-server-route.yaml` |
| **Add** | `src/main/resources/camel/cleanup-route.yaml` |
| **Add** | `src/main/resources/camel/rest-routes.yaml` |
| **Add** | `src/main/java/id/redhat/razhari/processor/StashStateProcessor.java` |
| **Add** | `src/main/java/id/redhat/razhari/processor/SendFailedProcessor.java` |
| **Add** | `src/main/java/id/redhat/razhari/processor/StashIncomingProcessor.java` |
| **Add** | `src/main/java/id/redhat/razhari/processor/BuildAckProcessor.java` |
| **Add** | `src/main/java/id/redhat/razhari/processor/CleanupProcessor.java` |
| **Add** | `src/main/java/id/redhat/razhari/processor/RestSubmitProcessor.java` |
| **Add** | `src/main/java/id/redhat/razhari/processor/RestGetByIdProcessor.java` |
| **Add** | `src/main/java/id/redhat/razhari/processor/RestGetStatusProcessor.java` |
| **Add** | `src/main/java/id/redhat/razhari/processor/RestListProcessor.java` |
| **Add** | `src/main/java/id/redhat/razhari/processor/RestTraceProcessor.java` |
| **Add** | `src/main/java/id/redhat/razhari/config/BacklogTracerActivator.java` |
| **Delete** | `src/main/java/id/redhat/razhari/route/RestApiRoute.java` |
| **Delete** | `src/main/java/id/redhat/razhari/route/ISO8583SendRoute.java` |
| **Delete** | `src/main/java/id/redhat/razhari/route/ISO8583ServerRoute.java` |
| **Delete** | `src/main/java/id/redhat/razhari/route/CleanupRoute.java` |
| **Keep** | `src/main/java/id/redhat/razhari/route/SeedDataRoute.java` |
| **Keep** | All other Java files unchanged |

## Testing Strategy

No structural test changes are required. The existing tests exercise routes by URI (`direct:rest-submit`, etc.) — those URIs are identical in the YAML files. The YAML DSL test exclusion pattern in `src/test/resources/application.properties` already excludes `SeedDataRoute`; no new exclusions are needed since the other routes are no longer Java RouteBuilders.

The 10 new processor classes are unit-testable in isolation, but new unit tests are out of scope for this refactoring — existing end-to-end coverage (`FullFlowTest`) is sufficient.
