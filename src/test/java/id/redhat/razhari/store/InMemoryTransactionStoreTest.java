package id.redhat.razhari.store;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(store.findById("id-1").isPresent());
        assertEquals(s, store.findById("id-1").get());
    }

    @Test
    void findsByStanAfterSave() {
        TransactionState s = state("id-1", "000001", TransactionStatus.PENDING);
        store.save(s);
        assertTrue(store.findByStan("000001").isPresent());
        assertEquals(s, store.findByStan("000001").get());
    }

    @Test
    void returnsEmptyForUnknownId() {
        assertTrue(store.findById("unknown").isEmpty());
    }

    @Test
    void returnsEmptyForUnknownStan() {
        assertTrue(store.findByStan("999999").isEmpty());
    }

    @Test
    void updatesExistingState() {
        TransactionState s = state("id-1", "000001", TransactionStatus.PENDING);
        store.save(s);
        s.status = TransactionStatus.COMPLETED;
        store.update(s);
        assertEquals(TransactionStatus.COMPLETED, store.findById("id-1").get().status);
    }

    @Test
    void findAllReturnsAllSavedStates() {
        store.save(state("id-1", "000001", TransactionStatus.PENDING));
        store.save(state("id-2", "000002", TransactionStatus.COMPLETED));
        assertEquals(2, store.findAll().size());
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
        assertEquals(1, results.size());
        assertEquals(old, results.get(0));
    }

    @Test
    void findsByStatus() {
        store.save(state("id-1", "000001", TransactionStatus.RECEIVED));
        store.save(state("id-2", "000002", TransactionStatus.PENDING));
        assertEquals(1, store.findByStatus(TransactionStatus.RECEIVED).size());
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
