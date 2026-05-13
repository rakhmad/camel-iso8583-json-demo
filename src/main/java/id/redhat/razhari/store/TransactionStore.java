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
