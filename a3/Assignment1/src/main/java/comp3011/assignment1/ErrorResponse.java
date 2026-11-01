package comp3011.assignment1;

import java.time.Instant;

public class ErrorResponse {
    public String timestamp;
    public int status;
    public String error;
    public String message;
    public String path;

    public ErrorResponse(int status, String error, String message, String path) {
        this.timestamp = Instant.now().toString();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
    }
}