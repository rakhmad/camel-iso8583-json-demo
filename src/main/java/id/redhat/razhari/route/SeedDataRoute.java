package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionRequest;
import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.TransactionStore;
import id.redhat.razhari.util.StanGenerator;
import io.quarkus.arc.profile.IfBuildProfile;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dev-only route that fires once on startup to pre-populate demo transactions.
 * Calls the store and dispatcher directly (same domain logic as direct:rest-submit)
 * without the HTTP-contract marshal/unmarshal layer.
 * Not active in test or prod profiles (@IfBuildProfile("dev")).
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class SeedDataRoute extends RouteBuilder {

    @Inject TransactionStore store;
    @Inject StanGenerator stanGenerator;
    @Inject IsoDispatcher isoDispatcher;

    @ConfigProperty(name = "seed.delay-ms", defaultValue = "3000")
    Long delayMs;

    @Override
    public void configure() {
        onException(Exception.class)
            .handled(true)
            .log(LoggingLevel.ERROR, "SeedDataRoute",
                "Seed data failed: ${exception.message}\n${exception.stacktrace}");

        from(String.format("timer:seed?delay=%d&repeatCount=1", delayMs))
            .routeId("seed-data")
            .log("Seed timer fired — submitting demo transactions")
            .process(exchange -> exchange.getMessage().setBody(List.of(
                request("0200", "4111111111111111", 10000L, "840", "TERM0001", "COFFEESHOP001"),
                request("0200", "5500000000000004", 25099L, "840", "TERM0002", "RESTAURANT001"),
                request("0200", "4000000000000002",  4999L, "978", "TERM0003", "BOOKSHOP_EU01"),
                request("0200", "4111111111111111", 999999L, "840", "TERM0001", "JEWELER001    ")
            )))
            .split(body())
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
                isoDispatcher.dispatch(state);
            })
            .log("Seed: queued ${body.merchantId}");
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
