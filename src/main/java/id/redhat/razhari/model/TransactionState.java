package id.redhat.razhari.model;

import java.time.Instant;
import java.util.Map;

public class TransactionState {
    public String id;                    // UUID assigned at REST submit time
    public String stan;                  // ISO 8583 field 11 correlation key
    public TransactionStatus status;
    public TransactionRequest request;
    public Map<String, String> result;   // populated from switch response
    public String errorMessage;          // set by onException handler for debugging
    public Instant createdAt;
    public Instant updatedAt;
}
