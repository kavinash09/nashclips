package nashclip;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

public class FxFeatureTests {
    static Path root;
    static <T>T ui(Callable<T> action)throws Exception {var task=new FutureTask<T>(action);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    static ButtonBase button(Parent parent,String prefix){
        for(Node child:parent.getChildrenUnmodifiable()){
            if(child instanceof ButtonBase b && b.getAccessibleText()!=null && b.getAccessibleText().startsWith(prefix))return b;
            if(child instanceof Parent p){var found=button(p,prefix);if(found!=null)return found;}
        }return null;
    }
    static final class Fixture implements AutoCloseable {
        final NashClipApp app;final ObservableList<String> history;final Path data;
        final Stage panel;final EdgeDrawer drawer;final FxClipboardTests.Board board=new FxClipboardTests.Board();
        final PasteServiceTests.Platform platform=new PasteServiceTests.Platform();
        @SuppressWarnings("unchecked") Fixture()throws Exception{
            Path home=Files.createTempDirectory(root,"home-");System.setProperty("user.home",home.toString());data=home.resolve(".nashclip");
            app=new NashClipApp();((PasteService)TestSupport.get(app,"pasteService")).close();TestSupport.set(app,"pasteService",new PasteService(platform));
            TestSupport.call(app,"loadSettingsAndHistory",new Class<?>[0]);history=(ObservableList<String>)TestSupport.get(app,"history");
            panel=new Stage(StageStyle.TRANSPARENT);panel.setOpacity(0);TestSupport.set(app,"panel",panel);
            TestSupport.set(app,"clipboardWatcher",new ClipboardWatcher(board,s->{},n->{}));
            panel.setScene((Scene)TestSupport.call(app,"createScene",new Class<?>[0]));
            drawer=new EdgeDrawer(panel,(Node)TestSupport.get(app,"dragHeader"),false,null,760,340,p->{});TestSupport.set(app,"drawer",drawer);
            for(Window w:Window.getWindows())w.setOpacity(0);
        }
        ClipboardTile tile(int i)throws Exception{return(ClipboardTile)TestSupport.call(app,"createTile",new Class<?>[]{int.class},i);}
        void add(String text)throws Exception{TestSupport.call(app,"addCopiedText",new Class<?>[]{String.class},text);}
        @SuppressWarnings("unchecked")List<String> pins()throws Exception{return(List<String>)TestSupport.get(app,"pinnedClips");}
        public void close()throws Exception{app.stop();}
    }
    public static void main(String[] args)throws Exception{
        root=Path.of(args[0]);Files.createDirectories(root);Platform.startup(()->Platform.setImplicitExit(false));
        try{
            ui(()->{
                TestSupport.run("fx.tiles.pin-five.unpin.delete.persist",()->{
                    try(var f=new Fixture()){
                        for(int i=0;i<6;i++)f.add("clip"+i);
                        for(int i=0;i<5;i++)button(f.tile(5),"Pin this clip").fire();
                        button(f.tile(5),"Pin this clip").fire();TestSupport.check(f.pins().size()==5,"Pin limit");
                        TestSupport.check(new HistoryStore(f.data,40).loadSnapshot().pinned().equals(f.pins()),"Pin restart data");
                        String deleted=f.history.get(0);button(f.tile(0),"Delete clip").fire();
                        TestSupport.check(!f.pins().contains(deleted)&&!f.history.contains(deleted),"Pinned delete");
                        button(f.tile(0),"Unpin this clip").fire();TestSupport.check(f.pins().size()==3,"Unpin");
                        TestSupport.check(new HistoryStore(f.data,40).loadSnapshot().pinned().size()==3,"Unpin not saved");
                    }
                });
                TestSupport.run("fx.tiles.failed-save-keeps-model",()->{
                    try(var f=new Fixture()){
                        f.add("keep");Path file=f.data.resolve("history.properties");Files.delete(file);Files.createDirectory(file);Files.writeString(file.resolve("block"),"x");
                        button(f.tile(0),"Delete clip").fire();button(f.tile(0),"Pin this clip").fire();
                        TestSupport.check(f.history.equals(List.of("keep"))&&f.pins().isEmpty(),"Failure mutated model");
                    }
                });
                TestSupport.run("fx.capture.newest-first.limit-and-pinned-dedup",()->{
                    try(var f=new Fixture()){
                        f.add("pin");button(f.tile(0),"Pin this clip").fire();TestSupport.set(f.app,"historyLimit",2);
                        f.add("two");f.add("three");f.add("four");f.add("pin");
                        TestSupport.check(f.history.equals(List.of("pin","four","three")),"Capture order/budget");
                    }
                });
                TestSupport.run("fx.theme-and-drawer-pin.persist",()->{
                    try(var f=new Fixture()){
                        TestSupport.call(f.app,"applyTheme",new Class<?>[]{String.class},"dark");
                        TestSupport.check(f.panel.getScene().getRoot().getStyleClass().contains("theme-dark"),"Dark style absent");
                        TestSupport.call(f.app,"applyPin",new Class<?>[]{boolean.class},true);
                        var saved=AppSettings.load(f.data);TestSupport.check(saved.pinned()&&saved.theme().equals("dark"),"Settings not saved");
                        TestSupport.call(f.app,"applyTheme",new Class<?>[]{String.class},"light");
                        TestSupport.check(!f.panel.getScene().getRoot().getStyleClass().contains("theme-dark"),"Light mode failed");
                    }
                });
                TestSupport.run("fx.layout.shortest-column.responsive.empty",()->{
                    var history=javafx.collections.FXCollections.observableArrayList("a\n".repeat(20),"b","c\n".repeat(20),"d");
                    var grid=new HistoryGrid(history,i->new ClipboardTile(history.get(i),i,false,()->{},()->{},()->{}));
                    grid.setAvailableWidth(900);var columns=(HBox)((StackPane)grid.getContent()).getChildren().get(0);
                    TestSupport.check(columns.getChildren().size()==3,"Three columns");
                    TestSupport.check(((VBox)columns.getChildren().get(1)).getChildren().size()==2,"D should fill below B");
                    grid.setAvailableWidth(200);TestSupport.check(((HBox)((StackPane)grid.getContent()).getChildren().get(0)).getChildren().size()==1,"Narrow layout");
                    history.clear();grid.refresh();TestSupport.check(grid.getContent() instanceof VBox,"Empty view");
                });
                return null;
            });
            TestSupport.run("fx.use-button.full-text.delivers-once.hides",()->{
                Fixture f=ui(Fixture::new);
                try{
                    String text="full "+"x".repeat(1200);f.platform.clipboard=text;
                    ui(()->{f.drawer.showDrawer(); return null;});
                    Thread.sleep(500);
                    ui(()->{TestSupport.check(f.panel.isShowing(), "Use precondition: panel must be open"); return null;});
                    ui(()->{f.add(text);button(f.tile(0),"Use clip").fire();return null;});
                    long end=System.nanoTime()+5_000_000_000L;
                    while(ui(()->(boolean)TestSupport.get(f.app,"useInProgress"))&&System.nanoTime()<end)Thread.sleep(20);
                    ui(()->{TestSupport.check(f.platform.sends==1&&f.board.text.equals(text),"Use failed/full text lost");TestSupport.check(!f.panel.isShowing(),"Panel remained open");return null;});
                }finally{ui(()->{f.close();return null;});}
            });
            TestSupport.finish();
        }finally{Platform.exit();}
    }
}
