package momiji.type;

import mindustry.world.blocks.production.GenericCrafter;

public class OmniCrafterB extends GenericCrafter{
    public Recipe recipe = new Recipe();


    public OmniCrafterB(String name){
        super(name);
    }

    public void init(){
        super.init();
        recipe.init();

    }

}
