package comp3011.assignment1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ConcurrencyTest {

    @LocalServerPort
    private int port;

    @Test
    void uptimeEndpoint_handles200ConcurrentRequests_withoutCrashing() throws InterruptedException {
        int numberOfRequests = 250;
        ExecutorService executor = Executors.newFixedThreadPool(50);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        RestTemplate restTemplate = new RestTemplate();

        String url = "http://localhost:" + port + "/api/v1/admin/uptime";

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < numberOfRequests; i++) {
            tasks.add(() -> {
                try {
                    ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
                return null;
            });
        }

        long startTime = System.currentTimeMillis();
        List<Future<Void>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();

        System.out.println("Concurrency test: " + successCount.get() + " succeeded, "
                + failureCount.get() + " failed, took " + duration + "ms");

        // No crashes: every request should have gotten a response, none should hang/timeout
        assertTrue(successCount.get() == numberOfRequests,
                "Expected all " + numberOfRequests + " requests to succeed, got " + successCount.get());

        // No significant delays: 250 lightweight requests should comfortably finish well under 30s
        assertTrue(duration < 15000,
                "Expected requests to complete within 15 seconds, took " + duration + "ms");
    }
}