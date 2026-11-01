package comp3011.assignment1;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AppStartTime startTime;
    private final ApplicationContext context;
    private final ShutdownState shutdownState;

    @Autowired
    public AdminController(AppStartTime startTime, ApplicationContext context, ShutdownState shutdownState) {
        this.startTime = startTime;
        this.context = context;
        this.shutdownState = shutdownState;
    }

    @GetMapping("/uptime")
    public ResponseEntity<?> getUptime() {
        Instant start = startTime.get();
        Instant now = Instant.now();
        double uptimeSeconds = Duration.between(start, now).toMillis() / 1000.0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("utcServerStart", start.toString());
        body.put("utcNow", now.toString());
        body.put("serverUptimeSeconds", uptimeSeconds);

        return ResponseEntity.ok(body);
    }

    @PostMapping("/shutdown")
    public ResponseEntity<?> shutdown() {
        if (!shutdownState.tryBeginShutdown()) {
            ErrorResponse error = new ErrorResponse(
                    409, "Conflict",
                    "Graceful shutdown is already in progress.",
                    "/api/v1/admin/shutdown"
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }

        Map<String, String> body = new LinkedHashMap<>();
        body.put("message", "Graceful shutdown requested.");

        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            int exitCode = org.springframework.boot.SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        }).start();

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }
}