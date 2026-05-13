package id.redhat.razhari.model;

import java.time.Instant;
import java.util.Map;

public class TransactionResponse {
    public String transactionId;
    public TransactionStatus status;
    public Instant createdAt;
    public Instant updatedAt;
    public Map<String, String> result;   // null when PENDING
}
