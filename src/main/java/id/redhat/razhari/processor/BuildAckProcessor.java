package id.redhat.razhari.processor;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

@ApplicationScoped
@Named
public class BuildAckProcessor implements Processor {

    @Inject
    MessageFactory<IsoMessage> messageFactory;

    @Override
    public void process(Exchange exchange) throws Exception {
        IsoMessage incoming = exchange.getProperty("incomingMsg", IsoMessage.class);
        int responseMti = incoming.getType() + 0x0010;
        IsoMessage ack = messageFactory.newMessage(responseMti);
        if (incoming.hasField(11)) {
            ack.setValue(11, incoming.getField(11).toString(), IsoType.NUMERIC, 6);
        }
        ack.setValue(39, "00", IsoType.ALPHA, 2);
        exchange.getIn().setBody(ack);
    }
}
