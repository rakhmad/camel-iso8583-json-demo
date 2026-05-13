package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.ProducerTemplate;

/**
 * Application-scoped CDI bean that delegates to Camel's ProducerTemplate.
 * Camel's ProducerTemplate is @Dependent-scoped and cannot be mocked directly
 * in @QuarkusTest. This wrapper provides a normal CDI scope so it can be
 * replaced with @InjectMock in tests.
 */
@ApplicationScoped
public class IsoMessageSender {

    @Inject
    ProducerTemplate producerTemplate;

    public void asyncSend(TransactionState state) {
        producerTemplate.asyncSendBody("direct:send-iso8583", state);
    }
}
