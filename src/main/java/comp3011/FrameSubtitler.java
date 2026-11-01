/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Duy Do a1921156
 */
package comp3011;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bytedeco.javacv.Frame;

/**
 * Draws the current subtitle onto each frame: white sans serif text with a black outline, centred near the bottom.
 *
 * <p>
 * Which cue to draw comes from the {@link SubtitleStore}, looked up by the frame's position in the video. When there is
 * no cue, or subtitles are off, the frame is left alone and the call returns straight away.
 * </p>
 *
 * <p>
 * Drawing text onto pixels is slow if repeated, so each cue is drawn once into a small transparent picture and that
 * picture is then blended onto every frame that shows the cue. This instance only ever runs on one pipeline thread, so
 * keeping that picture in a field is safe, in the same way the other effects keep scratch buffers.
 * </p>
 */
public class FrameSubtitler extends FrameProcessor {
    static {
        // The text is drawn with Java's 2D library off screen. Headless mode stops it starting a second windowing
        // system alongside JavaFX, which causes trouble on macOS. It must be set before any of that library loads.
        System.setProperty("java.awt.headless", "true");
    }

    private static final double FONT_HEIGHT_FRACTION = 0.052;
    private static final double MAX_TEXT_WIDTH_FRACTION = 0.92;
    private static final double BOTTOM_MARGIN_FRACTION = 0.06;
    private static final double OUTLINE_WIDTH_FRACTION = 0.14; // of the font size
    private static final double LINE_SPACING = 1.18;
    private static final int MIN_FONT_SIZE = 10;

    private SubtitleCue drawnCue;
    private int drawnWidth;
    private int drawnHeight;
    private int[] overlayArgb;
    private int overlayWidth;
    private int overlayHeight;
    private int overlayX;
    private int overlayY;

    // Asking for this effect on the command line is what switches subtitles on to begin with.
    public FrameSubtitler() {
        super();
        SubtitleStore.instance().setEnabled(true);
    }


    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameSubtitler requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameSubtitler requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 3) {
            throw new Exception("FrameSubtitler requires at least three image channels");
        }

        long timestampUs = (long) info.frameNumber() * videoInfo.frameDurationUs();
        SubtitleCue cue = SubtitleStore.instance().cueAt(timestampUs);
        if (cue == null) {
            return;
        }

        if (cue != drawnCue || drawnWidth != videoInfo.imageWidth() || drawnHeight != videoInfo.imageHeight()) {
            drawOverlay(cue);
        }
        blend(pixels);
    }

    // ---- Drawing the text into a transparent picture ----

    private void drawOverlay(SubtitleCue cue) {
        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();

        Font font = chooseFont(cue.lines(), width, height);
        int fontSize = font.getSize();
        int lineHeight = (int) Math.ceil(fontSize * LINE_SPACING);
        int padding = (int) Math.ceil(fontSize * OUTLINE_WIDTH_FRACTION) + 2;

        overlayWidth = width;
        overlayHeight = lineHeight * cue.lines().size() + padding * 2;
        overlayX = 0;
        overlayY = Math.max(0, height - (int) (height * BOTTOM_MARGIN_FRACTION) - overlayHeight);
        overlayHeight = Math.min(overlayHeight, height - overlayY);

        BufferedImage image = new BufferedImage(overlayWidth, overlayHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            BasicStroke outline = new BasicStroke(
                    (float) (fontSize * OUTLINE_WIDTH_FRACTION), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

            for (int i = 0; i < cue.lines().size(); i++) {
                String line = cue.lines().get(i);
                float x = (overlayWidth - metrics.stringWidth(line)) / 2f;
                float baseline = padding + i * lineHeight + metrics.getAscent();

                GlyphVector glyphs = font.createGlyphVector(g.getFontRenderContext(), line);
                Shape shape = glyphs.getOutline(x, baseline);
                g.setColor(Color.BLACK);
                g.setStroke(outline);
                g.draw(shape);
                g.setColor(Color.WHITE);
                g.fill(shape);
            }
        } finally {
            g.dispose();
        }

        overlayArgb = image.getRGB(0, 0, overlayWidth, overlayHeight, null, 0, overlayWidth);
        drawnCue = cue;
        drawnWidth = width;
        drawnHeight = height;
    }

    // Starts from a size relative to the picture height and shrinks it until the widest line fits.
    private static Font chooseFont(List<String> lines, int width, int height) {
        int size = Math.max(MIN_FONT_SIZE, (int) Math.round(height * FONT_HEIGHT_FRACTION));
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, size);

        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            int widest = 0;
            for (String line : lines) {
                widest = Math.max(widest, g.getFontMetrics(font).stringWidth(line));
            }
            double allowed = width * MAX_TEXT_WIDTH_FRACTION;
            if (widest > allowed) {
                size = Math.max(MIN_FONT_SIZE, (int) Math.floor(size * allowed / widest));
                font = font.deriveFont((float) size);
            }
        } finally {
            g.dispose();
        }
        return font;
    }

    // ---- Putting the picture onto the frame ----

    // Blends each overlay pixel over the frame. The frame is blue, green, red (in that order) with the given row stride.
    private void blend(ByteBuffer pixels) {
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        for (int row = 0; row < overlayHeight; row++) {
            int rowStart = (overlayY + row) * stride + overlayX * channels;
            for (int column = 0; column < overlayWidth; column++) {
                int argb = overlayArgb[row * overlayWidth + column];
                int alpha = argb >>> 24;
                if (alpha == 0) {
                    continue;
                }

                int base = rowStart + column * channels;
                int blue = argb & 0xff;
                int green = (argb >>> 8) & 0xff;
                int red = (argb >>> 16) & 0xff;
                mix(pixels, base, blue, alpha);
                mix(pixels, base + 1, green, alpha);
                mix(pixels, base + 2, red, alpha);
            }
        }
    }

    private static void mix(ByteBuffer pixels, int index, int source, int alpha) {
        int destination = Byte.toUnsignedInt(pixels.get(index));
        int mixed = (source * alpha + destination * (255 - alpha) + 127) / 255;
        pixels.put(index, (byte) mixed);
    }


}