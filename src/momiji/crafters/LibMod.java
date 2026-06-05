package momiji.crafters;

import momiji.crafters.content.blocks.ExampleBlocks;
import arc.util.*;
import mindustry.mod.*;

public class LibMod extends Mod{

    public LibMod() {
        Log.info("Loaded MomijiLib constructor.");
    }

    @Override
    public void loadContent() {
        // 使用时把下面这一行加到自己模组的 loadContent() 函数里就好.
        ClassMap.classes.put("MultiCrafter", MultiCrafter.class);

        ExampleBlocks.load();
    }
}
