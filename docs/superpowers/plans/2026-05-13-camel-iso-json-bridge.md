# Camel ISO 8583 ↔ JSON Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a bidirectional ISO 8583/JSON bridge where REST clients submit JSON transactions (202 + UUID), the integration converts them to ISO 8583 and forwards over TCP to a payment switch, and clients poll for the switch response.

**Architecture:** JAX-RS REST (RESTEasy Reactive) → TransactionStore (ConcurrentHashMap) → Camel route → j8583 codec → Camel Netty TCP client → switch. Inbound unsolicited messages flow the reverse path. STAN (field 11) is the correlation key between outbound requests and switch responses.

**Tech Stack:** Red Hat Quarkus `3.27.3.SP1-redhat-00002`, Apache Camel 4.x via `io.quarkus.platform:quarkus-camel-bom:3.27.3`, `camel-quarkus-iso8583` (ISO 8583 encode/decode; j8583 is a transitive dep), Netty 4.x (via `camel-quarkus-netty`), JUnit 5, RestAssured, Mockito via `quarkus-junit5-mockito`

---

## File Map

```
src/main/java/id/redhat/razhari/
├── model/
│   ├── TransactionStatus.java     # enum: PENDING, COMPLETED, FAILED, TIMEOUT, RECEIVED
│   ├── TransactionRequest.java    # REST input POJO
│   ├── TransactionState.java      # internal state (id, stan, status, request, result)
│   └── TransactionResponse.java   # REST output POJO
├── store/
│   ├── TransactionStore.java      # interface: save/findById/findByStan/update/findAll/findPendingOlderThan
│   └── InMemoryTransactionStore.java  # ConcurrentHashMap impl, @ApplicationScoped
├── codec/
│   ├── ISO8583Decoder.java        # ByteToMessageDecoder: 2-byte length prefix → ISOMessage
│   └── ISO8583Encoder.java        # MessageToByteEncoder: ISOMessage → 2-byte length + bytes
├── config/
│   ├── MessageFactoryProducer.java    # @Produces MessageFactory<ISOMessage> from j8583.xml
│   ├── ISO8583ServerInitializer.java  # ServerInitializerFactory for Netty TCP server
│   └── ISO8583ClientInitializer.java  # ClientInitializerFactory for Netty TCP client
├── util/
│   └── StanGenerator.java         # atomic 6-digit STAN counter, @ApplicationScoped
├── processor/
│   ├── JsonToIsoProcessor.java    # TransactionState → ISOMessage (outbound)
│   └── IsoToJsonProcessor.java    # ISOMessage → update TransactionStore (inbound response + unsolicited)
├── route/
│   ├── ISO8583SendRoute.java      # direct:send-iso8583 → Netty TCP client → switch
│   ├── ISO8583ServerRoute.java    # netty:tcp server → IsoToJsonProcessor (unsolicited)
│   └── CleanupRoute.java          # timer: move PENDING > TTL to TIMEOUT
└── rest/
    └── TransactionResource.java   # JAX-RS: POST /transactions, GET /transactions/{id}, GET /transactions

src/main/resources/
├── application.properties
└── j8583.xml                      # field definitions for MTI 0200 / 0210

src/test/java/id/redhat/razhari/
├── store/
│   └── InMemoryTransactionStoreTest.java   # plain JUnit 5
├── codec/
│   └── ISO8583CodecTest.java               # plain JUnit 5, EmbeddedChannel
├── util/
│   └── StanGeneratorTest.java              # plain JUnit 5
├── processor/
│   ├── JsonToIsoProcessorTest.java         # plain JUnit 5, mock Exchange
│   └── IsoToJsonProcessorTest.java         # plain JUnit 5, mock Exchange
├── rest/
│   └── TransactionResourceTest.java        # @QuarkusTest + @InjectMock
└── functional/
    └── FullFlowIT.java                     # @QuarkusIntegrationTest + MockISO8583Switch
```

---

## Task 1: Project Bootstrap

**Files:**
- Create: `pom.xml`
- Create: `src/main/resources/application.properties`

- [ ] **Step 1: Create project directory structure** (project already bootstrapped from Red Hat template)

```bash
mkdir -p src/main/java/id/redhat/razhari/{model,store,codec,config,util,processor,route,rest}
mkdir -p src/test/java/id/redhat/razhari/{store,codec,util,processor,rest,functional}
mkdir -p src/test/resources
```

- [ ] **Step 2: Verify pom.xml** (already bootstrapped from Red Hat template — confirm it has these deps)

The `pom.xml` was bootstrapped from `code.quarkus.redhat.com`. Verify it contains:

```xml
<!-- Key coordinates -->
<groupId>id.redhat.razhari</groupId>
<artifactId>camel-iso-json</artifactId>
<version>1.0.0-SNAPSHOT</version>

<!-- RHBQ version -->
<quarkus.platform.group-id>com.redhat.quarkus.platform</quarkus.platform.group-id>
<quarkus.platform.version>3.27.3.SP1-redhat-00002</quarkus.platform.version>

<!-- Camel BOM (upstream version — the Red Hat camel BOM uses io.quarkus.platform, not com.redhat) -->
<dependency>
  <groupId>io.quarkus.platform</groupId>
  <artifactId>quarkus-camel-bom</artifactId>
  <version>3.27.3</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>

<!-- Required runtime dependencies -->
<dependency><groupId>org.apache.camel.quarkus</groupId><artifactId>camel-quarkus-iso8583</artifactId></dependency>
<dependency><groupId>org.apache.camel.quarkus</groupId><artifactId>camel-quarkus-netty</artifactId></dependency>
<dependency><groupId>org.apache.camel.quarkus</groupId><artifactId>camel-quarkus-direct</artifactId></dependency>
<dependency><groupId>org.apache.camel.quarkus</groupId><artifactId>camel-quarkus-timer</artifactId></dependency>
<dependency><groupId>org.apache.camel.quarkus</groupId><artifactId>camel-quarkus-jackson</artifactId></dependency>
<dependency><groupId>io.quarkus</groupId><artifactId>quarkus-rest</artifactId></dependency>
<dependency><groupId>io.quarkus</groupId><artifactId>quarkus-rest-jackson</artifactId></dependency>
<dependency><groupId>io.quarkus</groupId><artifactId>quarkus-arc</artifactId></dependency>

<!-- Test dependencies -->
<dependency><groupId>io.quarkus</groupId><artifactId>quarkus-junit5</artifactId><scope>test</scope></dependency>
<dependency><groupId>io.quarkus</groupId><artifactId>quarkus-junit5-mockito</artifactId><scope>test</scope></dependency>
<dependency><groupId>io.rest-assured</groupId><artifactId>rest-assured</artifactId><scope>test</scope></dependency>
<dependency><groupId>org.apache.camel.quarkus</groupId><artifactId>camel-quarkus-mock</artifactId><scope>test</scope></dependency>

<!-- Red Hat GA repository (required for com.redhat.quarkus.platform artifacts) -->
<repositories>
  <repository>
    <id>redhat-ga</id>
    <url>https://maven.repository.redhat.com/ga/</url>
  </repository>
</repositories>
```

