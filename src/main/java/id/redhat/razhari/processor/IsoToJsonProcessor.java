package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.TransactionStore;
import com.solab.iso8583.IsoMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Named("isoToJsonProcessor")
public class IsoToJsonProcessor implements Processor {

    private final TransactionStore store;

    @Inject
    public IsoToJsonProcessor(TransactionStore store) {
        this.store = store;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        IsoMessage msg = exchange.getIn().getBody(IsoMessage.class);
        String stan = msg.hasField(11) ? msg.getField(11).toString().trim() : null;

        Optional<TransactionState> existing = stan != null ? store.findByStan(stan) : Optional.empty();

        if (existing.isPresent()) {
            TransactionState state = existing.get();
            state.status = TransactionStatus.COMPLETED;
            state.updatedAt = Instant.now();
            state.result = extractFields(msg);
            store.update(state);
        } else {
            TransactionState state = new TransactionState();
            state.id = UUID.randomUUID().toString();
            state.stan = stan;
            state.status = TransactionStatus.RECEIVED;
            state.createdAt = Instant.now();
            state.updatedAt = Instant.now();
            state.result = extractFields(msg);
            store.save(state);
        }
    }

    private Map<String, String> extractFields(IsoMessage msg) {
        Map<String, String> result = new HashMap<>();
        result.put("mti", String.format("%04x", msg.getType()).toUpperCase());
        if (msg.hasField(11)) result.put("stan",         msg.getField(11).toString().trim());
        if (msg.hasField(37)) result.put("retrievalRef", msg.getField(37).toString().trim());
        if (msg.hasField(38)) result.put("authCode",     msg.getField(38).toString().trim());
        if (msg.hasField(39)) result.put("responseCode", msg.getField(39).toString().trim());
        return result;
    }
}
