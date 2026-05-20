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

/**
 * Camel processor: converts an inbound j8583 {@link IsoMessage} into a
 * {@link TransactionState} store update or a new RECEIVED event.
 *
 * Two cases:
 *   1. STAN (field 11) matches an existing PENDING state → mark COMPLETED, store result fields.
 *   2. No matching STAN → switch-initiated message (reversal, advice); store as RECEIVED.
 *
 * ISO 8583 response fields extracted:
 *   Field 11 — STAN: used as the correlation key to find the original request
 *   Field 37 — Retrieval Reference Number (RRN): 12-char switch-assigned reference
 *   Field 38 — Authorization Code: 6-char approval code from the issuer
 *   Field 39 — Response Code: 2-char result ("00" = approved, "05" = declined, etc.)
 *
 * ALPHA fields from j8583 are space-padded to their declared length; .trim() removes padding.
 */
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
        // Field 11 (STAN) is the correlation key between request and response
        String stan = msg.hasField(11) ? msg.getField(11).toString().trim() : null;

        Optional<TransactionState> existing = stan != null ? store.findByStan(stan) : Optional.empty();

        if (existing.isPresent()) {
            // Outbound response: switch replied to our 0200 with a 0210
            TransactionState state = existing.get();
            state.status = TransactionStatus.COMPLETED;
            state.updatedAt = Instant.now();
            state.result = extractFields(msg);
            store.update(state);
        } else {
            // Unsolicited inbound: reversal, advice, or network management from the switch
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
        // .trim() removes space padding that j8583 adds to ALPHA-type fields
        if (msg.hasField(11)) { result.put("stan",         msg.getField(11).toString().trim()); }
        if (msg.hasField(37)) { result.put("retrievalRef", msg.getField(37).toString().trim()); }
        if (msg.hasField(38)) { result.put("authCode",     msg.getField(38).toString().trim()); }
        if (msg.hasField(39)) { result.put("responseCode", msg.getField(39).toString().trim()); }
        return result;
    }
}
