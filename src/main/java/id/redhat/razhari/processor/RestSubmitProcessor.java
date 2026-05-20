package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionRequest;
import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.route.IsoDispatcher;
import id.redhat.razhari.store.TransactionStore;
import id.redhat.razhari.util.StanGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
@Named
public class RestSubmitProcessor implements Processor {

    @Inject TransactionStore store;
    @Inject StanGenerator stanGenerator;
    @Inject IsoDispatcher isoDispatcher;

    @Override
    public void process(Exchange exchange) {
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

        exchange.getMessage().setBody(Map.of("transactionId", state.id));
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 202);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
    }
}
