package comp3011.assignment1;

import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class AppStartTime {
    private final Instant startTime = Instant.now();

    public Instant get() {
        return startTime;
    }
}