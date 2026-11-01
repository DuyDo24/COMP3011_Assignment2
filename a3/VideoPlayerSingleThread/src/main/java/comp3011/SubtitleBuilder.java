/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Duy Do a1921156
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a speech-to-text transcript (segments made of timed words) into subtitle cues that follow the assignment's
 * guidelines:
 * <ul>
 * <li>at most {@value #MAX_LINE_CHARS} characters per line and at most two lines, preferring one;</li>
 * <li>a phrase or sentence appears when it is spoken and stays for at least one second and at most seven;</li>
 * <li>new subtitles start after natural pauses and sentence ends;</li>
 * <li>a line break, when needed, comes after punctuation where possible;</li>
 * <li>segments that are probably not speech are dropped.</li>
 * </ul>
 *
 * <p>
 * This class is pure: it takes numbers and strings and returns cues. It does no I/O and touches no shared state, so it
 * can be called from any thread and tested without a server or a video.
 * </p>
 */
public final class SubtitleBuilder {
    public static final int MAX_LINE_CHARS = 42;
    public static final int MAX_LINES = 2;

    static final double NO_SPEECH_PROB_LIMIT = 0.6;
    static final double AVG_LOG_PROB_LIMIT = -1.0;
    static final double PAUSE_BREAK_SECONDS = 0.6;
    static final double MIN_CUE_SECONDS = 1.0;
    static final double MAX_CUE_SECONDS = 7.0;
    static final double HOLD_AFTER_LAST_WORD_SECONDS = 0.3;

    private static final long US_PER_SECOND = 1_000_000L;

    /** One word with its time in seconds from the start of the media. */
    public record Word(String text, double startSeconds, double endSeconds) {
    }

    /** One transcript segment as returned by the speech-to-text service. */
    public record Segment(
            String text,
            double startSeconds,
            double endSeconds,
            double avgLogProb,
            double noSpeechProb,
            List<Word> words) {
    }

    private SubtitleBuilder() {
    }

    /** The brief's rule: a segment is not shown when both measures say it is probably not speech. */
    public static boolean isSpeech(Segment segment) {
        return !(segment.noSpeechProb() > NO_SPEECH_PROB_LIMIT && segment.avgLogProb() < AVG_LOG_PROB_LIMIT);
    }

    /** Builds the cues for the given segments, which must be in time order. */
    public static List<SubtitleCue> build(List<Segment> segments) {
        List<TimedPhrase> phrases = new ArrayList<>();
        for (Segment segment : segments) {
            if (isSpeech(segment)) {
                splitIntoPhrases(wordsOf(segment), phrases);
            }
        }
        return toCues(phrases);
    }

    // ---- Words and phrases ----

    // The service normally gives word timings. If a segment has none, spread its words evenly across the segment so
    // the subtitle still appears at about the right time.
    private static List<Word> wordsOf(Segment segment) {
        if (segment.words() != null && !segment.words().isEmpty()) {
            return segment.words();
        }
        String[] pieces = segment.text().trim().split("\\s+");
        List<Word> words = new ArrayList<>();
        if (pieces.length == 1 && pieces[0].isEmpty()) {
            return words;
        }
        double step = (segment.endSeconds() - segment.startSeconds()) / pieces.length;
        for (int i = 0; i < pieces.length; i++) {
            words.add(new Word(pieces[i], segment.startSeconds() + i * step, segment.startSeconds() + (i + 1) * step));
        }
        return words;
    }

    private record TimedPhrase(String text, double startSeconds, double endSeconds) {
    }

    // A phrase ends at the end of a sentence, at a pause in the speech, or when adding the next word would make it too
    // long to show in two lines or for too many seconds.
    private static void splitIntoPhrases(List<Word> words, List<TimedPhrase> out) {
        StringBuilder text = new StringBuilder();
        double start = 0;
        double end = 0;

        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            String piece = word.text().trim();
            if (piece.isEmpty()) {
                continue;
            }

            if (text.length() > 0) {
                boolean pause = word.startSeconds() - end >= PAUSE_BREAK_SECONDS;
                boolean tooLong = text.length() + 1 + piece.length() > MAX_LINE_CHARS * MAX_LINES;
                boolean tooSlow = word.endSeconds() - start > MAX_CUE_SECONDS - HOLD_AFTER_LAST_WORD_SECONDS;
                if (pause || tooLong || tooSlow) {
                    out.add(new TimedPhrase(text.toString(), start, end));
                    text.setLength(0);
                }
            }

            if (text.length() == 0) {
                start = word.startSeconds();
            } else {
                text.append(' ');
            }
            text.append(piece);
            end = word.endSeconds();

            if (endsSentence(piece)) {
                out.add(new TimedPhrase(text.toString(), start, end));
                text.setLength(0);
            }
        }

        if (text.length() > 0) {
            out.add(new TimedPhrase(text.toString(), start, end));
        }
    }

    private static boolean endsSentence(String word) {
        char last = word.charAt(word.length() - 1);
        return last == '.' || last == '!' || last == '?';
    }

    // ---- Timing ----

    // Each cue shows from its first word until a short hold after its last word, held to at least one second where
    // the next cue allows and never past seven seconds or into the next cue.
    private static List<SubtitleCue> toCues(List<TimedPhrase> phrases) {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 0; i < phrases.size(); i++) {
            TimedPhrase phrase = phrases.get(i);
            double start = phrase.startSeconds();
            double end = phrase.endSeconds() + HOLD_AFTER_LAST_WORD_SECONDS;
            end = Math.max(end, start + MIN_CUE_SECONDS);
            end = Math.min(end, start + MAX_CUE_SECONDS);
            if (i + 1 < phrases.size()) {
                end = Math.min(end, phrases.get(i + 1).startSeconds());
            }
            if (end <= start) {
                continue; // The next phrase starts at the same moment, so this one has no time on screen.
            }
            cues.add(new SubtitleCue(toUs(start), toUs(end), wrap(phrase.text())));
        }
        return cues;
    }

    private static long toUs(double seconds) {
        return Math.round(seconds * US_PER_SECOND);
    }

    // ---- Line breaking ----

    /**
     * Splits text into one line when it fits, otherwise two lines of at most {@value #MAX_LINE_CHARS} characters.
     * The break goes after punctuation near the middle if there is any, so the lines are balanced and read naturally.
     * Text too long for two lines is cut at the last word that fits; the phrase splitter normally prevents that.
     */
    static List<String> wrap(String text) {
        String clean = text.trim().replaceAll("\\s+", " ");
        if (clean.length() <= MAX_LINE_CHARS) {
            return List.of(clean);
        }

        int middle = clean.length() / 2;
        int bestAfterPunctuation = -1;
        int bestAtSpace = -1;
        for (int i = 0; i < clean.length(); i++) {
            if (clean.charAt(i) != ' ' || i > MAX_LINE_CHARS || clean.length() - i - 1 > MAX_LINE_CHARS) {
                continue;
            }
            if (isPunctuation(clean.charAt(i - 1)) && closer(i, bestAfterPunctuation, middle)) {
                bestAfterPunctuation = i;
            }
            if (closer(i, bestAtSpace, middle)) {
                bestAtSpace = i;
            }
        }

        int breakAt = bestAfterPunctuation >= 0 ? bestAfterPunctuation : bestAtSpace;
        if (breakAt < 0) {
            return truncate(clean);
        }
        return List.of(clean.substring(0, breakAt), clean.substring(breakAt + 1));
    }

    private static boolean closer(int candidate, int currentBest, int target) {
        return currentBest < 0 || Math.abs(candidate - target) < Math.abs(currentBest - target);
    }

    private static boolean isPunctuation(char c) {
        return c == ',' || c == ';' || c == ':' || c == '.' || c == '!' || c == '?' || c == '-' || c == '—';
    }

    private static List<String> truncate(String text) {
        int limit = MAX_LINE_CHARS * MAX_LINES;
        String cut = text.length() <= limit ? text : text.substring(0, limit);
        int space = cut.lastIndexOf(' ', MAX_LINE_CHARS);
        if (space <= 0) {
            return List.of(cut.substring(0, Math.min(cut.length(), MAX_LINE_CHARS)));
        }
        String first = cut.substring(0, space);
        String second = cut.substring(space + 1);
        return second.length() > MAX_LINE_CHARS ? List.of(first, second.substring(0, MAX_LINE_CHARS)) : List.of(first, second);
    }
}