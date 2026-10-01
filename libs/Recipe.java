package momiji.type;

import arc.util.Nullable;
import mindustry.entities.Effect;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.LiquidStack;
import mindustry.world.draw.DrawBlock;
import mindustry.world.meta.Attribute;

public class Recipe{
    public @Nullable ItemStack inputItem;
    public @Nullable ItemStack[] inputItems;
    public @Nullable LiquidStack inputLiquid;
    public @Nullable LiquidStack[] inputLiquids;

    public @Nullable ItemStack outputItem;
    public @Nullable ItemStack[] outputItems;
    public @Nullable LiquidStack outputLiquid;
    public @Nullable LiquidStack[] outputLiquids;
    public @Nullable int[] liquidOutputDirections = {-1};

    // randomOutput
    public @Nullable ItemStack[] randomOutputItems;
    public int emptyWeight = 0;

    //HeatCrafter
    public float outputHeat = -1f;
    public float overheatScale = -1f;
    public float maxEfficiency = -1f;

    //HeatProducer
    public float heatOutput = -1f;
    public boolean splitHeat = false;

    //AttributeCrafter
    public @Nullable Attribute attribute;
    public float baseEfficiency = 1f;
    public float boostScale = 1f;
    public float maxBoost = 1f;
    public float minEfficiency = -1f;
    public float displayEfficiencyScale = 1f;
    public boolean displayEfficiency = true;
    public boolean scaleLiquidConsumption = false;


    public boolean hasItems;
    public boolean hasLiquids;
    public boolean hasPower;
    public boolean outputsItem;
    public boolean outputsLiquid;
    public int itemCapacity = -1;
    public int liquidCapacity = -1;
    public int randomItemCapacity = -1;

    public boolean dumpExtraLiquid = true;
    public boolean ignoreLiquidFullness = false;
    public boolean dumpExtraItem = false;
    public boolean ignoreItemFullness = false;

    public float craftTime = -1f;
    public @Nullable Effect craftEffect;
    public @Nullable Effect updateEffect;
    public float updateEffectChance = -1f;
    public float updateEffectSpread = -1f;
    public float warmupSpeed = -1f;

    public @Nullable DrawBlock drawer;


    protected int weightSum = 0;
    protected @Nullable ItemStack[] fixedOutput;
    protected @Nullable Item[] randomOutput;


    public void init(){
        if(outputItems == null && outputItem != null){
            outputItems = new ItemStack[] {outputItem};
        }
        if(outputLiquids == null && outputLiquid != null){
            outputLiquids = new LiquidStack[] {outputLiquid};
        }
        //write back to outputLiquid, as it helps with sensing
        if(outputLiquid == null && outputLiquids != null && outputLiquids.length > 0){
            outputLiquid = outputLiquids[0];
        }

        outputsItem = (outputItems != null || randomOutputItems != null);
        outputsLiquid = outputLiquids != null;

        if(outputsItem) hasItems = true;
        if(outputsLiquid) hasLiquids = true;
    }

    public void setCraftTime(float craftTime){
        this.craftTime = craftTime <= 0 ? 80f : craftTime;
    }

}
