/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Duy Do a1921156
 *
 * Drafted with help from Claude (Anthropic), then read and reviewed by the author.
 */
package comp3011;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fetches subtitles for a media file in the background and puts them in the {@link SubtitleStore}.
 *
 * <p>
 * The media's audio is cut into fixed 20 second chunks (chunk n covers n * 20 s up to (n + 1) * 20 s). Each chunk is
 * decoded by this class's own grabber, so the player's decoder is never touched, converted to 16 kHz mono 16-bit WAV
 * and posted to the web server. That is 16000 * 2 * 1 = 32000 bytes per second, so a chunk is 640000 bytes plus a
 * 44 byte header, well under Whisper's 25 MB limit.
 * </p>
 *
 * <p>
 * One thread does all the work. It looks at the playhead every 250 ms and fetches the first chunk that is missing from
 * the one being played and the next two, so seeking simply moves the playhead and the fetcher follows. A chunk that
 * fails (server down, bad key, media file gone) is retried after 5 seconds, and the player never waits for any of it.
 * </p>
 */
public final class SubtitleFetcher implements AutoCloseable {
    private static final String ENDPOINT = "http://localhost:8080/api/v1/subtitles";
    private static final int SAMPLE_RATE = 16_000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final long CHUNK_US = 20_000_000L;
    private static final int LOOKAHEAD_CHUNKS = 2;
    private static final int MIN_PCM_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE / 2; // Half a second of audio.
    private static final long RETRY_DELAY_MS = 5_000L;
    private static final long POLL_MS = 250L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File mediaFile;
    private final SubtitleStore store;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Set<Integer> done = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> retryAtMs = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object(); // Makes "closed" and "add cues" mutually exclusive.
    private final Thread thread;

    private volatile long playheadUs;
    private volatile boolean closed;

    private SubtitleFetcher(File mediaFile, SubtitleStore store, long playheadUs) {
        this.mediaFile = mediaFile;
        this.store = store;
        this.playheadUs = playheadUs;
        this.thread = new Thread(this::run, "subtitle-fetcher");
        this.thread.setDaemon(true);
    }

    public static SubtitleFetcher start(File mediaFile, SubtitleStore store, long playheadUs) {
        SubtitleFetcher fetcher = new SubtitleFetcher(mediaFile, store, playheadUs);
        fetcher.thread.start();
        return fetcher;
    }

    /** Tells the fetcher where playback is. Called from the JavaFX thread on every frame, so it must stay cheap. */
    public void setPlayheadUs(long playheadUs) {
        this.playheadUs = playheadUs;
    }

    /** After this returns, no more cues from this fetcher can reach the store. */
    @Override
    public void close() {
        synchronized (lifecycle) {
            closed = true;
        }
        thread.interrupt();
    }

    // ---- The fetching thread ----

    private void run() {
        while (!closed) {
            int chunk = nextChunkToFetch();
            if (chunk < 0) {
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }

            try {
                fetchChunk(chunk);
                if (closed) {
                    return;
                }
                done.add(chunk);
                retryAtMs.remove(chunk);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                retryAtMs.put(chunk, System.currentTimeMillis() + RETRY_DELAY_MS);
                System.out.println("Subtitles: chunk " + chunk + " failed (" + e + "), retrying in "
                        + RETRY_DELAY_MS / 1000 + " s");
            }
        }
    }

    // The first chunk, from the playing one onwards, that is not done and not waiting out a retry delay. Returns -1
    // when there is nothing to do, including while the media file is missing.
    private int nextChunkToFetch() {
        if (!mediaFile.isFile()) {
            return -1;
        }
        int current = (int) (playheadUs / CHUNK_US);
        long now = System.currentTimeMillis();
        for (int i = 0; i <= LOOKAHEAD_CHUNKS; i++) {
            int chunk = current + i;
            if (!done.contains(chunk) && retryAtMs.getOrDefault(chunk, 0L) <= now) {
                return chunk;
            }
        }
        return -1;
    }

