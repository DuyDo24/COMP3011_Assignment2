/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Duy Do a1921156
 *
 * Drafted with help from Claude (Anthropic), then read and reviewed by the author.
 */
package comp3011;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Remembers what the web server said about each stretch of audio, so the same sound is never paid for twice.
 *
 * <p>
 * The cache is keyed by <b>what the audio sounds like</b>, never by file name, size or date. Each 20 second chunk is
 * reduced to a loudness envelope: one number (decibels) per 50 ms. When a chunk is about to be sent to the server, its
 * envelope is slid along the envelopes already remembered and compared by normalised cross-correlation. A score near
 * 1 means "this is the same sound", and the slide that scored best says exactly how far the new media is shifted
 * relative to the remembered one. That one mechanism gives all three kinds of reuse:
 * </p>
 * <ul>
 * <li>the same media played again: shift 0;</li>
 * <li>the same media under another name: still shift 0, because the name is never looked at;</li>
 * <li>an excerpt cut out of remembered media: a shift equal to where the excerpt starts. Cached cues are moved by
 * that shift, and the excerpt's 20 second chunks need not line up with the original's.</li>
 * </ul>
 *
 * <p>
 * An envelope survives re-encoding, a change of volume and a small amount of noise, because it follows the loudness
 * contour of the sound rather than its exact samples. A chunk is served from the cache only when everything it needs
 * (after shifting) was really transcribed before. Otherwise it goes to the server as usual and the answer is
 * remembered, so a media that is only partly remembered costs only the missing parts.
 * </p>
 *
 * <p>
 * Each remembered media is one small JSON file that is rewritten (atomically) after every chunk, so nothing is lost
 * if the player is killed. A file is only trusted while the server's usage counter has not gone backwards since it
 * was written: a counter that went down means the server was restarted, and the cache belongs to an earlier run.
 * Every failure here is survivable: the caller simply falls back to asking the server.
 * </p>
 */
public final class SubtitleCache {
    /** Samples of 16 kHz audio per envelope value: 50 ms. */
    static final int HOP_SAMPLES = 800;
    static final long HOP_US = 50_000L;

    private static final int FORMAT = 1;
    private static final double MATCH_SCORE = 0.90; // To adopt a match found by searching.
    private static final double VERIFY_SCORE = 0.70; // To keep a match found earlier in the same media.
    private static final float FLAT_SPREAD_DB = 1.0f; // A chunk this steady carries no pattern to match on.
    private static final float FLOOR_DB = 20.0f; // Below this it is only quantisation noise and differs between encodes.
    private static final int MIN_OVERLAP_HOPS = 100; // 5 seconds of agreeing audio.
    private static final long COVER_TOLERANCE_US = 250_000L;
    private static final long MERGE_GAP_US = 250_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final SubtitleCache INSTANCE = new SubtitleCache();

    /** One media (or excerpt) already seen: its envelope, which stretches are transcribed, and the transcription. */
    static final class Entry {
        final String id;
        double usageAtSave = -1;
        float[] values = new float[0]; // Envelope by hop index, NaN where unknown.
        int base; // Hop index of values[0]. May be negative when an excerpt precedes the first remembered audio.
        final List<long[]> coverage = new ArrayList<>(); // Merged [fromUs, toUs] stretches that are transcribed.
        final List<ObjectNode> segments = new ArrayList<>(); // Server segments, in this entry's own timeline.

        Entry(String id) {
            this.id = id;
        }

        boolean hasData() {
            return !coverage.isEmpty();
        }

        void ensure(int firstIndex, int lastIndex) {
            if (values.length == 0) {
                base = firstIndex;
                values = new float[lastIndex - firstIndex + 1];
                Arrays.fill(values, Float.NaN);
                return;
            }
            int newBase = Math.min(base, firstIndex);
            int newEnd = Math.max(base + values.length - 1, lastIndex);
            if (newBase == base && newEnd == base + values.length - 1) {
                return;
            }
            float[] grown = new float[newEnd - newBase + 1];
            Arrays.fill(grown, Float.NaN);
            System.arraycopy(values, 0, grown, base - newBase, values.length);
            values = grown;
            base = newBase;
        }

