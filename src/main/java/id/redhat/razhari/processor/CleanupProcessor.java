package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.TransactionStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

@ApplicationScoped
@Named
public class CleanupProcessor implements Processor {

    @Inject
    TransactionStore store;

    @ConfigProperty(name = "camel.iso8583.transaction.timeout-ms", defaultValue = "30000")
    long timeoutMs;

    @Override
    public void process(Exchange exchange) {
        Instant threshold = Instant.now().minusMillis(timeoutMs);
        store.findPendingOlderThan(threshold).forEach(state -> {
            state.status = TransactionStatus.TIMEOUT;
            state.updatedAt = Instant.now();
            store.update(state);
        });
    }
}
