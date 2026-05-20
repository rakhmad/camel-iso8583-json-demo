package id.redhat.razhari.processor;

import id.redhat.razhari.model.TraceStep;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.spi.BacklogTracer;
import org.apache.camel.spi.BacklogTracerEventMessage;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
@Named
public class RestTraceProcessor implements Processor {

    @Inject
    CamelContext camelContext;

    private static final Pattern PAN_PATTERN =
        Pattern.compile("(\\d{6})\\d{3,9}(\\d{4})");
    private static final Pattern BODY_TYPE_PATTERN =
        Pattern.compile("<body[^>]+type=\"([^\"]+)\"");

    @Override
    public void process(Exchange exchange) {
        String txId = exchange.getIn().getHeader("transactionId", String.class);
        BacklogTracer tracer = camelContext.getCamelContextExtension()
            .getContextPlugin(BacklogTracer.class);
        List<TraceStep> steps;
        if (tracer == null || !tracer.isEnabled()) {
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
    }

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
}
