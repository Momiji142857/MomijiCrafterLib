package momiji.type;

import arc.util.Nullable;
import mindustry.entities.Effect;
import mindustry.world.Block;

public class MultiCrafterB extends Block{
    public Recipe[] recipes;

    public float craftTime = -1f;
    public @Nullable Effect craftEffect;
    public @Nullable Effect updateEffect;
    public float updateEffectChance = -1f;
    public float updateEffectSpread = -1f;
    public float warmupSpeed = -1f;

    public MultiCrafterB(String name){
        super(name);
    }

    @Override
    public void init(){
        super.init();

        if(recipes == null || recipes.length == 0) recipes = new Recipe[] {new Recipe()};
    }

}
