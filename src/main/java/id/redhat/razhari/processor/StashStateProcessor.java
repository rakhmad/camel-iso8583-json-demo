package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

@ApplicationScoped
@Named
public class StashStateProcessor implements Processor {
    @Override
    public void process(Exchange exchange) {
        exchange.setProperty("originalState",
            exchange.getIn().getBody(TransactionState.class));
    }
}
