package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for command line parsing. The controller checks that the
 * video file exists, so each test gets a real (empty) file in a temp folder.
 */
class CommandLineControllerTest {
    @TempDir
    Path tempDir;

    private String video;
    private PrintStream originalOut;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() throws IOException {
        video = Files.createFile(tempDir.resolve("movie.mp4")).toString();
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured)); // The controller prints errors and help.
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    private CommandLineController parse(String... args) {
        return new CommandLineController(args);
    }

    private List<Class<?>> effectTypes(CommandLineController controller) {
        return controller.getFrameProcessors().stream()
                .<Class<?>>map(FrameProcessor::getClass)
                .toList();
    }

    // ---- Effects: order, repeats, option styles ----

    @Test
    void stackedOptionsKeepCommandLineOrderAndRepeats() {
        CommandLineController c = parse("-nssn", video);
        assertEquals(List.of(FrameNumberer.class, FrameScratcher.class,
                FrameScratcher.class, FrameNumberer.class), effectTypes(c));
    }

    @Test
    void repeatedOptionsCreateSeparateInstances() {
        CommandLineController c = parse("-ss", video);
        assertNotSame(c.getFrameProcessors().get(0), c.getFrameProcessors().get(1));
    }

    @Test
    void longShortAndStackedFormsAreEquivalent() {
        List<Class<?>> expected = effectTypes(parse("-jfddn", video));
        assertEquals(expected, effectTypes(parse("-j", "-f", "-d", "-d", "-n", video)));
        assertEquals(expected, effectTypes(parse("-jf", "--dust-frames", "-d", "--number-frames", video)));
        assertEquals(expected, effectTypes(parse("--jitter-frames", "--flicker-frames",
                "--dust-frames", "--dust-frames", "--number-frames", video)));
    }

    @Test
    void videoFileCanAppearAnywhereOnTheCommandLine() {
        assertEquals(effectTypes(parse("-sn", video)), effectTypes(parse("-s", video, "-n")));
    }

    @Test
    void singularSpellingsStillAccepted() {
        CommandLineController c = parse("--dust-frame", "--vignette-frame", video);
        assertEquals(List.of(FrameDuster.class, FrameVignetter.class), effectTypes(c));
    }

    @Test
    void effectsCanBeStackedWithLaunchOptions() {
        CommandLineController c = parse("-anx", video);
        assertTrue(c.isAudioRequested());
        assertTrue(c.isMaximiseRequested());
        assertEquals(List.of(FrameNumberer.class), effectTypes(c));
        assertEquals(0, c.getExitCode());
    }

    @Test
    void noEffectOptionsMeansNoProcessors() {
        CommandLineController c = parse(video);
        assertTrue(c.getFrameProcessors().isEmpty());
        assertTrue(c.shouldLaunchApplication());
    }

    // ---- Errors and exit codes ----

    @Test
    void unknownShortOptionInStackIsAnError() {
        CommandLineController c = parse("-sfbnz", video);
        assertEquals(1, c.getExitCode());
        assertFalse(c.shouldLaunchApplication());
    }

    @Test
    void unknownLongOptionIsAnError() {
        assertEquals(1, parse("--illegal-argument", video).getExitCode());
    }

    @Test
    void optionNamesAreCaseSensitive() {
        assertEquals(1, parse(video, "--Flicker-frames").getExitCode());
    }

    @Test
    void twoVideoFilesIsAnError() {
        assertEquals(1, parse(video, video).getExitCode());
    }

    @Test
    void missingVideoFileIsAnError() {
        assertEquals(1, parse("--number-frames").getExitCode());
    }

    @Test
    void nonexistentVideoFileIsAnError() {
        assertEquals(1, parse(tempDir.resolve("nope.mp4").toString()).getExitCode());
    }

    @Test
    void conflictingDisplayOptionsAreAnError() {
        assertEquals(1, parse("-12", video).getExitCode());
    }

    // ---- Help ----

    @Test
    void helpAloneSucceedsWithoutLaunching() {
        CommandLineController c = parse("--help");
        assertEquals(0, c.getExitCode());
        assertFalse(c.shouldLaunchApplication());
    }

    @Test
    void helpWithEffectsButNoVideoIsAnError() {
        assertEquals(1, parse("--number-frames", "-h").getExitCode());
    }

    @Test
    void helpTextMatchesSpecification() {
        parse("--help");
        List<String> expected = List.of(
                "Usage: VideoPlayer [options] [video-file]",
                "",
                "Options:",
                "  -h, --help         Show this help message",
                "  -a, --audio        Play audio",
                "  -x, --maximise     Open the player maximised",
                "  -1, --monitor-1    Open the player on display 1",
                "  -2, --monitor-2    Open the player on display 2",
                "",
                "Frame processors:",
                "  -n, --number-frames    Render the frame number onto each frame",
                "  -s, --scratch-frames   Render vertical film scratches",
                "  -f, --flicker-frames   Randomly dim frames",
                "  -w, --black-and-white  Convert frames to black and white",
                "  -y, --yellow-frames    Apply a warmer colour temperature",
                "  -v, --vignette-frames  Darken the frame edges",
                "  -d, --dust-frames      Render dust and hair marks",
                "  -j, --jitter-frames    Randomly displace frames by a few pixels",
                "  -m, --mottle-frames    Add cloudy emulsion mottling",
                "  -b, --bleed-frames     Bleed light into frames",
                "  -p, --pepper-frames    Pepper frames with dark spots/blotches",
                "",
                "Frame processors are applied in command-line order and may be repeated.",
                "Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,",
                "converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
        assertEquals(expected, captured.toString().lines().toList());
    }
}