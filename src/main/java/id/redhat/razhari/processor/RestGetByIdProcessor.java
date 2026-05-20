package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionResponse;
import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.store.TransactionStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

@ApplicationScoped
@Named
public class RestGetByIdProcessor implements Processor {

    @Inject
    TransactionStore store;

    @Override
    public void process(Exchange exchange) {
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
    }

    private TransactionResponse toResponse(TransactionState s) {
        TransactionResponse r = new TransactionResponse();
        r.transactionId = s.id;
        r.status        = s.status;
        r.createdAt     = s.createdAt;
        r.updatedAt     = s.updatedAt;
        r.result        = s.result;
        r.errorMessage  = s.errorMessage;
        r.request       = s.request;
        return r;
    }
}
