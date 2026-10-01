package momiji;

import arc.util.Log;
import mindustry.mod.ClassMap;
import mindustry.mod.Mod;
import momiji.type.ItemLiquidJunction;
import momiji.type.LinkedBlock;
import momiji.type.LinkedDrill;
import momiji.type.OmniCrafter;

public class LibMod extends Mod {

    public LibMod() {
        Log.info("Loaded MomijiLib constructor.");
    }

    @Override
    public void loadContent() {
        // 使用时把下面这几行加到自己模组的 loadContent() 函数里就好.
        ClassMap.classes.put("MultiCrafter", MultiCrafter.class);
        ClassMap.classes.put("OmniCrafter", OmniCrafter.class);
        ClassMap.classes.put("ItemLiquidJunction", ItemLiquidJunction.class);
        ClassMap.classes.put("LinkedBlock", LinkedBlock.class);
        ClassMap.classes.put("LinkedDrill", LinkedDrill.class);

        // ExampleBlocks.load();
    }

    /*
    @Override
    public void init(){
        AtlasDebug.initKeyBinds();
    }
    */
}
