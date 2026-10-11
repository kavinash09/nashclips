import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

public class SwingInteractionTests {
    static <T>T ui(Callable<T> action)throws Exception{return SwingFeatureTests.ui(action);}
    static final class Fixture implements AutoCloseable{
        final JFrame panel=new JFrame();final JPanel header=new JPanel();final EdgeDrawer drawer;
        Fixture(){panel.setUndecorated(true);panel.setFocusableWindowState(false);panel.setOpacity(0);panel.add(header);drawer=new EdgeDrawer(panel);drawer.installHeaderDrag(header);
            for(Window w:Window.getWindows())if(!(w instanceof Frame f)||f.isUndecorated())w.setOpacity(0);}
        public void close(){drawer.close();}
    }
    static void event(JPanel header,int kind,Point point){header.dispatchEvent(new MouseEvent(header,kind,System.currentTimeMillis(),MouseEvent.BUTTON1_DOWN_MASK,10,10,point.x,point.y,1,false,MouseEvent.BUTTON1));}
    public static void main(String[] args)throws Exception{
        TestSupport.run("swing.drawer.header-drag.docks-left-and-right",()->{
            var f=ui(Fixture::new);
            try{
                ui(()->{f.drawer.showDrawer();return null;});Thread.sleep(400);
                for(boolean right:new boolean[]{false,true}){
                    ui(()->{Rectangle screen=(Rectangle)TestSupport.get(f.drawer,"screen");
                        Point start=new Point(f.panel.getX()+60,f.panel.getY()+20);Point end=new Point(right?screen.x+screen.width-60:screen.x+60,screen.y+screen.height/2);
                        event(f.header,MouseEvent.MOUSE_PRESSED,start);event(f.header,MouseEvent.MOUSE_DRAGGED,end);event(f.header,MouseEvent.MOUSE_RELEASED,end);return null;});
                    Thread.sleep(320);
                    ui(()->{TestSupport.check((boolean)TestSupport.get(f.drawer,"rightSide")==right,"Wrong docking side");
                        Window handle=(Window)TestSupport.get(f.drawer,"handle");
                        TestSupport.check(right?handle.getX()>=f.panel.getX()+f.panel.getWidth():handle.getX()+handle.getWidth()<=f.panel.getX(),"Arrow not on outer edge");return null;});
                }
            }finally{ui(()->{f.close();return null;});}
        });
        TestSupport.run("swing.drawer.pin.modal.resize-and-save-callback",()->{
            var f=ui(Fixture::new);var saved=new AtomicInteger();
            try{
                ui(()->{f.drawer.setPinned(true);f.drawer.setResizeListener((w,h)->saved.incrementAndGet());return null;});Thread.sleep(400);
                ui(()->{f.drawer.onGlobalClick(new Point(-100000,-100000));TestSupport.check(f.panel.isVisible(),"Pinned auto-closed");
                    f.drawer.setPinned(false);f.drawer.setModalInteraction(true);f.drawer.onGlobalClick(new Point(-100000,-100000));TestSupport.check(f.panel.isVisible(),"Modal auto-closed");
                    f.drawer.setModalInteraction(false);Point p=new Point(f.panel.getX(),f.panel.getY());int width=f.panel.getWidth();
                    f.drawer.beginResize(EdgeDrawer.ResizeEdge.LEFT,p);f.drawer.endResize(new Point(p.x+80,p.y));
                    TestSupport.check(f.panel.getWidth()<width&&saved.get()==1,"Resize or save callback failed");
                    TestSupport.check(!f.drawer.canResizeFrom(EdgeDrawer.ResizeEdge.RIGHT),"Outer resize edge enabled");return null;});
            }finally{ui(()->{f.close();return null;});}
        });
        TestSupport.run("swing.notice.hidden-deferral.auto-expiry.manual-dismiss",()->{
            var f=ui(Fixture::new);var notice=ui(()->{var n=new SizeNotice();f.panel.setGlassPane(n);n.showSize(70000,2);return n;});
            try{
                Thread.sleep(2200);ui(()->{TestSupport.check(notice.isVisible(),"Hidden notice expired");f.drawer.showDrawer();return null;});
                Thread.sleep(2500);ui(()->{TestSupport.check(!notice.isVisible(),"Notice did not expire");notice.showSize(70000,0);return null;});
                Thread.sleep(2200);ui(()->{TestSupport.check(notice.isVisible(),"Manual notice expired");SwingFeatureTests.button(notice,"Dismiss size notice").doClick(0);TestSupport.check(!notice.isVisible(),"Dismiss failed");return null;});
            }finally{ui(()->{notice.close();f.close();return null;});}
        });
        TestSupport.finish();
    }
}
