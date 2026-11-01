/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Duy Do a1921156
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import comp3011.SubtitleBuilder.Segment;
import comp3011.SubtitleBuilder.Word;

class SubtitleBuilderTest {

    @Test
    void aShortSentenceBecomesOneCueOnOneLineWhenItIsSpoken() {
        Segment segment = segment("Hello there, my friend.", 2.0, 4.0, 0.0, -0.2,
                word("Hello", 2.0, 2.4), word("there,", 2.5, 2.9), word("my", 3.0, 3.2), word("friend.", 3.3, 4.0));

        List<SubtitleCue> cues = SubtitleBuilder.build(List.of(segment));

        assertEquals(1, cues.size());
        assertEquals(List.of("Hello there, my friend."), cues.get(0).lines());
        assertEquals(2_000_000L, cues.get(0).startUs());
    }

    @Test
    void aCueStaysUpForAtLeastOneSecondEvenForOneWord() {
        List<SubtitleCue> cues = SubtitleBuilder.build(List.of(
                segment("Yes.", 10.0, 10.2, 0.0, -0.1, word("Yes.", 10.0, 10.2))));

        assertEquals(1, cues.size());
        assertTrue(cues.get(0).endUs() - cues.get(0).startUs() >= 1_000_000L);
    }

    @Test
    void aCueNeverStaysUpForMoreThanSevenSeconds() {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            words.add(word("word" + i, i * 0.5, i * 0.5 + 0.4));
        }
        List<SubtitleCue> cues = SubtitleBuilder.build(List.of(segment("long", 0, 20, 0.0, -0.1, words)));

        assertTrue(cues.size() > 1);
        for (SubtitleCue cue : cues) {
            assertTrue(cue.endUs() - cue.startUs() <= 7_000_000L, "cue too long: " + cue);
        }
    }

    @Test
    void aPauseStartsANewCue() {
        Segment segment = segment("and then we left", 0.0, 6.0, 0.0, -0.1,
                word("and", 0.0, 0.3), word("then", 0.4, 0.7), word("we", 4.0, 4.2), word("left", 4.3, 4.6));

        List<SubtitleCue> cues = SubtitleBuilder.build(List.of(segment));

        assertEquals(2, cues.size());
        assertEquals(List.of("and then"), cues.get(0).lines());
        assertEquals(List.of("we left"), cues.get(1).lines());
    }

    @Test
    void aSentenceEndStartsANewCueWithoutAPause() {
        Segment segment = segment("Go. Now.", 0.0, 1.0, 0.0, -0.1,
                word("Go.", 0.0, 0.4), word("Now.", 0.45, 0.9));

        assertEquals(2, SubtitleBuilder.build(List.of(segment)).size());
    }

    @Test
    void cuesNeverOverlapAndStayInTimeOrder() {
        Segment a = segment("One two.", 0.0, 1.0, 0.0, -0.1, word("One", 0.0, 0.4), word("two.", 0.5, 0.9));
        Segment b = segment("Three four.", 1.1, 2.0, 0.0, -0.1, word("Three", 1.1, 1.5), word("four.", 1.6, 2.0));

        List<SubtitleCue> cues = SubtitleBuilder.build(List.of(a, b));

        assertEquals(2, cues.size());
        assertTrue(cues.get(0).endUs() <= cues.get(1).startUs());
        assertTrue(cues.get(0).startUs() < cues.get(1).startUs());
    }

    @Test
    void textLongerThanOneLineIsBrokenIntoTwoLinesAfterPunctuationWhenPossible() {
        List<String> lines = SubtitleBuilder.wrap("I told him I would be there, but he did not believe me at all");

        assertEquals(List.of("I told him I would be there,", "but he did not believe me at all"), lines);
    }

    @Test
    void whenNoPunctuationBreakFitsTheLineIsBrokenAtASpaceNearTheMiddle() {
        // A break after the comma would leave 48 characters on the second line, which is too long.
        List<String> lines = SubtitleBuilder.wrap("When I was young, my father took me fishing every single weekend.");

        assertEquals(List.of("When I was young, my father took", "me fishing every single weekend."), lines);
    }

    @Test
    void textThatFitsOnOneLineIsNeverSplit() {
        assertEquals(List.of("Short and sweet, really."), SubtitleBuilder.wrap("Short and sweet, really."));
    }

    @Test
    void noLineEverExceedsFortyTwoCharactersAndNoCueExceedsTwoLines() {
        List<Word> words = new ArrayList<>();
        String[] vocabulary = { "extraordinarily", "cat", "unbelievable", "is", "perpendicular", "to", "a", "hippopotamus" };
        for (int i = 0; i < 60; i++) {
            words.add(word(vocabulary[i % vocabulary.length], i * 0.3, i * 0.3 + 0.25));
        }
        List<SubtitleCue> cues = SubtitleBuilder.build(List.of(segment("x", 0, 20, 0.0, -0.1, words)));

        assertFalse(cues.isEmpty());
        for (SubtitleCue cue : cues) {
            assertTrue(cue.lines().size() >= 1 && cue.lines().size() <= 2, "lines: " + cue.lines());
            for (String line : cue.lines()) {
                assertTrue(line.length() <= 42, "line too long: " + line);
            }
        }
    }

    @Test
    void segmentsThatAreProbablyNotSpeechAreDropped() {
        Segment noise = segment("music", 0.0, 3.0, 0.9, -1.5, word("music", 0.0, 3.0));
        Segment speech = segment("Hi there.", 3.0, 4.0, 0.1, -0.3, word("Hi", 3.0, 3.4), word("there.", 3.5, 4.0));

        List<SubtitleCue> cues = SubtitleBuilder.build(List.of(noise, speech));

        assertEquals(1, cues.size());
        assertEquals(List.of("Hi there."), cues.get(0).lines());
    }

    @Test
    void bothMeasuresMustSayNotSpeechBeforeASegmentIsDropped() {
        assertTrue(SubtitleBuilder.isSpeech(segment("a", 0, 1, 0.9, -0.5)));  // quiet but confident
        assertTrue(SubtitleBuilder.isSpeech(segment("a", 0, 1, 0.1, -1.5)));  // unsure but probably speech
        assertFalse(SubtitleBuilder.isSpeech(segment("a", 0, 1, 0.9, -1.5)));
    }

    @Test
    void aSegmentWithoutWordTimingsStillGivesACueAtTheSegmentTime() {
        List<SubtitleCue> cues = SubtitleBuilder.build(List.of(segment("No word timings here.", 5.0, 7.0, 0.0, -0.2)));

        assertEquals(1, cues.size());
        assertEquals(5_000_000L, cues.get(0).startUs());
    }

    @Test
    void emptyInputGivesNoCues() {
        assertTrue(SubtitleBuilder.build(List.of()).isEmpty());
        assertTrue(SubtitleBuilder.build(List.of(segment("", 0, 1, 0.0, -0.1))).isEmpty());
    }

    // ---- Helpers ----

    private static Word word(String text, double start, double end) {
        return new Word(text, start, end);
    }

    private static Segment segment(String text, double start, double end, double noSpeechProb, double avgLogProb, Word... words) {
        return new Segment(text, start, end, avgLogProb, noSpeechProb, List.of(words));
    }

    private static Segment segment(String text, double start, double end, double noSpeechProb, double avgLogProb, List<Word> words) {
        return new Segment(text, start, end, avgLogProb, noSpeechProb, words);
    }
}