> **Note:** Do NOT add `com.solab:j8583` directly — it is a transitive dependency of `camel-quarkus-iso8583` and will be available on the classpath automatically. The j8583 classes (`ISOMessage`, `MessageFactory`, etc.) are still used in Tasks 5–9; they just arrive transitively.

- [ ] **Step 3: Verify placeholder application.properties** (full config added in Task 14)

File `src/main/resources/application.properties` should already contain:

```properties
quarkus.http.port=8080
quarkus.http.host=0.0.0.0
quarkus.http.root-path=/api/v1

camel.iso8583.switch.host=localhost
camel.iso8583.switch.port=8583
camel.iso8583.server.port=9583
```

- [ ] **Step 4: Verify the project compiles**

```bash
./mvnw compile -U
```

Expected: `BUILD SUCCESS` (the `-U` flag forces Red Hat GA repository re-check on first run)

- [ ] **Step 5: Initialize git and commit bootstrap state**

```bash
git init && git add pom.xml src/main/resources/application.properties
git commit -m "feat: bootstrap Quarkus + Camel ISO 8583 project from Red Hat template"
```

---

## Task 2: Domain Model

**Files:**
- Create: `src/main/java/id/redhat/razhari/model/TransactionStatus.java`
- Create: `src/main/java/id/redhat/razhari/model/TransactionRequest.java`
- Create: `src/main/java/id/redhat/razhari/model/TransactionState.java`
- Create: `src/main/java/id/redhat/razhari/model/TransactionResponse.java`

No tests for plain data classes — tests will cover them through store and REST tests.

- [ ] **Step 1: Create TransactionStatus**

```java
package id.redhat.razhari.model;

public enum TransactionStatus {
    PENDING, COMPLETED, FAILED, TIMEOUT, RECEIVED
}
```

- [ ] **Step 2: Create TransactionRequest**

```java
package id.redhat.razhari.model;

public class TransactionRequest {
    public String mti;          // e.g. "0200"
    public String pan;          // field 2: Primary Account Number
    public long amount;         // field 4: transaction amount in minor units
    public String currency;     // field 49: ISO 4217 numeric code e.g. "840"
    public String terminalId;   // field 41: 8-char terminal ID
    public String merchantId;   // field 42: 15-char merchant ID
}
```

- [ ] **Step 3: Create TransactionState**

```java
package id.redhat.razhari.model;

import java.time.Instant;
import java.util.Map;

public class TransactionState {
    public String id;                    // UUID assigned at REST submit time
    public String stan;                  // ISO 8583 field 11 correlation key
    public TransactionStatus status;
    public TransactionRequest request;
    public Map<String, String> result;   // populated from switch response
    public Instant createdAt;
    public Instant updatedAt;
}
```

- [ ] **Step 4: Create TransactionResponse**

```java
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
```

- [ ] **Step 5: Compile to verify**

```bash
./mvnw compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/id/redhat/razhari/model/
git commit -m "feat: add domain model (TransactionStatus, Request, State, Response)"
```

---

## Task 3: TransactionStore (TDD)

**Files:**
- Create: `src/main/java/id/redhat/razhari/store/TransactionStore.java`
- Create: `src/main/java/id/redhat/razhari/store/InMemoryTransactionStore.java`
- Test: `src/test/java/id/redhat/razhari/store/InMemoryTransactionStoreTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package id.redhat.razhari.store;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTransactionStoreTest {

    InMemoryTransactionStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryTransactionStore();
    }

    @Test
    void savesAndFindsById() {
        TransactionState s = state("id-1", "000001", TransactionStatus.PENDING);
        store.save(s);
        assertThat(store.findById("id-1")).contains(s);
    }

    @Test
    void findsByStanAfterSave() {
        TransactionState s = state("id-1", "000001", TransactionStatus.PENDING);
        store.save(s);
        assertThat(store.findByStan("000001")).contains(s);
    }

    @Test
    void returnsEmptyForUnknownId() {
        assertThat(store.findById("unknown")).isEmpty();
    }

    @Test
    void returnsEmptyForUnknownStan() {
        assertThat(store.findByStan("999999")).isEmpty();
    }

    @Test
    void updatesExistingState() {
        TransactionState s = state("id-1", "000001", TransactionStatus.PENDING);
        store.save(s);
        s.status = TransactionStatus.COMPLETED;
        store.update(s);
        assertThat(store.findById("id-1").get().status).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void findAllReturnsAllSavedStates() {
        store.save(state("id-1", "000001", TransactionStatus.PENDING));
        store.save(state("id-2", "000002", TransactionStatus.COMPLETED));
        assertThat(store.findAll()).hasSize(2);
    }

    @Test
    void findsPendingOlderThanThreshold() {
        TransactionState old = state("id-old", "000001", TransactionStatus.PENDING);
        old.createdAt = Instant.now().minusSeconds(60);
        TransactionState recent = state("id-new", "000002", TransactionStatus.PENDING);
        recent.createdAt = Instant.now();
        store.save(old);
        store.save(recent);

        List<TransactionState> results =
            store.findPendingOlderThan(Instant.now().minusSeconds(30));
        assertThat(results).containsExactly(old);
    }

    @Test
    void findsByStatus() {
        store.save(state("id-1", "000001", TransactionStatus.RECEIVED));
        store.save(state("id-2", "000002", TransactionStatus.PENDING));
        assertThat(store.findByStatus(TransactionStatus.RECEIVED)).hasSize(1);
    }

    private TransactionState state(String id, String stan, TransactionStatus status) {
        TransactionState s = new TransactionState();
        s.id = id;
        s.stan = stan;
        s.status = status;
        s.createdAt = Instant.now();
        s.updatedAt = Instant.now();
        return s;
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./mvnw test -Dtest=InMemoryTransactionStoreTest
```

