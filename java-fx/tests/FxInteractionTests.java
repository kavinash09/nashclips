package nashclip;
import java.awt.Point;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;

public class FxInteractionTests {
    static class Fixture implements AutoCloseable{
        Stage panel=new Stage(StageStyle.TRANSPARENT);BorderPane root=new BorderPane();HBox header=new HBox();EdgeDrawer drawer;
        DrawerPositionStore.Position position;
        Fixture(){root.setTop(header);panel.setScene(new Scene(root,760,340));panel.setOpacity(0);drawer=new EdgeDrawer(panel,header,false,null,760,340,p->position=p);for(Window w:Window.getWindows())w.setOpacity(0);}
        public void close(){drawer.close();}
    }
    static void event(Node header,javafx.event.EventType<MouseEvent> kind,double x,double y){
        Event.fireEvent(header,new MouseEvent(kind,10,10,x,y,MouseButton.PRIMARY,1,false,false,false,false,true,false,false,false,false,false,new PickResult(header,10,10)));
    }
    public static void main(String[] args)throws Exception{
        Platform.startup(()->Platform.setImplicitExit(false));
        try{
            TestSupport.run("fx.drawer.header-drag.docks-both-sides.position-callback",()->{
                var f=FxFeatureTests.ui(Fixture::new);
                try{
                    FxFeatureTests.ui(()->{f.drawer.showDrawer();return null;});Thread.sleep(400);
                    for(boolean right:new boolean[]{false,true}){
                        FxFeatureTests.ui(()->{var s=Screen.getPrimary().getVisualBounds();double x=right?s.getMaxX()-60:s.getMinX()+60;double y=s.getMinY()+s.getHeight()/2;
                            event(f.header,MouseEvent.MOUSE_PRESSED,f.panel.getX()+60,f.panel.getY()+20);event(f.header,MouseEvent.MOUSE_DRAGGED,x,y);event(f.header,MouseEvent.MOUSE_RELEASED,x,y);return null;});
                        Thread.sleep(320);
                        FxFeatureTests.ui(()->{TestSupport.check(f.position!=null&&f.position.rightSide()==right,"Position callback/dock side");var h=(Stage)TestSupport.get(f.drawer,"handle");
                            TestSupport.check(right?h.getX()>=f.panel.getX()+f.panel.getWidth()-1:h.getX()+h.getWidth()<=f.panel.getX()+1,"Arrow not outer edge");return null;});
                    }
                }finally{FxFeatureTests.ui(()->{f.close();return null;});}
            });
            TestSupport.run("fx.drawer.pin.modal.resize-save",()->{
                var f=FxFeatureTests.ui(Fixture::new);var saves=new AtomicInteger();
                try{
                    FxFeatureTests.ui(()->{f.drawer.setPinned(true);f.drawer.setResizeListener((w,h)->saves.incrementAndGet());return null;});Thread.sleep(400);
                    FxFeatureTests.ui(()->{f.drawer.onGlobalClick(new Point(-100000,-100000));TestSupport.check(f.panel.isShowing(),"Pinned closed");
                        f.drawer.setPinned(false);f.drawer.setModalInteraction(true);f.drawer.onGlobalClick(new Point(-100000,-100000));TestSupport.check(f.panel.isShowing(),"Modal closed");f.drawer.setModalInteraction(false);
                        double width=f.panel.getWidth(),x=f.panel.getX(),y=f.panel.getY();f.drawer.beginResize(EdgeDrawer.ResizeEdge.LEFT,x,y);f.drawer.endResize(x+80,y);
                        TestSupport.check(f.panel.getWidth()<width&&saves.get()==1,"Resize callback");TestSupport.check(!f.drawer.canResizeFrom(EdgeDrawer.ResizeEdge.RIGHT),"Wrong edge enabled");return null;});
                }finally{FxFeatureTests.ui(()->{f.close();return null;});}
            });
            TestSupport.run("fx.notice.hidden-deferral.auto-expiry.manual-dismiss",()->{
                var f=FxFeatureTests.ui(Fixture::new);var notice=FxFeatureTests.ui(()->{var n=new SizeNotice(f.panel,f.root);n.showSize(70000,2);return n;});
                try{
                    Thread.sleep(2200);FxFeatureTests.ui(()->{TestSupport.check(notice.isVisible(),"Hidden notice expired");f.drawer.showDrawer();return null;});
                    Thread.sleep(2500);FxFeatureTests.ui(()->{TestSupport.check(!notice.isVisible(),"Auto-expiry failed");notice.showSize(70000,0);return null;});
                    Thread.sleep(2200);FxFeatureTests.ui(()->{TestSupport.check(notice.isVisible(),"Manual notice expired");FxFeatureTests.button(notice,"Dismiss size notice").fire();TestSupport.check(!notice.isVisible(),"Dismiss failed");return null;});
                }finally{FxFeatureTests.ui(()->{notice.close();f.close();return null;});}
            });
            TestSupport.finish();
        }finally{Platform.exit();}
    }
}
