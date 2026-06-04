package MultiCrafter_Momiji.content.blocks;

import MultiCrafter_Momiji.MultiCrafterMomiji;
import mindustry.content.*;
import mindustry.entities.effect.RadialEffect;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.type.LiquidStack;
import mindustry.type.PayloadStack;
import mindustry.world.Block;

public class ExampleBlocks {
    public static Block testFactory;

    public static void load() {

        testFactory = new MultiCrafterMomiji("test-factory") {{
            requirements(Category.crafting, ItemStack.with(Items.copper, 20, Items.lead, 20));
            size = 3;
            health = 300;
            craftEffect = Fx.pulverizeMedium;
            maxEfficiency = 3f;
            overheatScale = 0.5f;
            itemCapacity = 40;
            liquidCapacity = 30f;
            rotate = true;
            alwaysUnlocked = true;

            recipes = new Recipe[] {
                    new Recipe() {{
                        inputItem = new ItemStack(Items.copper, 1);
                        inputLiquid = new LiquidStack(Liquids.water, 0.1f);

                        outputItems = ItemStack.with(Items.lead, 1);
                        outputLiquids = LiquidStack.with(Liquids.slag, 0.1f);

                        updateEffect = Fx.plasticburn;

                        craftTime = 60f;
                        allowOverdrive = true;
                    }},
                    new Recipe() {{
                        consumeItem(Items.beryllium, 1);
                        consumeLiquid(Liquids.ozone, 2f / 60f);
                        consumePower(30f / 60f);

                        outputItems(ItemStack.with(Items.oxide, 1));
                        outputHeat(5f);

                        craftEffect = Fx.none;
                        craftTime = 60f * 2f;
                        allowOverdrive = false;
                    }},
                    new Recipe() {{
                        inputItems = ItemStack.with(Items.tungsten, 2, Items.graphite, 3);
                        inputHeat = 40f;

                        outputItem = new ItemStack(Items.carbide, 1);
                        craftTime = 60f * 2.25f / 2f;

                        craftEffect = new RadialEffect(Fx.surgeCruciSmoke, 4, 90f, 5f);
                    }},
                    new Recipe() {{
                        inputLiquids = LiquidStack.with(Liquids.slag, 20f / 60f, Liquids.arkycite, 40f / 60f);

                        outputLiquid = new LiquidStack(Liquids.water, 20f / 60f);
                        liquidOutputDirections = new int[] {0};
                        outputPower = 1400f / 60f;

                        craftEffect = Fx.none;
                    }},
                    new Recipe() {{
                        inputItems = ItemStack.with(Items.lead, 10, Items.silicon, 10);
                        inputPower = 72f / 60f;

                        outputPayloads = PayloadStack.with(UnitTypes.dagger, 1);

                        craftTime = 60f * 15f;
                    }},
                    new Recipe() {{
                        inputPayloads = PayloadStack.with(Blocks.canvas, 3);
                        inputPower = 90f / 60f;

                        outputPayloads = PayloadStack.with(UnitTypes.stell, 1);

                        inputPayloadCapacity = 10;
                        craftTime = 60f * 35f;
                    }},
                    new Recipe() {{
                        inputPayloads = PayloadStack.with(Blocks.conveyor, 2);

                        outputPayloads = PayloadStack.with(Blocks.junction, 1);
                        outputPower = 30f / 60f;

                        craftTime = 20f;
                        craftEffect = Fx.formsmoke;
                        updateEffect = Fx.plasticburn;
                    }}

            };
        }};
    }
}
