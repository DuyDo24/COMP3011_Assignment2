package comp3011.assignment1;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class TranscriptionController {

    private final TranscriptionService transcriptionService;

    public TranscriptionController(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    @PostMapping("/transcribe")
    public ResponseEntity<?> transcribe(@RequestParam("audio") MultipartFile audio) {
        if (audio.isEmpty()) {
            ErrorResponse error = new ErrorResponse(
                    400, "Bad Request", "No audio file received.", "/api/v1/transcribe"
            );
            return ResponseEntity.badRequest().body(error);
        }

        try {
            String text = transcriptionService.transcribe(audio);
            return ResponseEntity.ok(text);
        } catch (IllegalStateException e) {
            e.printStackTrace();
            ErrorResponse error = new ErrorResponse(
                    500, "Internal Server Error", "Server is not configured correctly.", "/api/v1/transcribe"
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        } catch (Exception e) {
            e.printStackTrace();
            ErrorResponse error = new ErrorResponse(
                    500, "Internal Server Error", "Failed to transcribe audio.", "/api/v1/transcribe"
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}