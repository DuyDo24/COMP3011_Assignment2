package comp3011;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;


public enum FrameEffectOption {
    NUMBER('n', "number-frames", "Render the frame number onto each frame", FrameNumberer::new),
    SCRATCH('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
    FLICKER('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
    BLACK_AND_WHITE('w', "black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
    YELLOW('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
    VIGNETTE('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new, "vignette-frame"),
    DUST('d', "dust-frames", "Render dust and hair marks", FrameDuster::new, "dust-frame"),
    JITTER('j', "jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
    MOTTLE('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
    BLEED('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
    PEPPER('p', "pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);

    private final char shortName;
    private final String longName;
    private final String description;
    private final Supplier<FrameProcessor> factory;
    private final String[] aliases; // Extra accepted long spellings, not shown in the help text.

    FrameEffectOption(char shortName, String longName, String description,
            Supplier<FrameProcessor> factory, String... aliases) {
        this.shortName = shortName;
        this.longName = longName;
        this.description = description;
        this.factory = factory;
        this.aliases = aliases;
    }

    /** Every call returns a brand new processor, so repeated options never share state. */
    public FrameProcessor create() {
        return factory.get();
    }

    public char shortName() {
        return shortName;
    }

    public String longName() {
        return longName;
    }

    public String description() {
        return description;
    }

    public static Optional<FrameEffectOption> fromShortName(char c) {
        return Arrays.stream(values()).filter(o -> o.shortName == c).findFirst();
    }

    public static Optional<FrameEffectOption> fromLongName(String name) {
        return Arrays.stream(values())
                .filter(o -> o.longName.equals(name) || Arrays.asList(o.aliases).contains(name))
                .findFirst();
    }
}