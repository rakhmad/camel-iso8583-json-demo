# camel-iso8583-json-demo

A bidirectional **ISO 8583 ↔ JSON** protocol bridge built with Red Hat Build of Quarkus 3.27 and Apache Camel 4.14.

REST clients submit JSON payment transactions and poll for results. The bridge converts JSON to binary ISO 8583, forwards the message over TCP to a payment switch, receives the response, and makes it available via the poll endpoint.

---

## Architecture

```
REST Client (JSON)
      │  POST /api/v1/transactions → 202 + UUID
      │  GET  /api/v1/transactions/{id} → COMPLETED / PENDING
      ▼
┌─────────────────────────────────────────────────┐
│  Quarkus + Camel                                │
│                                                 │
│  RESTEasy Reactive  ──►  Camel Route            │
│                           │                     │
│                    JsonToIsoProcessor            │
│                           │                     │
│                    ISO8583Encoder               │
│                           │                     │
│               Camel Netty TCP (port 8583) ◄──── │
│                           │                     │
│               Payment Switch / Mock             │
│                           │                     │
│                    ISO8583Decoder               │
│                           │                     │
│                    IsoToJsonProcessor            │
│                           │                     │
│                    TransactionStore             │
│               (ConcurrentHashMap, v1)           │
└─────────────────────────────────────────────────┘
```

**Correlation key:** ISO 8583 field 11 (STAN — System Trace Audit Number). Every outbound request gets a unique STAN; the switch echoes it in the response for exact matching.

---

## Prerequisites

- Java 21+
- Maven (or use the included `./mvnw` wrapper)
- Red Hat GA Maven repository (already configured in `pom.xml`)

---

## Running the App

```bash
# Development mode (hot reload)
./mvnw quarkus:dev
```

The REST API is available at `http://localhost:8080/api/v1/transactions`.

---

## Running the Demo

The demo requires three terminals: a mock payment switch, the bridge app, and the demo script.

**Terminal 1 — Mock ISO 8583 switch**
```bash
./scripts/mock-switch.sh
```

Starts a Netty TCP server on port 8583 that accepts `0200` authorization requests and replies with approved `0210` responses.

**Terminal 2 — The bridge app**
```bash
./mvnw quarkus:dev
```

**Terminal 3 — Demo flow**
```bash
./scripts/demo.sh
```

Submits four seed transactions (Visa USD, Mastercard USD, EUR, high-value) one by one, polls each to completion, and prints the full JSON result.

---

## API Usage

### Submit a transaction

```bash
curl -s -X POST http://localhost:8080/api/v1/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "mti": "0200",
    "pan": "4111111111111111",
    "amount": 10000,
    "currency": "840",
    "terminalId": "TERM0001",
    "merchantId": "COFFEESHOP001"
  }'
```

Response (`202 Accepted`):
```json
{ "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6" }
```

### Poll for result

```bash
curl -s http://localhost:8080/api/v1/transactions/3fa85f64-5717-4562-b3fc-2c963f66afa6
```

Response when completed:
```json
{
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "COMPLETED",
  "createdAt": "2026-05-13T15:00:00Z",
  "updatedAt": "2026-05-13T15:00:01Z",
  "result": {
    "mti": "0210",
    "stan": "000001",
    "authCode": "AUTH01",
    "responseCode": "00"
  }
}
```

### Lightweight status check

```bash
curl -s http://localhost:8080/api/v1/transactions/3fa85f64-5717-4562-b3fc-2c963f66afa6/status
```

### List switch-initiated (inbound) events

```bash
curl -s 'http://localhost:8080/api/v1/transactions?type=inbound'
```

---

## Transaction States

| State | Description |
|---|---|
| `PENDING` | Submitted, awaiting switch response |
| `COMPLETED` | Switch responded with a result |
| `FAILED` | Parse error or switch connection failure |
| `TIMEOUT` | No switch response within the configured TTL |
| `RECEIVED` | Unsolicited inbound message from the switch |

---

## Configuration

Key properties in `src/main/resources/application.properties`:

```properties
camel.iso8583.server.port=9583          # TCP port for inbound switch connections
camel.iso8583.switch.host=localhost     # Outbound switch hostname
camel.iso8583.switch.port=8583         # Outbound switch port
camel.iso8583.transaction.timeout-ms=30000
camel.iso8583.transaction.cleanup-interval-ms=60000
```

---

## Running Tests

```bash
# Unit tests only (fast, no app startup)
./mvnw test

# Unit + integration tests
./mvnw verify
```
