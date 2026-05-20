package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.time.Instant;

@ApplicationScoped
@Named
public class SendFailedProcessor implements Processor {
    @Override
    public void process(Exchange exchange) {
        TransactionState state = exchange.getProperty("originalState", TransactionState.class);
        if (state != null) {
            state.status = TransactionStatus.FAILED;
            state.updatedAt = Instant.now();
            Exception ex = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
            state.errorMessage = ex != null
                ? ex.getClass().getSimpleName() + ": " + ex.getMessage()
                : "unknown";
        }
    }
}
