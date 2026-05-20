package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;

/**
 * Application-scoped wrapper around ProducerTemplate for dispatching
 * outbound ISO 8583 messages. Being ApplicationScoped allows this bean
 * to be mocked via @InjectMock in @QuarkusTest — ProducerTemplate itself
 * is @Dependent and cannot be mocked directly.
 *
 * The txId header is set so BacklogTracer XML includes the UUID in every
 * step of the send-iso8583 exchange, enabling per-transaction filtering.
 */
@ApplicationScoped
public class IsoDispatcher {

    @Inject
    ProducerTemplate producerTemplate;

    public void dispatch(TransactionState state) {
        producerTemplate.asyncSend("direct:send-iso8583", exchange -> {
            exchange.getIn().setBody(state);
            exchange.getIn().setHeader("txId", state.id);
        });
    }
}
