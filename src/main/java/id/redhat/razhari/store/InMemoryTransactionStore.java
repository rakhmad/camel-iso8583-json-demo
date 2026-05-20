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
        if (state.stan != null) { stanToId.put(state.stan, state.id); }
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
