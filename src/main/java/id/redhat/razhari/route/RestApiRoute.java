package id.redhat.razhari.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.redhat.razhari.model.*;
import id.redhat.razhari.store.TransactionStore;
import id.redhat.razhari.util.StanGenerator;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.spi.BacklogTracer;
import org.apache.camel.spi.BacklogTracerEventMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    @Inject IsoDispatcher isoDispatcher;
    @Inject ObjectMapper objectMapper;
    @Inject CamelContext camelContext;

    @Override
    public void configure() {
        restConfiguration()
            .component("platform-http")
            .contextPath("/api/v1")
            .bindingMode(RestBindingMode.off);

        rest("/transactions")
            .post()
                .consumes("application/json")
                .produces("application/json")
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

        rest("/trace")
            .get("/{transactionId}")
                .produces("application/json")
                .to("direct:rest-trace");

        // Submit: save state as PENDING, fire async send to switch, return 202 + UUID
        from("direct:rest-submit")
            .routeId("rest-submit")
            .unmarshal(json(TransactionRequest.class))
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
                isoDispatcher.dispatch(state);

                exchange.getMessage().setBody(Map.of("transactionId", state.id));
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 202);
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            })
            .marshal(json());

        // Poll by UUID: returns full TransactionResponse or 404
        from("direct:rest-get-by-id")
            .routeId("rest-get-by-id")
            .process(exchange -> {
                String id = exchange.getIn().getHeader("id", String.class);
                boolean found = store.findById(id).map(s -> {
                    exchange.getMessage().setBody(toResponse(s));
                    exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
                    return true;
                }).orElse(false);
                if (!found) {
                    exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                    exchange.getMessage().setBody(null);
                    exchange.setProperty("skipMarshal", true);
                }
            })
            .choice()
                .when(exchangeProperty("skipMarshal").isNotEqualTo(true))
                    .marshal(json())
            .end();

        // Lightweight status check: returns {status, createdAt, updatedAt} or 404
        from("direct:rest-get-status")
            .routeId("rest-get-status")
            .process(exchange -> {
                String id = exchange.getIn().getHeader("id", String.class);
                boolean found = store.findById(id).map(s -> {
                    exchange.getMessage().setBody(Map.of(
                        "status",    s.status.name(),
                        "createdAt", s.createdAt.toString(),
                        "updatedAt", s.updatedAt.toString()
                    ));
                    exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
                    return true;
                }).orElse(false);
                if (!found) {
                    exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                    exchange.getMessage().setBody(null);
                    exchange.setProperty("skipMarshal", true);
                }
            })
            .choice()
                .when(exchangeProperty("skipMarshal").isNotEqualTo(true))
                    .marshal(json())
            .end();

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
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            })
            .marshal(json());

        // Trace: returns all BacklogTracer steps for a given transaction UUID
        from("direct:rest-trace")
            .routeId("rest-trace")
            .process(exchange -> {
                String txId = exchange.getIn().getHeader("transactionId", String.class);
                BacklogTracer tracer = camelContext.getCamelContextExtension()
                    .getContextPlugin(BacklogTracer.class);
                List<TraceStep> steps;
                if (tracer == null) {
                    steps = Collections.emptyList();
                } else {
                    AtomicInteger counter = new AtomicInteger(1);
                    steps = tracer.dumpAllTracedMessages().stream()
                        .filter(m -> m.getMessageAsXml() != null && m.getMessageAsXml().contains(txId))
                        .sorted(java.util.Comparator.comparingLong(BacklogTracerEventMessage::getUid))
                        .map(m -> toTraceStep(m, counter.getAndIncrement()))
                        .collect(Collectors.toList());
                }
                exchange.getMessage().setBody(steps);
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            })
            .marshal(json());
    }

    private static final Pattern PAN_PATTERN =
        Pattern.compile("(\\d{6})\\d{3,9}(\\d{4})");
    private static final Pattern BODY_TYPE_PATTERN =
        Pattern.compile("<body[^>]+type=\"([^\"]+)\"");

    private TraceStep toTraceStep(BacklogTracerEventMessage msg, int step) {
        TraceStep ts = new TraceStep();
        ts.step      = step;
        ts.routeId   = msg.getRouteId() != null ? msg.getRouteId() : "";
        ts.node      = msg.getToNode()  != null ? msg.getToNode()  : "";
        ts.timestamp = Instant.ofEpochMilli(msg.getTimestamp()).toString();

        String raw = msg.getMessageAsXml() != null ? msg.getMessageAsXml() : "";

        Matcher typeMatcher = BODY_TYPE_PATTERN.matcher(raw);
        ts.bodyType = typeMatcher.find()
            ? typeMatcher.group(1).replaceAll(".*\\.", "")
            : "Object";

        String masked = PAN_PATTERN.matcher(raw).replaceAll("$1******$2");
        ts.bodySummary = masked.length() > 200 ? masked.substring(0, 197) + "..." : masked;
        return ts;
    }

    private JacksonDataFormat json() {
        return new JacksonDataFormat(objectMapper, Object.class);
    }

    private JacksonDataFormat json(Class<?> type) {
        return new JacksonDataFormat(objectMapper, type);
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
