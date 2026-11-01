/*
 * Adelaide University COMP3011 Assignment 3.
*
* Authors:
*   1. Duy Do a1921156
*/
package comp3011;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
* The meeting point between the code that gets subtitles and the code that draws them.
*
* <p>
* One thread (later, the one fetching text from the web server) adds cues, the JavaFX thread flips the on/off switch,
* and the frame pipeline's effect stage asks which cue to draw for each frame. None of them waits for the others:
* </p>
* <ul>
* <li>the cue list is an immutable sorted list held in a volatile field. Writers build a whole new list under a lock
* and swap it in, so a reader always sees a complete, consistent list and never needs a lock;</li>
* <li>the on/off switch is a volatile boolean, so turning subtitles off takes effect on the very next frame and
* toggling never interrupts the video or the audio.</li>
* </ul>
*
* <p>
* There is one store for the whole player because effects are created from the command line with no arguments. The
* store holds nothing about video, only text and times.
* </p>
*/
public final class SubtitleStore {
   private static final SubtitleStore INSTANCE = new SubtitleStore();

   private final Object writeLock = new Object();
   private volatile List<SubtitleCue> cues = List.of();
   private volatile boolean enabled;

   public static SubtitleStore instance() {
       return INSTANCE;
   }

   /** Creates a separate store. The player only uses {@link #instance()}, other stores are for tests. */
   public SubtitleStore() {
   }

   public boolean isEnabled() {
       return enabled;
   }

   public void setEnabled(boolean enabled) {
       this.enabled = enabled;
   }

   /** Replaces every cue, for example after a different video is opened. */
   public void setCues(Collection<SubtitleCue> newCues) {
       synchronized (writeLock) {
           cues = sortedCopy(newCues);
       }
   }

   /** Adds cues to the ones already held. A cue with the same start time as an existing one replaces it. */
   public void addCues(Collection<SubtitleCue> newCues) {
       synchronized (writeLock) {
           List<SubtitleCue> merged = new ArrayList<>(cues.size() + newCues.size());
           for (SubtitleCue existing : cues) {
               boolean replaced = false;
               for (SubtitleCue added : newCues) {
                   if (added.startUs() == existing.startUs()) {
                       replaced = true;
                       break;
                   }
               }
               if (!replaced) {
                   merged.add(existing);
               }
           }
           merged.addAll(newCues);
           cues = sortedCopy(merged);
       }
   }

   public void clear() {
       setCues(List.of());
   }

   public boolean isEmpty() {
       return cues.isEmpty();
   }

   /**
    * The cue to show at the given media time, or null if there is none or subtitles are switched off. Safe to call
    * from any thread and never blocks.
    */
   public SubtitleCue cueAt(long timestampUs) {
       if (!enabled) {
           return null;
       }

       List<SubtitleCue> snapshot = cues;
       int low = 0;
       int high = snapshot.size() - 1;
       int found = -1;
       while (low <= high) { // The last cue that starts at or before the timestamp.
           int middle = (low + high) >>> 1;
           if (snapshot.get(middle).startUs() <= timestampUs) {
               found = middle;
               low = middle + 1;
           } else {
               high = middle - 1;
           }
       }

       if (found < 0) {
           return null;
       }
       SubtitleCue candidate = snapshot.get(found);
       return candidate.isShownAt(timestampUs) ? candidate : null;
   }

   private static List<SubtitleCue> sortedCopy(Collection<SubtitleCue> source) {
       List<SubtitleCue> copy = new ArrayList<>(source);
       copy.sort(Comparator.comparingLong(SubtitleCue::startUs));
       return List.copyOf(copy);
   }
}