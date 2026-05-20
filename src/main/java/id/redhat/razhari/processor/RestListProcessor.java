package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionResponse;
import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.TransactionStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
@Named
public class RestListProcessor implements Processor {

    @Inject
    TransactionStore store;

    @Override
    public void process(Exchange exchange) {
        String type = exchange.getIn().getHeader("type", String.class);
        List<TransactionState> states = "inbound".equals(type)
            ? store.findByStatus(TransactionStatus.RECEIVED)
            : store.findAll();
        exchange.getMessage().setBody(
            states.stream().map(this::toResponse).collect(Collectors.toList())
        );
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
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