        void putEnvelope(int firstIndex, float[] envelope) {
            if (envelope.length == 0) {
                return;
            }
            ensure(firstIndex, firstIndex + envelope.length - 1);
            for (int i = 0; i < envelope.length; i++) {
                int at = firstIndex + i - base;
                if (Float.isNaN(values[at])) { // First writer wins, so remembered audio is never disturbed.
                    values[at] = envelope[i];
                }
            }
        }

        void addCoverage(long fromUs, long toUs) {
            coverage.add(new long[] { fromUs, toUs });
            coverage.sort(Comparator.comparingLong(iv -> iv[0]));
            List<long[]> merged = new ArrayList<>();
            for (long[] iv : coverage) {
                long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && iv[0] <= last[1] + MERGE_GAP_US) {
                    last[1] = Math.max(last[1], iv[1]);
                } else {
                    merged.add(new long[] { iv[0], iv[1] });
                }
            }
            coverage.clear();
            coverage.addAll(merged);
        }

        boolean covers(long fromUs, long toUs) {
            for (long[] iv : coverage) {
                if (iv[0] <= fromUs + COVER_TOLERANCE_US && iv[1] >= toUs - COVER_TOLERANCE_US) {
                    return true;
                }
            }
            return false;
        }

        boolean covered(double pointUs) {
            for (long[] iv : coverage) {
                if (pointUs >= iv[0] && pointUs < iv[1]) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Normalised cross-correlation between a chunk's envelope and this entry's envelope when the chunk's hop
         * {@code k} sits on this entry's hop {@code k + lag}. Only hops known on both sides count, and their number
         * goes into {@code count[0]}. Returns -1 when there is nothing to compare or one side is flat.
         */
        double score(float[] x, int startHop, int lag, int[] count) {
            double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            int n = 0;
            for (int i = 0; i < x.length; i++) {
                int j = startHop + i + lag - base;
                if (j < 0 || j >= values.length) {
                    continue;
                }
                float y = values[j];
                if (y != y) {
                    continue;
                }
                double a = x[i];
                sx += a;
                sy += y;
                sxx += a * a;
                syy += (double) y * y;
                sxy += a * y;
                n++;
            }
            count[0] = n;
            if (n < 2) {
                return -1;
            }
            double covXY = sxy - sx * sy / n;
            double varX = sxx - sx * sx / n;
            double varY = syy - sy * sy / n;
            if (varX < 1e-6 || varY < 1e-6) {
                return -1;
            }
            return covXY / Math.sqrt(varX * varY);
        }

        /** Tries every shift that leaves enough overlap; returns the best score and puts its shift in lagOut[0]. */
        double bestLag(float[] x, int startHop, int minOverlap, int[] lagOut) {
            int low = base - startHop - x.length + minOverlap;
            int high = base + values.length - startHop - minOverlap;
            double best = -2;
            int[] count = new int[1];
            for (int lag = low; lag <= high; lag++) {
                double s = score(x, startHop, lag, count);
                if (count[0] >= minOverlap && s > best) {
                    best = s;
                    lagOut[0] = lag;
                }
            }
            return best;
        }
    }

    /** Which remembered media a playing media is currently lined up with, and by how many hops it is shifted. */
    static final class Placement {
        private final Entry entry;
        private final int lagHops;

        Placement(Entry entry, int lagHops) {
            this.entry = entry;
            this.lagHops = lagHops;
        }

        long shiftUs() {
            return lagHops * HOP_US;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private Path directory;
    private boolean loaded;

    private SubtitleCache() {
    }

    public static SubtitleCache shared() {
        return INSTANCE;
    }

    public synchronized boolean isLoaded() {
        return loaded;
    }

    /**
     * Reads the remembered media from disk, once. {@code serverUsage} is the server's usage counter now, or negative
     * if it could not be read. A file written when the counter was higher than it is now is from an earlier server
     * run, so it is deleted instead of trusted.
     */
    public synchronized void ensureLoaded(double serverUsage) {
        if (loaded) {
            return;
        }
        loaded = true;
        directory = chooseDirectory();
        if (directory == null) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                try {
                    Entry entry = read(file);
                    if (serverUsage >= 0 && entry.usageAtSave > serverUsage + 0.5) {
                        Files.deleteIfExists(file);
                        continue;
                    }
                    entries.add(entry);
                } catch (Exception unreadable) {
                    // A damaged or foreign file is simply not used.
                }
            }
        } catch (IOException e) {
            // Nothing remembered; the cache starts empty.
        }
    }

    // ---- Matching ----

    /** One loudness value (dB) per 50 ms of little-endian 16-bit mono audio. */
    static float[] envelope(byte[] pcm) {
        int hops = pcm.length / 2 / HOP_SAMPLES;
        float[] out = new float[hops];
        for (int h = 0; h < hops; h++) {
            double sum = 0;
            int from = h * HOP_SAMPLES * 2;
            for (int s = 0; s < HOP_SAMPLES; s++) {
                int at = from + s * 2;
                short v = (short) ((pcm[at] & 0xFF) | (pcm[at + 1] << 8));
                sum += (double) v * v;
            }
            out[h] = (float) Math.max(FLOOR_DB, 10.0 * Math.log10(1.0 + sum / HOP_SAMPLES));
        }
        return out;
    }

    static int hopIndex(long timeUs) {
        return (int) Math.round(timeUs / (double) HOP_US);
    }

    private static double spread(float[] x) {
        if (x.length == 0) {
            return 0;
        }
        double mean = 0;
        for (float v : x) {
            mean += v;
        }
        mean /= x.length;
        double sum = 0;
        for (float v : x) {
            sum += (v - mean) * (v - mean);
        }
        return Math.sqrt(sum / x.length);
    }

    /**
     * Lines a chunk up with remembered media. {@code current} is where the previous chunk of the same media was
     * placed (or null). A media is one continuous piece of sound, so its placement is kept as long as the new chunk
     * still agrees with it; only when it does not (or there is none yet) is every remembered media searched.
     * If nothing matches, a fresh entry is started for this media.
     */
    public synchronized Placement place(float[] x, int startHop, Placement current) {
        boolean flat = spread(x) < FLAT_SPREAD_DB;
        int[] count = new int[1];

        if (current != null) {
            double s = current.entry.score(x, startHop, current.lagHops, count);
            boolean cannotCheck = count[0] < x.length / 2; // Mostly new territory for that entry.
            if (cannotCheck || flat || s >= VERIFY_SCORE) {
                return current;
            }
        }

        if (!flat && x.length > 0) {
            int minOverlap = Math.min(MIN_OVERLAP_HOPS, Math.max(1, x.length / 2));
            Entry bestEntry = null;
            int bestLag = 0;
            double best = -2;
            int[] lag = new int[1];
            for (Entry e : entries) {
                if (!e.hasData()) {
                    continue;
                }
                double s = e.bestLag(x, startHop, minOverlap, lag);
                if (s > best) {
                    best = s;
                    bestEntry = e;
                    bestLag = lag[0];
                }
            }
            if (bestEntry != null && best >= MATCH_SCORE) {
                return new Placement(bestEntry, bestLag);
            }
        }

        Entry fresh = new Entry(UUID.randomUUID().toString());
        entries.add(fresh);
        return new Placement(fresh, 0);
    }

    // ---- Reading and writing the remembered transcription ----

    /**
     * The server segments for a chunk, in the playing media's own timeline, or null if any of the audio (shifted to
     * the remembered media's timeline) was never transcribed. A segment belongs to the chunk whose nominal span
     * holds its midpoint, so neighbouring chunks never both claim it.
     */
    public synchronized ArrayNode lookup(Placement p, long nominalStartUs, long nominalEndUs, long dataStartUs,
            long dataEndUs) {
        long shift = p.shiftUs();
        if (!p.entry.covers(dataStartUs + shift, dataEndUs + shift)) {
            return null;
        }
        ArrayNode out = MAPPER.createArrayNode();
        for (ObjectNode seg : p.entry.segments) {
            double midUs = (seg.path("startSeconds").asDouble() + seg.path("endSeconds").asDouble()) / 2.0 * 1e6;
            if (midUs >= nominalStartUs + shift && midUs < nominalEndUs + shift) {
                out.add(shifted(seg, -shift / 1e6));
            }
        }
        return out;
    }

    /** Remembers what the server said about a chunk, and writes it to disk. {@code segments} are in media time. */
    public synchronized void store(Placement p, JsonNode segments, long dataStartUs, long dataEndUs, float[] x,
            int startHop, double serverUsage) {
        Entry e = p.entry;
        long shift = p.shiftUs();
        for (JsonNode s : segments) {
            if (!(s instanceof ObjectNode seg)) {
                continue;
            }
            double midUs = ((seg.path("startSeconds").asDouble() + seg.path("endSeconds").asDouble()) / 2.0
                    + shift / 1e6) * 1e6;
            if (!e.covered(midUs)) { // Only what was not already remembered, so overlaps never double up.
                e.segments.add(shifted(seg, shift / 1e6));
            }
        }
        e.addCoverage(dataStartUs + shift, dataEndUs + shift);
        e.putEnvelope(startHop + p.lagHops, x);
        if (serverUsage >= 0) {
            e.usageAtSave = serverUsage;
        }
        write(e);
    }

    private static ObjectNode shifted(ObjectNode segment, double deltaSeconds) {
        ObjectNode copy = segment.deepCopy();
        add(copy, "startSeconds", deltaSeconds);
        add(copy, "endSeconds", deltaSeconds);
        JsonNode words = copy.get("words");
        if (words != null && words.isArray()) {
            for (JsonNode w : words) {
                if (w instanceof ObjectNode word) {
                    add(word, "startSeconds", deltaSeconds);
                    add(word, "endSeconds", deltaSeconds);
                }
            }
        }
        return copy;
    }

    private static void add(ObjectNode node, String field, double delta) {
        if (node.has(field)) {
            node.put(field, node.get(field).asDouble() + delta);
        }
    }

    // ---- Disk ----

    // The project folder first (so a cache never outlives the job it was made in), then the temp folder.
    private static Path chooseDirectory() {
        String[] bases = { System.getProperty("user.dir"), System.getProperty("java.io.tmpdir") };
        String[] names = { ".subtitle-cache", "comp3011-subtitle-cache" };
        for (int i = 0; i < bases.length; i++) {
            if (bases[i] == null) {
                continue;
            }
            try {
                Path path = Path.of(bases[i], names[i]);
                Files.createDirectories(path);
                if (Files.isWritable(path)) {
                    return path;
                }
            } catch (Exception ignored) {
                // Try the next place.
            }
        }
        return null;
    }

    private void write(Entry e) {
        if (directory == null) {
            return;
        }
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("format", FORMAT);
            root.put("id", e.id);
            root.put("usageAtSave", e.usageAtSave);

            ArrayNode coverage = root.putArray("coverage");
            for (long[] iv : e.coverage) {
                coverage.addArray().add(iv[0]).add(iv[1]);
            }

            ArrayNode envelope = root.putArray("envelope");
            int i = 0;
            while (i < e.values.length) {
                if (Float.isNaN(e.values[i])) {
                    i++;
                    continue;
                }
                int j = i;
                ObjectNode run = envelope.addObject();
                run.put("start", e.base + i);
                ArrayNode values = run.putArray("values");
                while (j < e.values.length && !Float.isNaN(e.values[j])) {
                    values.add(Math.round(e.values[j] * 100f) / 100.0);
                    j++;
                }
                i = j;
            }

            ArrayNode segments = root.putArray("segments");
            e.segments.forEach(segments::add);

            Path target = directory.resolve(e.id + ".json");
            Path temp = directory.resolve(e.id + ".tmp");
            MAPPER.writeValue(temp.toFile(), root);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException problem) {
            System.out.println("Subtitles: could not save the cache (" + problem + ")");
        }
    }

    private Entry read(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        if (root.path("format").asInt() != FORMAT) {
            throw new IOException("unknown cache format");
        }
        Entry e = new Entry(root.path("id").asText());
        e.usageAtSave = root.path("usageAtSave").asDouble(-1);
        for (JsonNode iv : root.path("coverage")) {
            e.coverage.add(new long[] { iv.get(0).asLong(), iv.get(1).asLong() });
        }
        for (JsonNode run : root.path("envelope")) {
            int start = run.path("start").asInt();
            JsonNode values = run.path("values");
            float[] data = new float[values.size()];
            for (int k = 0; k < data.length; k++) {
                data[k] = (float) values.get(k).asDouble();
            }
            e.putEnvelope(start, data);
        }
        for (JsonNode s : root.path("segments")) {
            if (s instanceof ObjectNode seg) {
                e.segments.add(seg);
            }
        }
        return e;
    }
}