    private void fetchChunk(int chunk) throws Exception {
        long startUs = chunk * CHUNK_US;
        long endUs = startUs + CHUNK_US;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long firstSampleUs = -1;

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaFile)) {
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.setAudioChannels(1);
            grabber.setSampleRate(SAMPLE_RATE);
            grabber.start();
            if (!grabber.hasAudio()) {
                return; // Nothing to transcribe, so the chunk counts as done.
            }
            if (startUs > 0) {
                grabber.setTimestamp(startUs);
            }

            Frame frame;
            while (!closed && (frame = grabber.grabSamples()) != null) {
                long timestampUs = grabber.getTimestamp();
                if (timestampUs >= endUs) {
                    break;
                }
                if (timestampUs < startUs) {
                    continue; // Seeking can land a little before the wanted time.
                }
                if (frame.samples == null || frame.samples.length == 0
                        || !(frame.samples[0] instanceof ShortBuffer samples)) {
                    continue;
                }
                if (firstSampleUs < 0) {
                    firstSampleUs = timestampUs;
                }

                ShortBuffer source = samples.duplicate();
                ByteBuffer out = ByteBuffer.allocate(source.remaining() * BYTES_PER_SAMPLE)
                        .order(ByteOrder.LITTLE_ENDIAN);
                out.asShortBuffer().put(source);
                pcm.writeBytes(out.array());
            }
        }

        byte[] pcmBytes = pcm.toByteArray();
        if (closed || pcmBytes.length < MIN_PCM_BYTES) {
            return; // Closed, or past the end of the media.
        }

        // The offset is where the first kept sample really is, so cue times stay right even if the seek was inexact.
        double offsetSeconds = firstSampleUs / 1_000_000.0;
        List<SubtitleBuilder.Segment> segments = transcribe(wav(pcmBytes), offsetSeconds);
        List<SubtitleCue> cues = SubtitleBuilder.build(segments);

        synchronized (lifecycle) {
            if (!closed) {
                store.addCues(cues);
            }
        }
        System.out.printf("Subtitles: chunk %d (%d s to %d s) gave %d cues%n",
                chunk, startUs / 1_000_000L, endUs / 1_000_000L, cues.size());
    }

    // ---- Talking to the web server ----

    private List<SubtitleBuilder.Segment> transcribe(byte[] wavBytes, double offsetSeconds) throws Exception {
        String boundary = "----subtitles" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeText(body, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"offsetSeconds\"\r\n\r\n"
                + String.format(Locale.ROOT, "%.3f", offsetSeconds) + "\r\n");
        writeText(body, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"audio\"; filename=\"chunk.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n");
        body.writeBytes(wavBytes);
        writeText(body, "\r\n--" + boundary + "--\r\n");

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("server replied " + response.statusCode() + ": " + response.body());
        }

        List<SubtitleBuilder.Segment> segments = new ArrayList<>();
        for (JsonNode s : MAPPER.readTree(response.body()).path("segments")) {
            List<SubtitleBuilder.Word> words = new ArrayList<>();
            for (JsonNode w : s.path("words")) {
                words.add(new SubtitleBuilder.Word(
                        w.path("text").asText(), w.path("startSeconds").asDouble(), w.path("endSeconds").asDouble()));
            }
            segments.add(new SubtitleBuilder.Segment(
                    s.path("text").asText(),
                    s.path("startSeconds").asDouble(),
                    s.path("endSeconds").asDouble(),
                    s.path("avgLogProb").asDouble(),
                    s.path("noSpeechProb").asDouble(),
                    words));
        }
        return segments;
    }

    private static void writeText(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    // A 44 byte WAV header followed by the raw samples: mono, 16 bit, little endian.
    private static byte[] wav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        b.putShort((short) 1); // PCM
        b.putShort((short) 1); // Channels
        b.putInt(SAMPLE_RATE);
        b.putInt(SAMPLE_RATE * BYTES_PER_SAMPLE); // Bytes per second: 16000 * 2 * 1 = 32000
        b.putShort((short) BYTES_PER_SAMPLE); // Bytes per sample frame
        b.putShort((short) 16); // Bits per sample
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        b.put(pcm);
        return b.array();
    }
}