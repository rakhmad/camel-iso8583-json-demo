package id.redhat.razhari.util;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StanGeneratorTest {

    @Test
    void generatesZeroPaddedSixDigitString() {
        StanGenerator gen = new StanGenerator();
        String stan = gen.next();
        assertTrue(stan.matches("\\d{6}"), "STAN should be 6 digits: " + stan);
    }

    @Test
    void incrementsMonotonically() {
        StanGenerator gen = new StanGenerator();
        int first = Integer.parseInt(gen.next());
        int second = Integer.parseInt(gen.next());
        assertEquals(first + 1, second);
    }

    @Test
    void wrapsAroundAt999999() {
        StanGenerator gen = new StanGenerator();
        for (int i = 0; i < 999_999; i++) gen.next();
        assertEquals("000001", gen.next());
    }

    @Test
    void producesUniqueValuesUnderConcurrency() throws Exception {
        StanGenerator gen = new StanGenerator();
        Set<String> results = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 1000; i++) pool.submit(() -> results.add(gen.next()));
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        assertEquals(1000, results.size());
    }
}
