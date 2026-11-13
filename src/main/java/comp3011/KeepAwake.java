/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Duy Do a1921156
 *
 * Drafted with help from Claude (Anthropic), then read and reviewed by the author.
 */
package comp3011;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Asks Windows not to turn the display off, start the screen saver or lock the session while a video is playing, as
 * every media player does. Without it, a video that runs for a couple of minutes with nobody touching the keyboard
 * can freeze when the display goes idle. Does nothing on other systems, and never fails: if the call is not possible
 * the player simply behaves as before.
 *
 * <p>
 * SetThreadExecutionState applies to the thread that calls it and lasts until that thread ends or calls it again, so
 * one daemon thread of its own makes the call and renews it every 10 seconds.
 * </p>
 */
final class KeepAwake {
    private static final int ES_CONTINUOUS = 0x80000000;
    private static final int ES_SYSTEM_REQUIRED = 0x00000001;
    private static final int ES_DISPLAY_REQUIRED = 0x00000002;
    private static final long RENEW_MS = 10_000L;

    private static Thread holder;

    private KeepAwake() {
    }

    /** Starts keeping the display awake. Safe to call again while already doing so. */
    static synchronized void begin() {
        if (holder != null || !System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        Thread thread = new Thread(KeepAwake::hold, "keep-awake");
        thread.setDaemon(true);
        holder = thread;
        thread.start();
    }

    /** Lets the display sleep again. */
    static synchronized void end() {
        Thread thread = holder;
        holder = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void hold() {
        try {
            MethodHandle call = Linker.nativeLinker().downcallHandle(
                    SymbolLookup.libraryLookup("kernel32", Arena.global()).find("SetThreadExecutionState")
                            .orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int ignored = (int) call.invokeExact(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED);
                    Thread.sleep(RENEW_MS);
                }
            } catch (InterruptedException stopped) {
                // end() was called.
            } finally {
                int ignored = (int) call.invokeExact(ES_CONTINUOUS); // Back to normal.
            }
        } catch (Throwable unavailable) {
            // Best effort only.
        }
    }
}