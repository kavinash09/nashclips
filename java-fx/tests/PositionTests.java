package nashclip;
import java.nio.file.*;

public class PositionTests {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);var store=new DrawerPositionStore(root);
        TestSupport.run("position.absent-and-roundtrip-both-sides",()->{
            TestSupport.check(store.load().isEmpty(),"Unexpected position");
            for(boolean side:new boolean[]{true,false}){
                var p=new DrawerPositionStore.Position(side,-1920,-200,1920,1080,0.8);store.save(p);
                TestSupport.check(store.load().orElseThrow().equals(p),"Position lost");
            }
        });
        TestSupport.run("position.reject-nan-infinity-bounds",()->{
            TestSupport.rejects(()->new DrawerPositionStore.Position(true,Double.NaN,0,1920,1080,0.5));
            TestSupport.rejects(()->new DrawerPositionStore.Position(true,0,0,0,1080,0.5));
            TestSupport.rejects(()->new DrawerPositionStore.Position(true,0,0,1920,1080,1.1));
            Files.writeString(root.resolve("java-fx-position.properties"),"side=up\n");TestSupport.rejects(store::load);
        });
        TestSupport.finish();
    }
}
