package magat.testgame;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/**
 * What the code under test prints, kept by the test. A test of a failure path asserts on the verdict the
 * code prints ({@code VALIDATION: FAIL}, {@code RUN: STOPPED (identical arms)}) instead of leaving it in the
 * suite's output, where it reads as a failure of a suite that passed. If the block throws, what it printed
 * goes to the real output with the exception, since that is when it is wanted.
 */
public final class Stdout {

    private Stdout() {}

    /** What a block returned, and what it printed. */
    public static final class Captured<T> {
        public final T value;
        public final String out;

        Captured(T value, String out) {
            this.value = value;
            this.out = out;
        }
    }

    /** Runs the block with {@code System.out} captured, on every thread. */
    public static <T> Captured<T> capture(Callable<T> block) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream was = System.out;
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            T value = block.call();
            return new Captured<>(value, bytes.toString(StandardCharsets.UTF_8));
        } catch (Exception | Error e) {
            was.print(bytes.toString(StandardCharsets.UTF_8));
            throw e;
        } finally {
            System.setOut(was);
        }
    }
}
