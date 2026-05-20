package id.redhat.razhari.processor;

import id.redhat.razhari.store.TransactionStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.Map;

@ApplicationScoped
@Named
public class RestGetStatusProcessor implements Processor {

    @Inject
    TransactionStore store;

    @Override
    public void process(Exchange exchange) {
        String id = exchange.getIn().getHeader("id", String.class);
        boolean found = store.findById(id).map(s -> {
            exchange.getMessage().setBody(Map.of(
                "status",    s.status.name(),
                "createdAt", s.createdAt.toString(),
                "updatedAt", s.updatedAt.toString()
            ));
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            return true;
        }).orElse(false);
        if (!found) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
            exchange.getMessage().setBody(null);
            exchange.setProperty("skipMarshal", true);
        }
    }
}
