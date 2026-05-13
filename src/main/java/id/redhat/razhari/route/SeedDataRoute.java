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
