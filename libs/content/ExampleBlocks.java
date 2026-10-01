package momiji.content;

import mindustry.content.*;
import mindustry.entities.effect.RadialEffect;
import mindustry.gen.Sounds;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.type.LiquidStack;
import mindustry.type.PayloadStack;
import mindustry.world.Block;
import mindustry.world.draw.*;
import mindustry.world.meta.Attribute;
import mindustry.world.meta.Env;
import momiji.MultiCrafter;
import momiji.type.*;

import static mindustry.type.ItemStack.with;

public class ExampleBlocks {
    public static Block testFactory, testFactoryO, itemLiquidJunction, LinkedContainer, LinkedMechanicalDrill, itemBridgePro;

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
            rotateDraw = false;
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
            researchCostMultiplier = 0.3f;

            consumeLiquid(Liquids.ozone, 2f / 60f);
            consumeItem(Items.beryllium);
            consumePower(0.5f);

            attribute = Attribute.heat;
            randomItemCapacity = 3;
            randomResults = ItemStack.with(Items.scrap, 1);
            emptyWeight = 9;
            itemCapacity = 30;

            rotateDraw = false;
            rotate = true;

            drawer = new DrawMulti(new DrawRegion("-bottom"), new DrawLiquidRegion(), new DrawDefault(), new DrawHeatOutput());
            ambientSound = Sounds.loopExtract;
            ambientSoundVolume = 0.08f;

            regionRotated1 = 2;
            craftTime = 60f * 2f;
            liquidCapacity = 30f;
            heatOutput = 5f;

            maxEfficiency = 2f;

            heatRequirement = 10f;
            hasLiquids = true;
        }};

        itemLiquidJunction = new ItemLiquidJunction("item-liquid-junction") {{
            requirements(Category.distribution, with(Items.copper, 3, Items.graphite, 4, Items.metaglass, 8));
            speed = 26;
            capacity = 6;
            health = 30;
            buildCostMultiplier = 6f;
        }};

        LinkedContainer = new LinkedBlock("linked-container"){{
            requirements(Category.effect, with(Items.titanium, 100));
            size = 2;
            itemCapacity = 300;
            scaledHealth = 55;
        }};

        LinkedMechanicalDrill = new LinkedDrill("linked-mechanical-drill"){{
            requirements(Category.production, with(Items.copper, 12));
            tier = 2;
            drillTime = 600;
            size = 2;
            //mechanical drill doesn't work in space
            envEnabled ^= Env.space;
            researchCost = with(Items.copper, 10);

            consumeLiquid(Liquids.water, 0.05f).boost();
        }};

        itemBridgePro = new BatchDumpBridge("bridge-conveyor-pro"){{
            requirements(Category.distribution, with(Items.phaseFabric, 5, Items.silicon, 7, Items.lead, 10, Items.graphite, 10));
            range = 12;
            transportTime = 2f;
            arrowPeriod = 0.9f;
            arrowTimeScl = 2.75f;
            hasPower = true;
            pulse = true;
            envEnabled |= Env.space;
            consumePower(0.30f);
        }};

    }
}
