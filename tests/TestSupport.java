import java.nio.file.*;
import java.lang.reflect.*;

/** Dependency-free named test cases; scripts/test.py converts these records to JUnit XML. */
final class TestSupport {
    interface Checked { void run() throws Exception; }
    static int failures;
    static void run(String name, Checked test) {
        long start = System.nanoTime();
        try {
            test.run();
            System.out.println("TEST\tPASS\t" + name + "\t" + (System.nanoTime() - start) / 1e9);
        } catch (Throwable error) {
            failures++;
            System.out.println("TEST\tFAIL\t" + name + "\t" + (System.nanoTime() - start) / 1e9);
            error.printStackTrace(System.out);
        }
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void rejects(Checked action) throws Exception {
        try { action.run(); } catch (java.io.IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected validation failure");
    }
    static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static Object call(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(target, values); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }
    static void finish() { if (failures != 0) throw new AssertionError(failures + " test case(s) failed"); }
}