Expected: FAIL — `InMemoryTransactionStore` does not exist

- [ ] **Step 3: Create TransactionStore interface**

```java
package id.redhat.razhari.store;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransactionStore {
    void save(TransactionState state);
    Optional<TransactionState> findById(String id);
    Optional<TransactionState> findByStan(String stan);
    void update(TransactionState state);
    List<TransactionState> findAll();
    List<TransactionState> findByStatus(TransactionStatus status);
    List<TransactionState> findPendingOlderThan(Instant threshold);
}
```

- [ ] **Step 4: Create InMemoryTransactionStore**

```java
package id.redhat.razhari.store;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class InMemoryTransactionStore implements TransactionStore {

    private final ConcurrentHashMap<String, TransactionState> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> stanToId = new ConcurrentHashMap<>();

    @Override
    public void save(TransactionState state) {
        byId.put(state.id, state);
        if (state.stan != null) stanToId.put(state.stan, state.id);
    }

    @Override
    public Optional<TransactionState> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<TransactionState> findByStan(String stan) {
        return Optional.ofNullable(stanToId.get(stan)).map(byId::get);
    }

    @Override
    public void update(TransactionState state) {
        state.updatedAt = Instant.now();
        byId.put(state.id, state);
    }

    @Override
    public List<TransactionState> findAll() {
        return List.copyOf(byId.values());
    }

    @Override
    public List<TransactionState> findByStatus(TransactionStatus status) {
        return byId.values().stream()
            .filter(s -> s.status == status)
            .collect(Collectors.toList());
    }

    @Override
    public List<TransactionState> findPendingOlderThan(Instant threshold) {
        return byId.values().stream()
            .filter(s -> s.status == TransactionStatus.PENDING && s.createdAt.isBefore(threshold))
            .collect(Collectors.toList());
    }
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
./mvnw test -Dtest=InMemoryTransactionStoreTest
```

Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/id/redhat/razhari/store/ \
        src/test/java/id/redhat/razhari/store/
