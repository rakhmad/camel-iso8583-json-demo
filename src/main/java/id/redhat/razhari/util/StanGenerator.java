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
