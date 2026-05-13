package id.redhat.razhari.processor;

import id.redhat.razhari.config.MessageFactoryProducer;
import id.redhat.razhari.model.TransactionRequest;
import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.MessageFactory;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JsonToIsoProcessorTest {

    static MessageFactory<IsoMessage> factory;
    static JsonToIsoProcessor processor;

    @BeforeAll
    static void init() throws Exception {
        factory = new MessageFactoryProducer().messageFactory();
        processor = new JsonToIsoProcessor(factory);
    }

    @Test
    void mapsTransactionRequestToIsoMessage() throws Exception {
        TransactionState state = buildState("4111111111111111", 10000L, "840", "TERM0001", "MERCH001");
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(state);

        processor.process(exchange);

        IsoMessage msg = exchange.getIn().getBody(IsoMessage.class);
        assertThat(msg).isNotNull();
        assertThat(msg.getType()).isEqualTo(0x0200);
        assertThat(msg.getField(2).toString()).isEqualTo("4111111111111111");
        assertThat(msg.getField(11).toString()).isEqualTo("000001");
        assertThat(msg.getField(49).toString()).isEqualTo("840");
    }

    @Test
    void setsStanFromTransactionState() throws Exception {
        TransactionState state = buildState("5500000000000004", 5000L, "978", "TERM0002", "MERCH002");
        state.stan = "000099";

        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(state);
        processor.process(exchange);

        IsoMessage msg = exchange.getIn().getBody(IsoMessage.class);
        assertThat(msg.getField(11).toString()).isEqualTo("000099");
    }

    @Test
    void formatsAmountAsTwelveDigitNumeric() throws Exception {
        TransactionState state = buildState("4111111111111111", 1L, "840", "TERM0001", "MERCH001");
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(state);
        processor.process(exchange);

        IsoMessage msg = exchange.getIn().getBody(IsoMessage.class);
        assertThat(msg.getField(4).toString()).isEqualTo("000000000001");
    }

    private TransactionState buildState(String pan, long amount, String currency,
                                        String terminalId, String merchantId) {
        TransactionRequest req = new TransactionRequest();
        req.mti = "0200";
        req.pan = pan;
        req.amount = amount;
        req.currency = currency;
        req.terminalId = terminalId;
        req.merchantId = merchantId;

        TransactionState s = new TransactionState();
        s.id = "test-id";
        s.stan = "000001";
        s.status = TransactionStatus.PENDING;
        s.request = req;
        s.createdAt = Instant.now();
        s.updatedAt = Instant.now();
        return s;
    }
}
