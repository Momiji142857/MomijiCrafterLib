package momiji.crafters;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.EnumSet;
import arc.struct.IntSet;
import arc.util.Strings;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.core.UI;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.heat.HeatConductor;
import mindustry.world.blocks.heat.HeatConsumer;
import mindustry.world.blocks.heat.HeatProducer;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.HeatCrafter;
import mindustry.world.blocks.production.AttributeCrafter;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.world.draw.DrawDefault;
import mindustry.world.draw.DrawHeatOutput;
import mindustry.world.draw.DrawMulti;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatCat;
import mindustry.world.meta.StatUnit;
import mindustry.world.meta.StatValues;

import java.util.Arrays;

/**
 * @since 2026-05-27
 * @see Block
 * @see GenericCrafter
 * @see HeatCrafter 耗热
 * @see HeatProducer 发热
 * @see AttributeCrafter 环境
 * @author Momiji142857 (with DeepSeek)
 * */
public class OmniCrafter extends HeatCrafter {
    public float heatOutput = 0f;
    public boolean splitHeat = false;

    public float warmupRate = 0.15f;
    public float payloadSpeed = 0.7f, payloadRotateSpeed = 5f;

    public boolean isPayloadRouter = false;

    public OmniCrafter(String name) {
        super (name);
    }

    public void init() {

        if (heatOutput > 0 && drawer instanceof DrawDefault) {
            drawer = new DrawMulti(new DrawDefault(), new DrawHeatOutput());
            flags = EnumSet.of();
        }

        super.init();
    }

    @Override
    public void setStats() {
        stats.timePeriod = craftTime;

        stats.add(Stat.size, "@x@", size, size);

        if (synthetic()) {
            stats.add(Stat.health, health, StatUnit.none);
            if(armor > 0){
                stats.add(Stat.armor, armor, StatUnit.none);
            }
        }

        if (canBeBuilt() && requirements.length > 0) {
            stats.add(Stat.buildTime, buildTime / 60, StatUnit.seconds);
            stats.add(Stat.buildCost, StatValues.items(false, requirements));
        }

        if (instantTransfer) {
            stats.add(Stat.maxConsecutive, 2, StatUnit.none);
        }

        stats.add(new Stat("canOverdrive", StatCat.function), canOverdrive);

        for (var c : consumers) {
            c.display(stats);
        }

        //Note: Power stats are added by the consumers.
        if (hasLiquids) stats.add(Stat.liquidCapacity, liquidCapacity, StatUnit.liquidUnits);
        if (hasItems && itemCapacity > 0) stats.add(Stat.itemCapacity, itemCapacity, StatUnit.items);

        if ((hasItems && itemCapacity > 0) || outputItems != null) {
            stats.add(Stat.productionTime, craftTime / 60f, StatUnit.seconds);
        }

        if (outputItems != null) {
            stats.add(Stat.output, StatValues.items(craftTime, outputItems));
        }

        if (outputLiquids != null) {
            stats.add(Stat.output, StatValues.liquids(1f, outputLiquids));
        }

        if (heatRequirement > 0) {
            stats.add(Stat.input, heatRequirement, StatUnit.heatUnits);
            stats.add(Stat.maxEfficiency, (int)(maxEfficiency * 100f), StatUnit.percent);
        }

        if (heatOutput > 0) {
            stats.add(Stat.output, heatOutput, StatUnit.heatUnits);
        }

    }

    @Override
    public void setBars() {
        addBar("health", entity -> new Bar("stat.health", Pal.health, entity::healthf).blink(Color.white));

        if (consPower != null) {
            boolean buffered = consPower.buffered;
            float capacity = consPower.capacity;

            addBar("power", entity -> new Bar(
                    () -> buffered ? Core.bundle.format("bar.poweramount", Float.isNaN(entity.power.status * capacity) ? "<ERROR>" : UI.formatAmount((int)(entity.power.status * capacity))) :
                            Core.bundle.get("bar.power"),
                    () -> Pal.powerBar,
                    () -> Mathf.zero(consPower.requestedPower(entity)) && entity.power.graph.getPowerProduced() + entity.power.graph.getBatteryStored() > 0f ? 1f : entity.power.status)
            );
        }

        if (hasItems && configurable) {
            addBar("items", entity -> new Bar(
                    () -> Core.bundle.format("bar.items", entity.items.total()),
                    () -> Pal.items,
                    () -> (float)entity.items.total() / itemCapacity)
            );
        }

        if (hasLiquids) {
            boolean added = false;

            for (var consl : consumers) {
                if (consl instanceof ConsumeLiquid liq) {
                    added = true;
                    addLiquidBar(liq.liquid);
                } else if (consl instanceof ConsumeLiquids multi) {
                    added = true;
                    for (var stack : multi.liquids) {
                        addLiquidBar(stack.liquid);
                    }
                }
            }

            if (!added) {
                addLiquidBar(build -> build.liquids.current());
            }
        }

        if (outputLiquids != null && outputLiquids.length > 0) {
            removeBar("liquid");

            for (var stack : outputLiquids) {
                addLiquidBar(stack.liquid);
            }
        }

        if (heatRequirement > 0) {
            addBar("inputHeat", (OmniCrafterBuild entity) ->
                    new Bar(() ->
                            Core.bundle.format("bar.heatpercent", (int)(entity.inputHeat + 0.01f), (int)(entity.efficiencyScale() * 100 + 0.01f)),
                            () -> Pal.lightOrange,
                            () -> entity.inputHeat / heatRequirement));
        }

        if (heatOutput > 0) {
            addBar("outputHeat", (OmniCrafterBuild entity) -> new Bar("bar.heat", Pal.lightOrange,
                    () -> entity.outputHeat / ((entity.efficiencyScale() > 1f) ? (heatOutput * entity.efficiencyScale()) : heatOutput)));
        }
    }

