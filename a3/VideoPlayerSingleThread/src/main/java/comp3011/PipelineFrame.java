/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Duy Do a1921156
 */
package comp3011;

import org.bytedeco.javacv.Frame;

import javafx.scene.image.Image;

/**
 * A frame travelling through the {@link FramePipeline}.
 *
 * <p>
 * Ownership rule: exactly one thread holds a PipelineFrame at a time. A thread
 * passes it on by putting it on a queue and must not touch it afterwards. The
 * queue hand-off is also what makes one stage's pixel writes visible to the
 * next stage.
 * </p>
 *
 * <p>
 * Before conversion it carries the mutable JavaCV frame (a private copy of the
 * decoder's frame) and no image. After conversion it carries the finished JavaFX
 * image and no frame, which lets the large pixel buffer be reclaimed early.
 * </p>
 *
 * @param frame            the JavaCV frame the effects draw on, or null once converted
 * @param image            the finished JavaFX image, or null before conversion
 * @param frameNumber      the video frame number, as reported by the decoder
 * @param mediaTimestampUs the frame's position in the media, in microseconds
 * @param preparedAtNs     when the frame finished conversion, used for the headroom trace
 */
public record PipelineFrame(
        Frame frame,
        Image image,
        int frameNumber,
        long mediaTimestampUs,
        long preparedAtNs) {

    /** Marker queued after the last video frame. Every stage forwards it and then finishes. */
    public static final PipelineFrame END_OF_STREAM = new PipelineFrame(null, null, -1, 0, 0);

    public static PipelineFrame decoded(Frame frame, int frameNumber, long mediaTimestampUs) {
        return new PipelineFrame(frame, null, frameNumber, mediaTimestampUs, 0);
    }

    public PipelineFrame converted(Image image, long preparedAtNs) {
        return new PipelineFrame(null, image, frameNumber, mediaTimestampUs, preparedAtNs);
    }

    public boolean isEndOfStream() {
        return this == END_OF_STREAM;
    }
}
