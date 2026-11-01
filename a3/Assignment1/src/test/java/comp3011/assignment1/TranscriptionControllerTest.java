package comp3011.assignment1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest
@AutoConfigureMockMvc
public class TranscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RestTemplate restTemplate;

    @Test
    void transcribe_withValidAudio_returnsTranscribedText() throws Exception {
        String fakeOpenAiResponse = """
            {
              "text": "hello world",
              "usage": { "input_tokens": 10, "output_tokens": 5 }
            }
            """;

        when(restTemplate.exchange(
                any(String.class),
                any(HttpMethod.class),
                any(),
                (Class<String>) any(Class.class)
        )).thenReturn(new ResponseEntity<>(fakeOpenAiResponse, HttpStatus.OK));

        MockMultipartFile audio = new MockMultipartFile(
                "audio", "test.webm", "audio/webm", "fake-audio-bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/transcribe").file(audio))
                .andExpect(status().isOk())
                .andExpect(content().string("hello world"));
    }

    @Test
    void transcribe_withNoAudioFile_returnsBadRequest() throws Exception {
        MockMultipartFile emptyAudio = new MockMultipartFile(
                "audio", "empty.webm", "audio/webm", new byte[0]
        );

        mockMvc.perform(multipart("/api/v1/transcribe").file(emptyAudio))
                .andExpect(status().isBadRequest());
    }
}