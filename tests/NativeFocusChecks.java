/** Exercises native lifetime/closed-state checks without ever activating an app or posting keys. */
public class NativeFocusChecks {
    public static void main(String[] args) throws Exception {
        var availability = NativeFocus.class.getDeclaredField("AVAILABLE");
        availability.setAccessible(true);
        if (!availability.getBoolean(null)) throw new AssertionError("Native bridge did not load");
        for (int i = 0; i < 300; i++) {
            NativeFocus focus = new NativeFocus();
            // No capture: there cannot be a destination, regardless of the user's permissions.
            if (focus.freeze() != 0 || focus.isOriginalWindow(1)) throw new AssertionError("Uncaptured target accepted");
            focus.release(0);
            focus.close(); focus.close(); focus.remember();
            if (focus.freeze() != 0 || focus.restore(1)) throw new AssertionError("Closed native target reused");
        }
        System.out.println("PASS: 300 JNI context create/freeze/release/close cycles; invalid and closed targets refused; no keys sent");
    }
}
