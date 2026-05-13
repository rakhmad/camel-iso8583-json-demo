package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import org.apache.camel.builder.RouteBuilder;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

@ApplicationScoped
public class ISO8583SendRoute extends RouteBuilder {

    @Override
    public void configure() {
        onException(Exception.class)
            .handled(true)
            .process(exchange -> {
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
                // Stash original state before processor replaces the body
                exchange.setProperty("originalState",
                    exchange.getIn().getBody(TransactionState.class));
            })
            .process("jsonToIsoProcessor")
            .to("netty:tcp://{{camel.iso8583.switch.host}}:{{camel.iso8583.switch.port}}"
                + "?clientInitializerFactory=#iso8583ClientInitializer"
                + "&sync=true"
                + "&reuseChannel=true"
                + "&connectTimeout={{camel.iso8583.netty.connect-timeout}}")
            .process("isoToJsonProcessor");
    }
}
