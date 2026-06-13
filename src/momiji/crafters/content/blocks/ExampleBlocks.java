package momiji.crafters.content.blocks;

import mindustry.gen.Sounds;
import mindustry.world.draw.*;
import momiji.crafters.ItemLiquidJunction;
import momiji.crafters.MultiCrafter;
import mindustry.content.*;
import mindustry.entities.effect.RadialEffect;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.type.LiquidStack;
import mindustry.type.PayloadStack;
import mindustry.world.Block;
import momiji.crafters.OmniCrafter;

import static mindustry.type.ItemStack.with;

public class ExampleBlocks {
    public static Block testFactory, testFactoryO, itemLiquidJunction;

    public static void load() {

        testFactory = new MultiCrafter("test-factory") {{
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

        testFactoryO = new OmniCrafter("test-factory-o") {{
            requirements(Category.crafting, with(Items.tungsten, 120, Items.graphite, 80, Items.silicon, 100, Items.beryllium, 120));
            size = 3;

            outputItem = new ItemStack(Items.oxide, 1);
            researchCostMultiplier = 1.1f;

            consumeLiquid(Liquids.ozone, 2f / 60f);
            consumeItem(Items.beryllium);
            consumePower(0.5f);

            outputLiquid = new LiquidStack(Liquids.water, 0.01f);

            rotateDraw = false;

            drawer = new DrawMulti(new DrawRegion("-bottom"), new DrawLiquidRegion(), new DrawDefault(), new DrawHeatOutput());
            ambientSound = Sounds.loopExtract;
            ambientSoundVolume = 0.08f;

            regionRotated1 = 2;
            craftTime = 60f * 2f;
            liquidCapacity = 30f;
            heatOutput = 5f;

            heatRequirement = 10f;
            hasLiquids = true;
        }};

        itemLiquidJunction = new ItemLiquidJunction("item-liquid-junction") {{
            requirements(Category.distribution, with(Items.copper, 3, Items.graphite, 4, Items.metaglass, 8));
            speed = 26;
            capacity = 6;
            health = 30;
            buildCostMultiplier = 6f;
            solid = false;
        }};
    }
}