git commit -m "feat: add TransactionStore interface and ConcurrentHashMap implementation"
```

---

## Task 4: STAN Generator (TDD)

**Files:**
- Create: `src/main/java/id/redhat/razhari/util/StanGenerator.java`
- Test: `src/test/java/id/redhat/razhari/util/StanGeneratorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package id.redhat.razhari.util;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StanGeneratorTest {

    @Test
    void generatesZeroPaddedSixDigitString() {
        StanGenerator gen = new StanGenerator();
        String stan = gen.next();
        assertThat(stan).matches("\\d{6}");
    }

    @Test
    void incrementsMonotonically() {
        StanGenerator gen = new StanGenerator();
        int first = Integer.parseInt(gen.next());
        int second = Integer.parseInt(gen.next());
        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void wrapsAroundAt999999() {
        StanGenerator gen = new StanGenerator();
        for (int i = 0; i < 999_999; i++) gen.next();
        assertThat(gen.next()).isEqualTo("000001");
    }

    @Test
    void producesUniqueValuesUnderConcurrency() throws Exception {
        StanGenerator gen = new StanGenerator();
        Set<String> results = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 1000; i++) pool.submit(() -> results.add(gen.next()));
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(results).hasSize(1000);
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./mvnw test -Dtest=StanGeneratorTest
```

Expected: FAIL — `StanGenerator` does not exist

- [ ] **Step 3: Create StanGenerator**

```java
package id.redhat.razhari.util;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class StanGenerator {

    private final AtomicInteger counter = new AtomicInteger(0);

    public String next() {
        int value = counter.updateAndGet(i -> i >= 999_999 ? 1 : i + 1);
        return String.format("%06d", value);
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./mvnw test -Dtest=StanGeneratorTest
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/id/redhat/razhari/util/ \
        src/test/java/id/redhat/razhari/util/
git commit -m "feat: add thread-safe STAN generator (6-digit, wraps at 999999)"
```

---

## Task 5: j8583 MessageFactory Configuration

**Files:**
- Create: `src/main/resources/j8583.xml`
- Create: `src/main/java/id/redhat/razhari/config/MessageFactoryProducer.java`

> **Note on j8583:** The `camel-quarkus-iso8583` dependency brings j8583 (`com.solab:j8583`) as a transitive dependency. You do NOT add j8583 directly to `pom.xml` — the classes (`ISOMessage`, `MessageFactory`, `ConfigParser`) are available on the classpath automatically.

No separate unit test — the codec tests in Task 6 validate that the factory produces parseable messages.

- [ ] **Step 1: Create j8583.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE j8583-config PUBLIC "-//J8583//DTD CONFIG 1.0//EN"
    "http://j8583.sourceforge.net/j8583.dtd">
<j8583-config>

    <!-- No length header in j8583 — handled by our 2-byte Netty codec -->
    <header length="0"/>

    <!-- Authorization Request 0200 -->
    <parse type="0200">
        <field num="2"  type="LLVAR"/>
        <field num="3"  type="NUMERIC" length="6"/>
        <field num="4"  type="NUMERIC" length="12"/>
        <field num="7"  type="DATE10"/>
        <field num="11" type="NUMERIC" length="6"/>
        <field num="12" type="TIME"/>
        <field num="13" type="DATE4"/>
        <field num="41" type="ALPHA"   length="8"/>
        <field num="42" type="ALPHA"   length="15"/>
        <field num="49" type="NUMERIC" length="3"/>
    </parse>

    <!-- Authorization Response 0210 -->
    <parse type="0210">
        <field num="2"  type="LLVAR"/>
        <field num="3"  type="NUMERIC" length="6"/>
        <field num="4"  type="NUMERIC" length="12"/>
        <field num="7"  type="DATE10"/>
        <field num="11" type="NUMERIC" length="6"/>
        <field num="37" type="ALPHA"   length="12"/>
        <field num="38" type="ALPHA"   length="6"/>
        <field num="39" type="ALPHA"   length="2"/>
        <field num="41" type="ALPHA"   length="8"/>
        <field num="42" type="ALPHA"   length="15"/>
        <field num="49" type="NUMERIC" length="3"/>
    </parse>

    <!-- Reversal / Advice 0400 -->
    <parse type="0400">
        <field num="2"  type="LLVAR"/>
        <field num="4"  type="NUMERIC" length="12"/>
        <field num="11" type="NUMERIC" length="6"/>
        <field num="39" type="ALPHA"   length="2"/>
    </parse>

    <!-- Template defaults for 0200 -->
    <template type="0200">
        <field num="3" type="NUMERIC" length="6" value="000000"/>
    </template>

</j8583-config>
```

- [ ] **Step 2: Create MessageFactoryProducer**

```java
package id.redhat.razhari.config;

import com.solab.iso8583.ISOMessage;
import com.solab.iso8583.MessageFactory;
import com.solab.iso8583.parse.ConfigParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.io.IOException;

@ApplicationScoped
public class MessageFactoryProducer {

    @Produces
    @ApplicationScoped
    public MessageFactory<ISOMessage> messageFactory() throws IOException {
        MessageFactory<ISOMessage> factory = new MessageFactory<>();
        factory.setUseBinaryMessages(false);
        factory.setCharacterEncoding("UTF-8");
        factory.setAssignDate(true);
        // ConfigParser reads j8583.xml from the classpath and configures parse/template maps
        ConfigParser.configureFromClasspathConfig(factory, "j8583.xml");
        return factory;
    }
}
```

- [ ] **Step 3: Compile**

```bash
./mvnw compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/j8583.xml \
        src/main/java/id/redhat/razhari/config/MessageFactoryProducer.java
git commit -m "feat: configure j8583 MessageFactory with field definitions for 0200/0210/0400"
```

---

## Task 6: ISO 8583 Netty Codec (TDD)

**Files:**
- Create: `src/main/java/id/redhat/razhari/codec/ISO8583Decoder.java`
- Create: `src/main/java/id/redhat/razhari/codec/ISO8583Encoder.java`
- Test: `src/test/java/id/redhat/razhari/codec/ISO8583CodecTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package id.redhat.razhari.codec;

import id.redhat.razhari.config.MessageFactoryProducer;
import com.solab.iso8583.ISOMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ISO8583CodecTest {

    static MessageFactory<ISOMessage> factory;

    @BeforeAll
    static void init() throws Exception {
        factory = new MessageFactoryProducer().messageFactory();
    }

    @Test
    void encoderWritesTwoByteLengthPrefixThenPayload() throws Exception {
        ISOMessage msg = factory.newMessage(0x0200);
        msg.setValue(2, "4111111111111111", null, IsoType.LLVAR, 0);
        msg.setValue(11, "000001", null, IsoType.NUMERIC, 6);

        EmbeddedChannel ch = new EmbeddedChannel(new ISO8583Encoder());
        ch.writeOutbound(msg);
        ByteBuf buf = ch.readOutbound();

        int length = buf.readUnsignedShort();
        assertThat(buf.readableBytes()).isEqualTo(length);
    }

    @Test
    void decoderReconstructsISOMessage() throws Exception {
        ISOMessage original = factory.newMessage(0x0200);
        original.setValue(2, "4111111111111111", null, IsoType.LLVAR, 0);
        original.setValue(11, "000042", null, IsoType.NUMERIC, 6);

        // Encode first
        EmbeddedChannel encoder = new EmbeddedChannel(new ISO8583Encoder());
        encoder.writeOutbound(original);
        ByteBuf encoded = encoder.readOutbound();

        // Decode
        EmbeddedChannel decoder = new EmbeddedChannel(new ISO8583Decoder(factory));
        decoder.writeInbound(encoded);
        ISOMessage decoded = decoder.readInbound();

        assertThat(decoded).isNotNull();
        assertThat(decoded.getMti()).isEqualTo(0x0200);
        assertThat(decoded.getField(11).toString()).isEqualTo("000042");
    }

    @Test
    void decoderWaitsForFullFrame() throws Exception {
        ISOMessage original = factory.newMessage(0x0200);
        original.setValue(11, "000001", null, IsoType.NUMERIC, 6);

        EmbeddedChannel encoder = new EmbeddedChannel(new ISO8583Encoder());
        encoder.writeOutbound(original);
        ByteBuf full = encoder.readOutbound();

        // Send only first byte — decoder must not produce output yet
        EmbeddedChannel decoder = new EmbeddedChannel(new ISO8583Decoder(factory));
        decoder.writeInbound(full.readSlice(1).retain());
        assertThat((Object) decoder.readInbound()).isNull();

        // Send the rest — now it should decode
        decoder.writeInbound(full);
        assertThat((Object) decoder.readInbound()).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./mvnw test -Dtest=ISO8583CodecTest
```

Expected: FAIL — `ISO8583Decoder` and `ISO8583Encoder` do not exist

- [ ] **Step 3: Create ISO8583Encoder**

```java
package id.redhat.razhari.codec;

import com.solab.iso8583.ISOMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class ISO8583Encoder extends MessageToByteEncoder<ISOMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ISOMessage msg, ByteBuf out) throws Exception {
        byte[] payload = msg.writeData();
        out.writeShort(payload.length);
        out.writeBytes(payload);
    }
}
```

- [ ] **Step 4: Create ISO8583Decoder**

```java
package id.redhat.razhari.codec;

import com.solab.iso8583.ISOMessage;
import com.solab.iso8583.MessageFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class ISO8583Decoder extends ByteToMessageDecoder {

    private final MessageFactory<ISOMessage> factory;

    public ISO8583Decoder(MessageFactory<ISOMessage> factory) {
        this.factory = factory;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 2) return;
        int length = in.getUnsignedShort(in.readerIndex());
        if (in.readableBytes() < length + 2) return;
        in.skipBytes(2);
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        out.add(factory.parseMessage(bytes, 0));
    }
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
./mvnw test -Dtest=ISO8583CodecTest
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/id/redhat/razhari/codec/ \
        src/test/java/id/redhat/razhari/codec/
git commit -m "feat: add ISO8583 Netty codec (2-byte length-prefix framing)"
```

---

## Task 7: Channel Initializer Factories

**Files:**
- Create: `src/main/java/id/redhat/razhari/config/ISO8583ServerInitializer.java`
- Create: `src/main/java/id/redhat/razhari/config/ISO8583ClientInitializer.java`

These are thin wiring classes — validated by the route integration tests in Tasks 11–12.

- [ ] **Step 1: Create ISO8583ServerInitializer**

```java
package id.redhat.razhari.config;

import id.redhat.razhari.codec.ISO8583Decoder;
import id.redhat.razhari.codec.ISO8583Encoder;
import com.solab.iso8583.ISOMessage;
import com.solab.iso8583.MessageFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.component.netty.ServerInitializerFactory;
import org.apache.camel.component.netty.NettyConsumer;

@ApplicationScoped
@Named("iso8583ServerInitializer")
public class ISO8583ServerInitializer extends ServerInitializerFactory {

    @Inject
    MessageFactory<ISOMessage> messageFactory;

    @Override
    protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("decoder", new ISO8583Decoder(messageFactory));
        pipeline.addLast("encoder", new ISO8583Encoder());
    }

    @Override
    public ServerInitializerFactory createPipelineFactory(NettyConsumer consumer) {
        return this;
    }
}
```

- [ ] **Step 2: Create ISO8583ClientInitializer**

```java
package id.redhat.razhari.config;

import id.redhat.razhari.codec.ISO8583Decoder;
import id.redhat.razhari.codec.ISO8583Encoder;
import com.solab.iso8583.ISOMessage;
import com.solab.iso8583.MessageFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.component.netty.ClientInitializerFactory;
import org.apache.camel.component.netty.NettyProducer;

@ApplicationScoped
@Named("iso8583ClientInitializer")
public class ISO8583ClientInitializer extends ClientInitializerFactory {

    @Inject
    MessageFactory<ISOMessage> messageFactory;

    @Override
    protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("decoder", new ISO8583Decoder(messageFactory));
        pipeline.addLast("encoder", new ISO8583Encoder());
    }

    @Override
    public ClientInitializerFactory createPipelineFactory(NettyProducer producer) {
        return this;
    }
}
```

- [ ] **Step 3: Compile**

```bash
./mvnw compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/id/redhat/razhari/config/
git commit -m "feat: add Netty server/client initializer factories with ISO8583 codec pipeline"
```

---

## Task 8: JsonToIsoProcessor (TDD)

**Files:**
- Create: `src/main/java/id/redhat/razhari/processor/JsonToIsoProcessor.java`
- Test: `src/test/java/id/redhat/razhari/processor/JsonToIsoProcessorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package id.redhat.razhari.processor;

import id.redhat.razhari.config.MessageFactoryProducer;
import id.redhat.razhari.model.TransactionRequest;
import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import com.solab.iso8583.ISOMessage;
import com.solab.iso8583.MessageFactory;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JsonToIsoProcessorTest {

    static MessageFactory<ISOMessage> factory;
    static JsonToIsoProcessor processor;

    @BeforeAll
    static void init() throws Exception {
        factory = new MessageFactoryProducer().messageFactory();
        processor = new JsonToIsoProcessor(factory);
    }

    @Test
    void mapsTransactionRequestToISOMessage() throws Exception {
        TransactionState state = buildState("4111111111111111", 10000L, "840", "TERM0001", "MERCH001");
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(state);

        processor.process(exchange);

        ISOMessage msg = exchange.getIn().getBody(ISOMessage.class);
        assertThat(msg).isNotNull();
        assertThat(msg.getMti()).isEqualTo(0x0200);
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

        ISOMessage msg = exchange.getIn().getBody(ISOMessage.class);
        assertThat(msg.getField(11).toString()).isEqualTo("000099");
    }

    @Test
    void formatsAmountAsTwelveDigitNumeric() throws Exception {
        TransactionState state = buildState("4111111111111111", 1L, "840", "TERM0001", "MERCH001");
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(state);
        processor.process(exchange);

        ISOMessage msg = exchange.getIn().getBody(ISOMessage.class);
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
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./mvnw test -Dtest=JsonToIsoProcessorTest
```

Expected: FAIL — `JsonToIsoProcessor` does not exist

- [ ] **Step 3: Create JsonToIsoProcessor**

```java
package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionRequest;
import id.redhat.razhari.model.TransactionState;
import com.solab.iso8583.ISOMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

@ApplicationScoped
@Named("jsonToIsoProcessor")
public class JsonToIsoProcessor implements Processor {

    private final MessageFactory<ISOMessage> factory;

    @Inject
    public JsonToIsoProcessor(MessageFactory<ISOMessage> factory) {
        this.factory = factory;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        TransactionState state = exchange.getIn().getBody(TransactionState.class);
        TransactionRequest req = state.request;

        // Parse MTI string "0200" as hex integer: 0x0200 = 512
        int mti = Integer.parseInt(req.mti, 16);
        ISOMessage msg = factory.newMessage(mti);

        msg.setValue(2,  req.pan,                              null, IsoType.LLVAR,   0);
        msg.setValue(4,  String.format("%012d", req.amount),   null, IsoType.NUMERIC, 12);
        msg.setValue(11, state.stan,                           null, IsoType.NUMERIC, 6);
        msg.setValue(41, req.terminalId,                       null, IsoType.ALPHA,   8);
        msg.setValue(42, req.merchantId,                       null, IsoType.ALPHA,   15);
        msg.setValue(49, req.currency,                         null, IsoType.NUMERIC, 3);

        exchange.getIn().setBody(msg);
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./mvnw test -Dtest=JsonToIsoProcessorTest
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/id/redhat/razhari/processor/JsonToIsoProcessor.java \
        src/test/java/id/redhat/razhari/processor/JsonToIsoProcessorTest.java
git commit -m "feat: add JsonToIsoProcessor (TransactionState → ISOMessage field mapping)"
```

---

## Task 9: IsoToJsonProcessor (TDD)

**Files:**
- Create: `src/main/java/id/redhat/razhari/processor/IsoToJsonProcessor.java`
- Test: `src/test/java/id/redhat/razhari/processor/IsoToJsonProcessorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package id.redhat.razhari.processor;

import id.redhat.razhari.config.MessageFactoryProducer;
import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.InMemoryTransactionStore;
import com.solab.iso8583.ISOMessage;
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

    static MessageFactory<ISOMessage> factory;
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

        ISOMessage response = factory.newMessage(0x0210);
        response.setValue(11, "000042", null, IsoType.NUMERIC, 6);
        response.setValue(38, "AUTH01", null, IsoType.ALPHA, 6);
        response.setValue(39, "00",     null, IsoType.ALPHA, 2);

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
        ISOMessage unsolicited = factory.newMessage(0x0400);
        unsolicited.setValue(11, "000099", null, IsoType.NUMERIC, 6);
        unsolicited.setValue(39, "00",     null, IsoType.ALPHA, 2);

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

        ISOMessage response = factory.newMessage(0x0210);
        response.setValue(11, "000001", null, IsoType.NUMERIC, 6);
        response.setValue(39, "00",     null, IsoType.ALPHA, 2);

        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(response);
        processor.process(exchange);

        assertThat(store.findById("id-2").get().status).isEqualTo(TransactionStatus.PENDING);
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./mvnw test -Dtest=IsoToJsonProcessorTest
```

Expected: FAIL — `IsoToJsonProcessor` does not exist

- [ ] **Step 3: Create IsoToJsonProcessor**

```java
package id.redhat.razhari.processor;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.TransactionStore;
import com.solab.iso8583.ISOMessage;
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
        ISOMessage msg = exchange.getIn().getBody(ISOMessage.class);
        String stan = msg.getField(11) != null ? msg.getField(11).toString() : null;

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

    private Map<String, String> extractFields(ISOMessage msg) {
        Map<String, String> result = new HashMap<>();
        result.put("mti", String.format("%04x", msg.getMti()).toUpperCase());
        if (msg.getField(11) != null) result.put("stan",            msg.getField(11).toString());
        if (msg.getField(37) != null) result.put("retrievalRef",    msg.getField(37).toString());
        if (msg.getField(38) != null) result.put("authCode",        msg.getField(38).toString());
        if (msg.getField(39) != null) result.put("responseCode",    msg.getField(39).toString());
        return result;
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./mvnw test -Dtest=IsoToJsonProcessorTest
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/id/redhat/razhari/processor/IsoToJsonProcessor.java \
        src/test/java/id/redhat/razhari/processor/IsoToJsonProcessorTest.java
git commit -m "feat: add IsoToJsonProcessor (ISOMessage → TransactionStore update or RECEIVED event)"
```

---

## Task 10: REST Resource (TDD)

**Files:**
- Create: `src/main/java/id/redhat/razhari/rest/TransactionResource.java`
- Test: `src/test/java/id/redhat/razhari/rest/TransactionResourceTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package id.redhat.razhari.rest;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.TransactionStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@QuarkusTest
class TransactionResourceTest {

    @InjectMock
    TransactionStore store;

    @InjectMock
    ProducerTemplate producerTemplate;

    @Test
    void POST_returns_202_with_transaction_id() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "mti": "0200",
                  "pan": "4111111111111111",
                  "amount": 10000,
                  "currency": "840",
                  "terminalId": "TERM0001",
                  "merchantId": "MERCH001"
                }
                """)
        .when()
            .post("/transactions")
        .then()
            .statusCode(202)
            .body("transactionId", matchesPattern("[0-9a-f-]{36}"));

        verify(store).save(any(TransactionState.class));
        verify(producerTemplate).asyncSendBody(eq("direct:send-iso8583"), any(TransactionState.class));
    }

    @Test
    void GET_by_id_returns_200_with_state_when_found() {
        TransactionState state = pendingState("test-id", "000001");
        when(store.findById("test-id")).thenReturn(Optional.of(state));

        given()
        .when()
            .get("/transactions/test-id")
        .then()
            .statusCode(200)
            .body("transactionId", equalTo("test-id"))
            .body("status", equalTo("PENDING"));
    }

    @Test
    void GET_by_id_returns_404_when_not_found() {
        when(store.findById("unknown")).thenReturn(Optional.empty());

        given()
        .when()
            .get("/transactions/unknown")
        .then()
            .statusCode(404);
    }

    @Test
    void GET_status_returns_lightweight_status_object() {
        TransactionState state = pendingState("id-1", "000001");
        when(store.findById("id-1")).thenReturn(Optional.of(state));

        given()
        .when()
            .get("/transactions/id-1/status")
        .then()
            .statusCode(200)
            .body("status", equalTo("PENDING"))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue());
    }

    @Test
    void GET_list_returns_inbound_events_when_type_inbound() {
        TransactionState received = pendingState("id-1", "000001");
        received.status = TransactionStatus.RECEIVED;
        when(store.findByStatus(TransactionStatus.RECEIVED)).thenReturn(List.of(received));

        given()
            .queryParam("type", "inbound")
        .when()
            .get("/transactions")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1));
    }

    @Test
    void GET_list_returns_all_when_no_type_param() {
        when(store.findAll()).thenReturn(List.of(
            pendingState("id-1", "000001"),
            pendingState("id-2", "000002")
        ));

        given()
        .when()
            .get("/transactions")
        .then()
            .statusCode(200)
            .body("size()", equalTo(2));
    }

    private TransactionState pendingState(String id, String stan) {
        TransactionState s = new TransactionState();
        s.id = id;
        s.stan = stan;
        s.status = TransactionStatus.PENDING;
        s.createdAt = Instant.now();
        s.updatedAt = Instant.now();
        return s;
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./mvnw test -Dtest=TransactionResourceTest
```

Expected: FAIL — `TransactionResource` does not exist

- [ ] **Step 3: Create TransactionResource**

```java
package id.redhat.razhari.rest;

import id.redhat.razhari.model.*;
import id.redhat.razhari.store.TransactionStore;
import id.redhat.razhari.util.StanGenerator;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.camel.ProducerTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TransactionResource {

    @Inject TransactionStore store;
    @Inject ProducerTemplate producerTemplate;
    @Inject StanGenerator stanGenerator;

    @POST
    public Response submit(TransactionRequest request) {
        TransactionState state = new TransactionState();
        state.id = UUID.randomUUID().toString();
        state.stan = stanGenerator.next();
        state.status = TransactionStatus.PENDING;
        state.request = request;
        state.createdAt = Instant.now();
        state.updatedAt = Instant.now();

        store.save(state);
        producerTemplate.asyncSendBody("direct:send-iso8583", state);

        return Response.accepted(Map.of("transactionId", state.id)).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") String id) {
        return store.findById(id)
            .map(s -> Response.ok(toResponse(s)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/{id}/status")
    public Response getStatus(@PathParam("id") String id) {
        return store.findById(id)
            .map(s -> Response.ok(Map.of(
                "status",    s.status,
                "createdAt", s.createdAt,
                "updatedAt", s.updatedAt
            )).build())
            .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    public Response list(@QueryParam("type") String type) {
        List<TransactionState> states = "inbound".equals(type)
            ? store.findByStatus(TransactionStatus.RECEIVED)
            : store.findAll();
        List<TransactionResponse> responses = states.stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
        return Response.ok(responses).build();
    }

    private TransactionResponse toResponse(TransactionState s) {
        TransactionResponse r = new TransactionResponse();
        r.transactionId = s.id;
        r.status = s.status;
        r.createdAt = s.createdAt;
        r.updatedAt = s.updatedAt;
        r.result = s.result;
        return r;
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./mvnw test -Dtest=TransactionResourceTest
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/id/redhat/razhari/rest/ \
        src/test/java/id/redhat/razhari/rest/
git commit -m "feat: add TransactionResource REST endpoints with async submit + poll"
```

---

## Task 11: ISO8583 Send Route

**Files:**
- Create: `src/main/java/id/redhat/razhari/route/ISO8583SendRoute.java`

- [ ] **Step 1: Create ISO8583SendRoute**

```java
package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import org.apache.camel.builder.RouteBuilder;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

@ApplicationScoped
public class ISO8583SendRoute extends RouteBuilder {

    @Override
    public void configure() {
        onException(Exception.class)
            .handled(true)
            .process(exchange -> {
                TransactionState state = exchange.getProperty(
                    "originalState", TransactionState.class);
                if (state != null) {
                    state.status = TransactionStatus.FAILED;
                    state.updatedAt = Instant.now();
                }
            })
            .log("ISO8583 send failed: ${exception.message}");

        from("direct:send-iso8583")
            .process(exchange -> {
                // Stash original state before processor replaces the body
                exchange.setProperty("originalState",
                    exchange.getIn().getBody(TransactionState.class));
            })
            .process("jsonToIsoProcessor")
            .to("netty:tcp://{{camel.iso8583.switch.host}}:{{camel.iso8583.switch.port}}"
                + "?clientInitializerFactory=#iso8583ClientInitializer"
                + "&sync=true"
                + "&reuseChannel=true"
                + "&connectTimeout={{camel.iso8583.netty.connect-timeout}}")
            .process("isoToJsonProcessor");
    }
}
```

- [ ] **Step 2: Create test application.properties** (prevents real TCP connection in unit tests)

Create `src/test/resources/application.properties`:

```properties
# Override switch host to something unreachable in unit tests
# Routes are not started in plain @QuarkusTest by default if not triggered
quarkus.http.root-path=/api/v1
camel.iso8583.switch.host=localhost
camel.iso8583.switch.port=19999
camel.iso8583.server.port=19583
camel.iso8583.netty.connect-timeout=1000
camel.iso8583.transaction.timeout-ms=30000
camel.iso8583.transaction.cleanup-interval-ms=60000
camel.iso8583.netty.retry-attempts=1
camel.iso8583.netty.retry-delay=100
```

- [ ] **Step 3: Compile**

```bash
./mvnw compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/id/redhat/razhari/route/ISO8583SendRoute.java \
        src/test/resources/application.properties
git commit -m "feat: add ISO8583 Camel send route (direct:send-iso8583 → Netty TCP → switch)"
```

---

## Task 12: ISO8583 TCP Server Route

**Files:**
- Create: `src/main/java/id/redhat/razhari/route/ISO8583ServerRoute.java`

- [ ] **Step 1: Create ISO8583ServerRoute**

```java
package id.redhat.razhari.route;

import id.redhat.razhari.config.MessageFactoryProducer;
import id.redhat.razhari.model.TransactionStatus;
import com.solab.iso8583.ISOMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;

@ApplicationScoped
public class ISO8583ServerRoute extends RouteBuilder {

    @Inject
    MessageFactory<ISOMessage> messageFactory;

    @Override
    public void configure() {
        onException(Exception.class)
            .handled(true)
            .log("ISO8583 server error: ${exception.message}");

        from("netty:tcp://0.0.0.0:{{camel.iso8583.server.port}}"
             + "?serverInitializerFactory=#iso8583ServerInitializer"
             + "&sync=true"
             + "&keepAlive=true")
            .process(exchange -> {
                // Store original message before processor replaces body
                exchange.setProperty("incomingMsg",
                    exchange.getIn().getBody(ISOMessage.class));
            })
            .process("isoToJsonProcessor")
            .process(exchange -> {
                // Build acknowledgment response back to switch
                ISOMessage incoming = exchange.getProperty("incomingMsg", ISOMessage.class);
                int responseMti = incoming.getMti() + 0x0010; // 0200→0210, 0400→0410
                ISOMessage ack = messageFactory.newMessage(responseMti);
                if (incoming.getField(11) != null) {
                    ack.setValue(11, incoming.getField(11).toString(),
                        null, IsoType.NUMERIC, 6);
                }
                ack.setValue(39, "00", null, IsoType.ALPHA, 2);
                exchange.getIn().setBody(ack);
            });
    }
}
```

- [ ] **Step 2: Compile**

```bash
./mvnw compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/id/redhat/razhari/route/ISO8583ServerRoute.java
git commit -m "feat: add ISO8583 TCP server route for unsolicited switch messages"
```

---

## Task 13: Cleanup Timer Route (TDD)

**Files:**
- Create: `src/main/java/id/redhat/razhari/route/CleanupRoute.java`

The timer route logic is tested via the store — the route itself is validated by the functional test.

- [ ] **Step 1: Create CleanupRoute**

```java
package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.TransactionStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

@ApplicationScoped
public class CleanupRoute extends RouteBuilder {

    @Inject
    TransactionStore store;

    @ConfigProperty(name = "camel.iso8583.transaction.timeout-ms", defaultValue = "30000")
    long timeoutMs;

    @Override
    public void configure() {
        from("timer:cleanup?period={{camel.iso8583.transaction.cleanup-interval-ms}}")
            .process(exchange -> {
                Instant threshold = Instant.now().minusMillis(timeoutMs);
                store.findPendingOlderThan(threshold).forEach(state -> {
                    state.status = TransactionStatus.TIMEOUT;
                    state.updatedAt = Instant.now();
                    store.update(state);
                });
            })
            .log("Cleanup complete — timed out PENDING transactions older than ${camel.iso8583.transaction.timeout-ms}ms");
    }
}
```

- [ ] **Step 2: Compile**

```bash
./mvnw compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/id/redhat/razhari/route/CleanupRoute.java
git commit -m "feat: add timer-based cleanup route (PENDING → TIMEOUT after configurable TTL)"
```

---

## Task 14: Full application.properties

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Replace application.properties with full configuration**

```properties
# HTTP server
quarkus.http.port=8080
quarkus.http.host=0.0.0.0
quarkus.http.root-path=/api/v1

# ISO 8583 TCP Server (listening for switch-initiated messages)
camel.iso8583.server.port=9583

# ISO 8583 Switch connection (outbound)
camel.iso8583.switch.host=localhost
camel.iso8583.switch.port=8583
camel.iso8583.netty.connect-timeout=5000
camel.iso8583.netty.retry-attempts=3
camel.iso8583.netty.retry-delay=2000

# Transaction lifecycle
camel.iso8583.transaction.timeout-ms=30000
camel.iso8583.transaction.cleanup-interval-ms=60000

# Logging
quarkus.log.level=INFO
quarkus.log.category."id.redhat.razhari".level=DEBUG
```

- [ ] **Step 2: Verify app starts in dev mode**

```bash
./mvnw quarkus:dev
```

Expected: Application starts, REST endpoints registered at `http://localhost:8080/api/v1/transactions`

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "feat: complete application.properties with all configurable properties"
```

---

## Task 15: End-to-End Functional Test

**Files:**
- Create: `src/test/java/id/redhat/razhari/functional/MockISO8583Switch.java`
- Create: `src/test/java/id/redhat/razhari/functional/FullFlowIT.java`

- [ ] **Step 1: Create MockISO8583Switch**

```java
package id.redhat.razhari.functional;

import id.redhat.razhari.codec.ISO8583Decoder;
import id.redhat.razhari.codec.ISO8583Encoder;
import id.redhat.razhari.config.MessageFactoryProducer;
import com.solab.iso8583.ISOMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.io.IOException;

public class MockISO8583Switch {

    private final int port;
    private Channel serverChannel;
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private MessageFactory<ISOMessage> factory;

    public MockISO8583Switch(int port) throws IOException {
        this.port = port;
        this.factory = new MessageFactoryProducer().messageFactory();
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        MessageFactory<ISOMessage> f = factory;

        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new ISO8583Decoder(f));
                    ch.pipeline().addLast(new ISO8583Encoder());
                    ch.pipeline().addLast(new SimpleChannelInboundHandler<ISOMessage>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, ISOMessage msg)
                                throws Exception {
                            ISOMessage response = f.newMessage(0x0210);
                            if (msg.getField(11) != null) {
                                response.setValue(11, msg.getField(11).toString(),
                                    null, IsoType.NUMERIC, 6);
                            }
                            response.setValue(38, "AUTH01", null, IsoType.ALPHA, 6);
                            response.setValue(39, "00",     null, IsoType.ALPHA, 2);
                            ctx.writeAndFlush(response);
                        }
                    });
                }
            });

        serverChannel = bootstrap.bind(port).sync().channel();
    }

    public void stop() {
        if (serverChannel != null) serverChannel.close().syncUninterruptibly();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }
}
```

- [ ] **Step 2: Create FullFlowIT**

```java
package id.redhat.razhari.functional;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.awaitility.Awaitility.*;
import java.util.concurrent.TimeUnit;

