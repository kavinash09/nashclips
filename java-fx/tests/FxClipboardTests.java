package nashclip;
import javafx.application.Platform;
import java.util.*;
import java.util.concurrent.*;

public class FxClipboardTests {
    static final class Board implements ClipboardWatcher.TextClipboard {
        String text; boolean busy, failWrite;
        public String read(){if(busy)throw new IllegalStateException("busy");return text;}
        public void write(String value){if(failWrite)throw new IllegalStateException("denied");text=value;}
    }
    public static void main(String[] args)throws Exception {
        Platform.startup(()->Platform.setImplicitExit(false));
        FutureTask<Void> work=new FutureTask<>(()->{
            TestSupport.run("fx.clipboard.capture.blank.duplicate.own-copy.stop",()->{
                var board=new Board(); board.text="startup"; var clips=new ArrayList<String>();var sizes=new ArrayList<Integer>();
                var w=new ClipboardWatcher(board,clips::add,sizes::add);
                try {
                    w.start();w.start();w.poll();TestSupport.check(clips.isEmpty(),"Startup captured");
                    board.text="one";w.poll();w.poll();board.text=" \n";w.poll();board.text=null;w.poll();
                    w.copy("own");w.poll();TestSupport.check(clips.equals(List.of("one")),"Capture rules");
                    w.stop();board.text="stopped";w.poll();TestSupport.check(clips.size()==1,"Callback after stop");
                }finally{w.stop();}
            });
            TestSupport.run("fx.clipboard.busy.pause-resume.oversize",()->{
                var b=new Board();var clips=new ArrayList<String>();var sizes=new ArrayList<Integer>();var w=new ClipboardWatcher(b,clips::add,sizes::add);
                try{
                    w.start();b.text="one";w.poll();b.busy=true;w.poll();b.busy=false;w.poll();TestSupport.check(clips.size()==1,"Busy duplicate");
                    b.text="x".repeat(65537);w.poll();b.busy=true;w.poll();b.busy=false;w.poll();TestSupport.check(sizes.equals(List.of(65537)),"Oversize dedup");
                    w.setPaused(true);b.text="secret";w.poll();b.busy=true;w.setPaused(false);w.poll();b.busy=false;w.poll();w.poll();
                    TestSupport.check(clips.equals(List.of("one")),"Paused content captured");
                    b.text="two";w.poll();TestSupport.check(clips.equals(List.of("one","two")),"Resume failed");
                }finally{w.stop();}
            });
            TestSupport.run("fx.clipboard.failed-write-reported",()->{
                var b=new Board();var w=new ClipboardWatcher(b,s->{},n->{});b.failWrite=true;
                try{try{w.copy("clip");throw new AssertionError("Write failure hidden");}catch(IllegalStateException expected){}}
                finally{w.stop();}
            });
            return null;
        });
        try{Platform.runLater(work);work.get(15,TimeUnit.SECONDS);TestSupport.finish();}finally{Platform.exit();}
    }
}