    @Override
    public void addLiquidBar(Liquid liq){
        addBar("liquid-" + liq.name, entity ->{
            if (!liq.unlockedNow()) return null;
            return new Bar(
                    () -> {
                        float current = entity.liquids.get(liq);
                        float fill = current / liquidCapacity;
                        return liq.localizedName + " "
                                + Fonts.getUnicodeStr(liq.name)
                                + fmtNum(current)
                                + ((fill > 0.99) ? "" : "/" + fmtNum(liquidCapacity) + " [lightgray]| " + Strings.fixedBuilder(fill * 100, 0) + "%[]");
                    },
                    liq::barColor,
                    () -> entity.liquids.get(liq) / liquidCapacity
            );
        });
    }

    private static String fmtNum(float value) {
        float abs = Math.abs(value);
        if (abs >= 1000f) return UI.formatAmount((long) value);
        if (abs >= 10f) return Strings.fixed(value, 1);
        if (abs >= 0.01f) return Strings.fixed(value, 2);
        if (abs == 0f) return "0";
        if (abs < 0.000_001f) return "0.00";
        int exponent = (int) Math.floor(Math.log10(abs));
        float mantissa = (float) (value / Math.pow(10, exponent));
        mantissa = Mathf.round(mantissa, 2);
        return mantissa + "[gray]E" + exponent + "[]";
    }

    public class OmniCrafterBuild extends GenericCrafterBuild implements HeatBlock, HeatConsumer {

        public float inputHeat = 0f;
        public float outputHeat = 0f;

        public float[] sideHeat = new float[4];
        public IntSet cameFrom = new IntSet();
        public long lastHeatUpdate = -1;

        @Override
        public void updateTile() {
            updateHeat();

            super.updateTile();

            outputHeat = Mathf.approachDelta(outputHeat, heatOutput * efficiency, warmupRate * delta());
        }

        public void updateHeat() {
            if(lastHeatUpdate == Vars.state.updateId) return;

            lastHeatUpdate = Vars.state.updateId;
            inputHeat = calculateHeat(sideHeat, cameFrom);
        }

        @Override
        public float calculateHeat(float[] sideHeat, IntSet cameFrom) {
            Arrays.fill(sideHeat, 0.0F);
            if (cameFrom != null) {
                cameFrom.clear();
            }

            float heat = 0.0F;

            for(Building build : proximity) {
                if (build != null && build.team == team && build instanceof HeatBlock) {
                    HeatBlock heater;
                    boolean var10000;
                    label59: {
                        heater = (HeatBlock)build;
                        Block var9 = build.block;
                        if (var9 instanceof HeatConductor cond) {
                            if (cond.splitHeat) {
                                var10000 = true;
                                break label59;
                            }
                        }

                        var10000 = false;
                    }

                    boolean split = var10000;
                    if (!build.block.rotate || !split && (relativeTo(build) + 2) % 4 == build.rotation || split && relativeTo(build) != build.rotation) {
                        label70: {
                            float diff = Math.min(Math.abs(build.x - x), Math.abs(build.y - y)) / 8.0F;
                            int contactPoints = Math.min((int)((float)block.size / 2.0F + (float)build.block.size / 2.0F - diff), Math.min(build.block.size, block.size));
                            float add = heater.heat() / (float)build.block.size * (float)contactPoints;
                            if (split) {
                                add /= 3.0F;
                            }

                            int var10001 = Mathf.mod(relativeTo(build), 4);
                            sideHeat[var10001] += add;
                            heat += add;
                        }

                        if (cameFrom != null) {
                            cameFrom.add(build.id);
                            if (build instanceof HeatConductor.HeatConductorBuild hc) {
                                cameFrom.addAll(hc.cameFrom);
                            }
                        }

                        if (heater instanceof HeatConductor.HeatConductorBuild cond) {
                            cond.updateHeat();
                        }
                    }
                }
            }

            return heat;
        }

        @Override
        public float warmup() {
            return outputHeat;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.f(outputHeat);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            outputHeat = read.f();
        }

        //region HeatCrafter

        @Override
        public boolean shouldConsume() {
            return (heatRequirement <= 0f || inputHeat > 0) && super.shouldConsume();
        }

        @Override
        public float heatRequirement() {
            return heatRequirement;
        }

        @Override
        public float[] sideHeat() {
            return sideHeat;
        }

        @Override
        public float warmupTarget() {
            return (heatRequirement > 0) ? Mathf.clamp(inputHeat / heatRequirement) : 1f;
        }

        @Override
        public float efficiencyScale() {
            if (heatRequirement <= 0f) return 1f;

            float over = Math.max(inputHeat - heatRequirement, 0f);
            return Math.min(Mathf.clamp(inputHeat / heatRequirement) + over / heatRequirement * overheatScale, maxEfficiency);
        }

        //endregion

        //region HeatProducer

        @Override
        public float heatFrac() {
            return (heatOutput > 0) ? (outputHeat / heatOutput) / (splitHeat ? 3f : 1) : 0f;
        }

        @Override
        public float heat() {
            return outputHeat;
        }

        //endregion
    }

}
