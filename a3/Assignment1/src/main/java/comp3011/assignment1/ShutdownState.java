package comp3011.assignment1;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ShutdownState {
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * Attempts to claim the shutdown. Returns true if this call is the
     * first to claim it (should proceed), false if shutdown was already
     * in progress (should be rejected with 409).
     */
    public boolean tryBeginShutdown() {
        return shuttingDown.compareAndSet(false, true);
    }

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }
}