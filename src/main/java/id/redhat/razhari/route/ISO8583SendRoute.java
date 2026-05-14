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
