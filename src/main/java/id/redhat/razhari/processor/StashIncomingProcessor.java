package id.redhat.razhari.processor;

import com.solab.iso8583.IsoMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

@ApplicationScoped
@Named
public class StashIncomingProcessor implements Processor {
    @Override
    public void process(Exchange exchange) {
        exchange.setProperty("incomingMsg",
            exchange.getIn().getBody(IsoMessage.class));
    }
}
