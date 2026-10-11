/** Exercises real orchestration with fake OS effects. Never posts a keyboard event. */
public class PasteServiceTests {
    static class Platform implements PasteService.PlatformAccess {
        String clipboard = "clip"; boolean restored = true, matches = true, deny;
        int sends, releases, closes, restores, reads, checks, delays;
        final java.util.List<String> events = new java.util.ArrayList<>();
        Runnable duringDelay = () -> { };
        public void remember() { }
        public long freeze() { return 7; }
        public boolean restore(long token) { TestSupport.check(token == 7, "Wrong frozen target"); restores++; events.add("restore"); return restored; }
        public boolean isOriginalWindow(long token) { TestSupport.check(token == 7, "Wrong verified target"); checks++; events.add("verify"); return matches; }
        public void release(long token) { TestSupport.check(token == 0 || token == 7, "Wrong released target"); releases++; events.add("release"); }
        public boolean clipboardMatches(String text) { reads++; events.add("clipboard"); return text.equals(clipboard); }
        public void awaitRestoration() throws InterruptedException {
            delays++; events.add("delay"); duringDelay.run(); if (Thread.interrupted()) throw new InterruptedException();
        }
        public void sendPaste() { if (deny) throw new SecurityException("denied"); events.add("send"); sends++; }
        public void close() { closes++; }
    }
    static void scenario(String name, java.util.function.BiConsumer<Platform, PasteService> arrange, boolean sent) {
        TestSupport.run(name, () -> {
            var platform = new Platform();
            try (var service = new PasteService(platform)) {
                arrange.accept(platform, service);
                var result = service.paste(service.target(), "clip");
                TestSupport.check(result.sent() == sent && platform.sends == (sent ? 1 : 0), "Unexpected key delivery: " + result);
                TestSupport.check(platform.releases == 1, "Frozen destination leaked");
                if (sent) TestSupport.check(platform.events.equals(java.util.List.of("clipboard", "restore", "delay", "verify", "clipboard", "send", "release")), "Verification order changed");
                if (name.endsWith("changed-before")) TestSupport.check(platform.restores == 0 && platform.delays == 0, "Changed clipboard still activated destination");
                if (name.endsWith("wrong-window")) TestSupport.check(result.message().contains("regain focus"), "Wrong failure status");
                if (name.endsWith("closed")) TestSupport.check(platform.reads == 0 && platform.restores == 0, "Closed service still accessed OS");
                if (name.endsWith("interrupted")) TestSupport.check(Thread.currentThread().isInterrupted(), "Interrupt flag swallowed");
            } finally { Thread.interrupted(); }
            TestSupport.check(platform.closes == 1, "Platform cleanup not idempotent");
        });
    }
    public static void main(String[] args) {
        scenario("paste.verified-target.success", (p,s) -> {}, true);
        scenario("paste.restore-refused", (p,s) -> p.restored = false, false);
        scenario("paste.wrong-window", (p,s) -> p.matches = false, false);
        scenario("paste.clipboard-changed-before", (p,s) -> p.clipboard = "changed", false);
        scenario("paste.clipboard-changed-during-delay", (p,s) -> p.duringDelay = () -> p.clipboard = "changed", false);
        scenario("paste.permission-denied", (p,s) -> p.deny = true, false);
        scenario("paste.closed", (p,s) -> { s.close(); s.close(); }, false);
        scenario("paste.shutdown-during-delay", (p,s) -> p.duringDelay = s::close, false);
        scenario("paste.interrupted", (p,s) -> p.duringDelay = () -> Thread.currentThread().interrupt(), false);
        TestSupport.run("paste.unknown-window-and-cancel", () -> {
            var p = new Platform();
            try (var s = new PasteService(p)) {
                TestSupport.check(!s.paste(new PasteService.Target(0), "clip").sent() && p.restores == 0 && p.sends == 0, "Unknown target activated");
                s.cancel(s.target()); TestSupport.check(p.releases == 2, "Cancelled target retained");
            }
        });
        TestSupport.run("paste.background-monitor.idempotent-start-and-close", () -> {
            var p = new Platform();
            try (var s = new PasteService(p)) {
                s.start(); Thread first = (Thread) TestSupport.get(s, "focusMonitor");
                s.start(); TestSupport.check(first == TestSupport.get(s, "focusMonitor"), "Second monitor started");
                s.close(); first.join(3000); TestSupport.check(!first.isAlive(), "Monitor survived shutdown");
                s.start(); TestSupport.check(!first.isAlive() && p.closes == 1, "Closed service restarted");
            }
        });
        TestSupport.finish();
    }
}