@QuarkusIntegrationTest
class FullFlowIT {

    static MockISO8583Switch mockSwitch;

    @BeforeAll
    static void startMockSwitch() throws Exception {
        mockSwitch = new MockISO8583Switch(19999); // matches test application.properties
        mockSwitch.start();
    }

    @AfterAll
    static void stopMockSwitch() {
        mockSwitch.stop();
    }

    @Test
    void submitsTransactionAndPollsForCompletedResult() throws Exception {
        // Submit
        String transactionId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "mti": "0200",
                  "pan": "4111111111111111",
                  "amount": 10000,
                  "currency": "840",
                  "terminalId": "TERM0001",
                  "merchantId": "MERCH001"
                }
                """)
        .when()
            .post("/api/v1/transactions")
        .then()
            .statusCode(202)
            .extract().path("transactionId");

        // Poll until COMPLETED (switch responds asynchronously)
        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            String status = given()
                .get("/api/v1/transactions/" + transactionId + "/status")
                .then().extract().path("status");
            return "COMPLETED".equals(status);
        });

        // Verify full response
        given()
        .when()
            .get("/api/v1/transactions/" + transactionId)
        .then()
            .statusCode(200)
            .body("status",                equalTo("COMPLETED"))
            .body("result.responseCode",   equalTo("00"))
            .body("result.authCode",       equalTo("AUTH01"))
            .body("result.stan",           notNullValue());
    }

    @Test
    void returns404ForUnknownTransactionId() {
        given()
        .when()
            .get("/api/v1/transactions/does-not-exist")
        .then()
            .statusCode(404);
    }
}
```

- [ ] **Step 3: Add Awaitility dependency to pom.xml**

```xml
<dependency>
  <groupId>org.awaitility</groupId>
  <artifactId>awaitility</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 4: Run all unit tests**

```bash
./mvnw test
```

Expected: All unit tests pass

- [ ] **Step 5: Package and run integration test**

```bash
./mvnw verify
```

Expected: `FullFlowIT` passes — transaction is submitted, switch mock responds 0210, poll returns COMPLETED

- [ ] **Step 6: Commit**

```bash
git add src/test/java/id/redhat/razhari/functional/ pom.xml
git commit -m "feat: add end-to-end functional test with MockISO8583Switch"
```

---

## Running the Application

```bash
# Dev mode (hot reload, open browser at http://localhost:8080)
./mvnw quarkus:dev

# Run unit tests only
./mvnw test

# Run unit tests + integration tests
./mvnw verify

# Run a single unit test class
./mvnw test -Dtest=InMemoryTransactionStoreTest

# Run a single integration test
./mvnw verify -Dit.test=FullFlowIT

# Package as uber-jar
./mvnw package

# Native build (requires GraalVM 21+)
./mvnw package -Pnative
```
