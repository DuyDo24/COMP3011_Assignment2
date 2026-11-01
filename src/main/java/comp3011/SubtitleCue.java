/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Duy Do a1921156
 */
package comp3011;

import java.util.List;

/**
 * One subtitle: some text to show from startUs until endUs of the media's timeline.
 *
 * @param startUs when the text appears, in microseconds from the start of the media
 * @param endUs   when the text disappears, in microseconds from the start of the media
 * @param lines   one or two lines of at most {@link SubtitleBuilder#MAX_LINE_CHARS} characters
 */
public record SubtitleCue(long startUs, long endUs, List<String> lines) {
    public SubtitleCue {
        lines = List.copyOf(lines);
    }

    public boolean isShownAt(long timestampUs) {
        return timestampUs >= startUs && timestampUs < endUs;
    }
}