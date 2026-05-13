package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionRequest;
import id.redhat.razhari.model.TransactionState;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

@ApplicationScoped
@Named("jsonToIsoProcessor")
public class JsonToIsoProcessor implements Processor {

    private final MessageFactory<IsoMessage> factory;

    @Inject
    public JsonToIsoProcessor(MessageFactory<IsoMessage> factory) {
        this.factory = factory;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        TransactionState state = exchange.getIn().getBody(TransactionState.class);
        TransactionRequest req = state.request;

        // MTI "0200" parsed as hex: Integer.parseInt("0200", 16) = 0x0200 = 512
        int mti = Integer.parseInt(req.mti, 16);
        IsoMessage msg = factory.newMessage(mti);

        msg.setValue(2,  req.pan,                              IsoType.LLVAR,   0);
        msg.setValue(4,  String.format("%012d", req.amount),   IsoType.NUMERIC, 12);
        msg.setValue(11, state.stan,                           IsoType.NUMERIC, 6);
        msg.setValue(41, req.terminalId,                       IsoType.ALPHA,   8);
        msg.setValue(42, req.merchantId,                       IsoType.ALPHA,   15);
        msg.setValue(49, req.currency,                         IsoType.NUMERIC, 3);

        exchange.getIn().setBody(msg);
    }
}
