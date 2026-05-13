package id.redhat.razhari.model;

public class TransactionRequest {
    public String mti;          // e.g. "0200"
    public String pan;          // field 2: Primary Account Number
    public long amount;         // field 4: transaction amount in minor units
    public String currency;     // field 49: ISO 4217 numeric code e.g. "840"
    public String terminalId;   // field 41: 8-char terminal ID
    public String merchantId;   // field 42: 15-char merchant ID
}
