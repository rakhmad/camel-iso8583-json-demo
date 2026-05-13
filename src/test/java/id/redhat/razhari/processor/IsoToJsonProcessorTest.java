package id.redhat.razhari.processor;

import id.redhat.razhari.config.MessageFactoryProducer;
import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.InMemoryTransactionStore;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IsoToJsonProcessorTest {

    static MessageFactory<IsoMessage> factory;
    InMemoryTransactionStore store;
    IsoToJsonProcessor processor;

    @BeforeAll
    static void initFactory() throws Exception {
        factory = new MessageFactoryProducer().messageFactory();
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryTransactionStore();
        processor = new IsoToJsonProcessor(store);
    }

    @Test
    void updatesExistingStateToCompletedWhenStanMatches() throws Exception {
        TransactionState existing = new TransactionState();
        existing.id = "id-1";
        existing.stan = "000042";
        existing.status = TransactionStatus.PENDING;
        existing.createdAt = Instant.now();
        existing.updatedAt = Instant.now();
        store.save(existing);

        IsoMessage response = factory.newMessage(0x0210);
        response.setValue(11, "000042", IsoType.NUMERIC, 6);
        response.setValue(38, "AUTH01", IsoType.ALPHA, 6);
        response.setValue(39, "00",     IsoType.ALPHA, 2);

        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(response);
        processor.process(exchange);

        TransactionState updated = store.findById("id-1").orElseThrow();
        assertThat(updated.status).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(updated.result).containsEntry("responseCode", "00");
        assertThat(updated.result).containsEntry("authCode", "AUTH01");
    }

    @Test
    void createsNewReceivedStateForUnsolicitedMessage() throws Exception {
        IsoMessage unsolicited = factory.newMessage(0x0400);
        unsolicited.setValue(11, "000099", IsoType.NUMERIC, 6);
        unsolicited.setValue(39, "00",     IsoType.ALPHA, 2);

        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(unsolicited);
        processor.process(exchange);

        assertThat(store.findByStatus(TransactionStatus.RECEIVED)).hasSize(1);
        assertThat(store.findByStan("000099")).isPresent();
    }

    @Test
    void doesNotChangeOtherStatesWhenUpdating() throws Exception {
        TransactionState s1 = new TransactionState();
        s1.id = "id-1"; s1.stan = "000001"; s1.status = TransactionStatus.PENDING;
        s1.createdAt = Instant.now(); s1.updatedAt = Instant.now();

        TransactionState s2 = new TransactionState();
        s2.id = "id-2"; s2.stan = "000002"; s2.status = TransactionStatus.PENDING;
        s2.createdAt = Instant.now(); s2.updatedAt = Instant.now();

        store.save(s1);
        store.save(s2);

        IsoMessage response = factory.newMessage(0x0210);
        response.setValue(11, "000001", IsoType.NUMERIC, 6);
        response.setValue(39, "00",     IsoType.ALPHA, 2);

        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(response);
        processor.process(exchange);

        assertThat(store.findById("id-2").get().status).isEqualTo(TransactionStatus.PENDING);
    }
}
