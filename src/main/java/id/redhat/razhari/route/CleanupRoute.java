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
