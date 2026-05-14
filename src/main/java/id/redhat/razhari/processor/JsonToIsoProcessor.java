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

/**
 * Camel processor: converts a {@link TransactionState} (carrying a JSON request)
 * into a j8583 {@link IsoMessage} ready to be sent to the payment switch.
 *
 * ISO 8583 field mapping:
 *   Field  2 — PAN (Primary Account Number), LLVAR: variable-length, 2-digit length prefix
 *   Field  4 — Amount, NUMERIC-12: minor currency units (cents), zero-padded
 *   Field 11 — STAN (System Trace Audit Number), NUMERIC-6: correlation key echoed in response
 *   Field 41 — Terminal ID, ALPHA-8: space-padded on the right
 *   Field 42 — Merchant ID, ALPHA-15: space-padded on the right
 *   Field 49 — Currency code, NUMERIC-3: ISO 4217 numeric (840=USD, 978=EUR)
 *
 * The MTI string (e.g. "0200") is parsed as a hex integer: Integer.parseInt("0200", 16) = 512.
 * j8583's newMessage() accepts this integer form.
 */
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

        msg.setValue(2,  req.pan,                            IsoType.LLVAR,   0);
        msg.setValue(4,  String.format("%012d", req.amount), IsoType.NUMERIC, 12);
        msg.setValue(11, state.stan,                         IsoType.NUMERIC, 6);
        msg.setValue(41, req.terminalId,                     IsoType.ALPHA,   8);
        msg.setValue(42, req.merchantId,                     IsoType.ALPHA,   15);
        msg.setValue(49, req.currency,                       IsoType.NUMERIC, 3);

        exchange.getIn().setBody(msg);
    }
}
