package comp3011.assignment1;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


import static org.junit.jupiter.api.Assertions.assertEquals;

public class ShutdownStateTest {

    @Test
    void tryBeginShutdown_underConcurrentCalls_onlyOneSucceeds() throws InterruptedException, ExecutionException, TimeoutException {
        ShutdownState state = new ShutdownState();

        int numberOfThreads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    // All threads wait here, then get released at the same instant
                    // to maximise the chance of a genuine race condition.
                    startLatch.await();
                } catch (InterruptedException ignored) {}

                if (state.tryBeginShutdown()) {
                    successCount.incrementAndGet();
                }
            }));
        }

        readyLatch.await(); // wait until every thread is ready and blocked
        startLatch.countDown(); // release them all at once

        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        executor.shutdown();

        assertEquals(1, successCount.get(),
                "Expected exactly one thread to win the shutdown race, got " + successCount.get());
    }
}