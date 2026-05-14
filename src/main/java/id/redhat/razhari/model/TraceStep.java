package id.redhat.razhari.model;

/** One BacklogTracer step for a transaction — returned by GET /api/v1/trace/{transactionId}. */
public class TraceStep {
    public int    step;
    public String routeId;
    public String node;
    public String timestamp;   // ISO-8601 string
    public String bodyType;    // simple class name extracted from BacklogTracer XML
    public String bodySummary; // ≤200 chars, PAN masked
}
