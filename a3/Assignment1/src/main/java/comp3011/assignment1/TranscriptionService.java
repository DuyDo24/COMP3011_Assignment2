package comp3011.assignment1;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TranscriptionService {

	private static final String OPENAI_URL = "https://api.openai.com/v1/audio/transcriptions";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TokenStats tokenStats;

    public TranscriptionService(RestTemplate restTemplate, TokenStats tokenStats) {
        this.restTemplate = restTemplate;
        this.tokenStats = tokenStats;
    }

    public String transcribe(MultipartFile audioFile) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set on the server.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource fileResource = new ByteArrayResource(audioFile.getBytes()) {
            @Override
            public String getFilename() {
                return audioFile.getOriginalFilename() != null
                        ? audioFile.getOriginalFilename()
                        : "recording.webm";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        // Switched from whisper-1 to gpt-4o-transcribe:
        // - returns real token usage (whisper-1 does not)
        // - generally more accurate transcription
        body.add("model", "gpt-4o-transcribe");
        body.add("response_format", "json");

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                OPENAI_URL, HttpMethod.POST, requestEntity, String.class
        );

        JsonNode json = objectMapper.readTree(response.getBody());
        String transcribedText = json.get("text").asText();

        // Real token usage from OpenAI's response - no more estimation.
        JsonNode usage = json.get("usage");
        if (usage != null) {
            long inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asLong() : 0;
            long outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asLong() : 0;
            tokenStats.addInput(inputTokens);
            tokenStats.addOutput(outputTokens);
        }

        return transcribedText;
    }
}