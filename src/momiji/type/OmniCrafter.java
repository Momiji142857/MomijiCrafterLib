package momiji.type;

import arc.Core;
import arc.func.Func;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.core.UI;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Iconc;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.heat.HeatConsumer;
import mindustry.world.blocks.heat.HeatProducer;
import mindustry.world.blocks.production.AttributeCrafter;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.HeatCrafter;
import mindustry.world.blocks.production.Separator;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.world.draw.DrawDefault;
import mindustry.world.draw.DrawHeatOutput;
import mindustry.world.draw.DrawMulti;
import mindustry.world.meta.Attribute;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.meta.StatValues;

import static mindustry.world.meta.StatValues.stack;

/**
 * 目标是啥都能干的工厂.
 *
 * @author Momiji142857 (with EVE, DeepSeek)
 * @see Block
 * @see GenericCrafter
 * @see HeatCrafter 耗热
 * @see HeatProducer 发热
 * @see AttributeCrafter 环境
 * @see Separator 随机产出
 * @since 2026-05-27
 *
 */
public class OmniCrafter extends GenericCrafter{
    /** 控制热量输出量的变化速度. */
    public float warmupRate = 0.15f;
    /** 未使用. */
    public float payloadSpeed = 0.7f, payloadRotateSpeed = 5f;
    /** 未使用. */
    public boolean isPayloadRouter = false;

    /** 多余产物是否销毁. */
    public boolean dumpExtraItem = false;
    /** 多余随机产物是否销毁. */
    public boolean dumpRandomItem = false;
    /** 产物是否堵塞生产. */
    public boolean ignoreItemFullness = false;

    // HeatCrafter

    /** Base heat requirement for 100% efficiency. */
    public float heatRequirement = 0f;
    /** After heat meets this requirement, excess heat will be scaled by this number. */
    public float overheatScale = 1f;
    /** Maximum possible efficiency after overheat. */
    public float maxEfficiency = 4f;

    // HeatProducer

    /** 生产效率为 100% 时输出的热量. */
    public float heatOutput = 0f;
    /** 是否分配热量. */
    public boolean splitHeat = false;

    // AttributeCrafter

    /** 这一块与 {@link AttributeCrafter} 里的一样 */
    public @Nullable Attribute attribute;
    public float baseEfficiency = 1f;
    public float boostScale = 1f;
    public float maxBoost = 1f;
    public float minEfficiency = -1f;
    public float displayEfficiencyScale = 1f;
    public boolean displayEfficiency = true;
    public boolean scaleLiquidConsumption = false;

    // randomOutput

    /** 随机产出的物品列表, 使用方式与 {@link Separator} 里的 {@link Separator#results} 相同. */
    public @Nullable ItemStack[] randomResults;
    /** 随机产出物品的总容量, 默认与 {@link Block#itemCapacity} 相同. */
    public int randomItemCapacity = -1;
    /** 轮空权重, 相当于 randomResults 中添加了 emptyWeight 个空物品. */
    public int emptyWeight = 0;

    /** 记录随机产出物品的总权重. */
    protected int weightSum = 0;
    /** 用于 {@link OmniCrafterBuild shouldConsume()} 函数, 记录固定产出的物品和数量, 若同时会随机产出, 则对应的 amount + 1. */
    protected @Nullable ItemStack[] fixedOutputItems;
    /** 用于 {@link OmniCrafterBuild shouldConsume()} 函数, 记录随机产出的物品, 排除同时会固定产出的物品 */
    protected @Nullable Item[] randomOutputItems;


    public OmniCrafter(String name){
        super(name);
    }

    @Override
    public void init(){
        super.init();

        if(heatOutput > 0 && drawer instanceof DrawDefault){
            drawer = new DrawMulti(new DrawDefault(), new DrawHeatOutput());
        }

        if(randomItemCapacity < 0) randomItemCapacity = itemCapacity;

        ItemStack[] fixedOutput = outputItems == null ? null : ItemStack.copy(outputItems);
        if(randomResults != null){
            ConsumeItems consItems = findConsumer(c -> c instanceof ConsumeItems);

            ObjectSet<Item> consItemSet = new ObjectSet<>();
            if(consItems != null){
                for(ItemStack stack : consItems.items) consItemSet.add(stack.item);
            }

            ObjectSet<Item> fixedItemSet = new ObjectSet<>();
            if(fixedOutput != null){
                for(ItemStack s : fixedOutput) fixedItemSet.add(s.item);
            }

            Seq<Item> randomList = new Seq<>();

            for(ItemStack s : randomResults){
                Item item = s.item;

                if(consItemSet.contains(item)) continue;

                if(fixedItemSet.contains(item)){
                    if(fixedOutput != null){
                        for(ItemStack fs : fixedOutput){
                            if(fs.item == item){
                                fs.amount += 1;
                                break;
                            }
                        }
                    }
                }else{
                    randomList.add(item);
                }
            }

            randomOutputItems = randomList.toArray(Item.class);

            for(ItemStack stack : randomResults) weightSum += stack.amount;
            weightSum += emptyWeight;
        }
        fixedOutputItems = fixedOutput;
    }

    @Override
    public void setStats(){
        super.setStats();

        // length == 0 会出现多余的空白部分
        stats.remove(Stat.output);
        if(outputItems != null && outputItems.length != 0){
            stats.add(Stat.output, StatValues.items(craftTime, outputItems));
        }
        if(outputLiquids != null && outputLiquids.length != 0){
            stats.add(Stat.output, StatValues.liquids(1f, outputLiquids));
        }

        // randomOutput
        if(randomResults != null){
            stats.add(Stat.output, table -> {
                for(ItemStack stack : randomResults){
                    table.add(displayRandomItemPercent(stack.item, ((float) stack.amount / weightSum * 100), true)).padRight(5);
                }
            });
        }

        // HeatCrafter
        if(heatRequirement > 0){
            stats.add(Stat.input, heatRequirement, StatUnit.heatUnits);
            stats.add(Stat.maxEfficiency, (int) (maxEfficiency * 100f), StatUnit.percent);
        }

        // HeatProducer
        if(heatOutput > 0){
            stats.add(Stat.output, heatOutput, StatUnit.heatUnits);
        }

        // AttributeCrafter
        if(attribute != null){
            stats.add(baseEfficiency <= 0.0001f ? Stat.tiles : Stat.affinities, attribute, floating, boostScale * size * size, !displayEfficiency);
        }

    }

    public Table displayRandomItemPercent(Item item, float percent, boolean showName){
        {
            Table t = new Table();
            t.add(stack(item, 0, !showName));
            t.add((showName ? item.localizedName + "\n" : "") + "[lightgray]" + fmtNum(percent) + "%").padLeft(2).padRight(5).style(Styles.outlineLabel);
            return t;
        }
    }

    @Override
    public void setBars(){
        super.setBars();

        removeBar("health");
        addBar("health", entity -> new Bar(() -> {
            float healthf = entity.healthf();
            return Iconc.add + " " + longFmtNum(entity.health) + " " + ((healthf > 0.99) ? "" : ("/" + longFmtNum(entity.maxHealth) + " [lightgray]| " + Strings.fixedBuilder(healthf * 100, 0) + "%[]"));
        }, () -> Pal.health, entity::healthf));

        if(consPower != null){
            removeBar("power");
            removeBar("inputPower");
            boolean buffered = consPower.buffered;

            addBar("inputPower", entity -> new Bar(() -> {
                float fill = entity.power.status;
                if(buffered){
                    float capacity = consPower.capacity;
                    float amount = fill * capacity;
                    return Core.bundle.format("bar.poweramount", Float.isNaN(amount) ? "<ERROR>" : longFmtNum(amount) + ((fill > 0.99f) ? "" : "/" + longFmtNum(capacity) + " [lightgray]| " + Strings.fixedBuilder(fill * 100, 0) + "%[]"));
                }else{
                    float usage = consPower.usage * 60 * entity.timeScale();
                    return Iconc.power + " " + longFmtNum(fill * usage) + ((fill > 0.99f) ? "" : "/" + Strings.autoFixed(usage, 2)) + ((entity.efficiency <= 0) ? " [lightgray]| 0%[]" : (fill > 0.99f) ? "" : " [lightgray]| " + Strings.fixedBuilder(fill * 100, 0) + "%[]");
                }
            }, () -> Pal.powerBar, () -> Mathf.zero(consPower.requestedPower(entity)) && entity.power.graph.getPowerProduced() + entity.power.graph.getBatteryStored() > 0f ? 1f : entity.power.status));
        }

        if(hasPower && outputsPower){
            removeBar("power");
            removeBar("outputPower");
            addBar("outputPower", (OmniCrafterBuild entity) -> new Bar(() -> Core.bundle.format("bar.poweroutput", Strings.fixed(entity.getPowerProduction() * 60 * entity.timeScale(), 1)), () -> Pal.powerBar, () -> entity.productionEfficiency));
        }

        if(hasLiquids){
            removeBar("liquid");
            boolean added = false;

            for(var consl : consumers){
                /* 这里与原版行为略有不同
                 * 如果 consumers 中存在含有相同的液体的 ConsumeLiquid, 且该种液体的第一个和最后一个之间含有其他液体
                 * 液体条的顺序会与原版逻辑下的顺序不同
                 * 原版会出现在第一次的位置, 这里的会出现在最后一次的位置
                 * */
                if(consl instanceof ConsumeLiquid liq){
                    added = true;
                    removeBar("liquid-" + liq.liquid.name);
                    addLiquidBar(liq.liquid);
                }else if(consl instanceof ConsumeLiquids multi){
                    added = true;
                    for(var stack : multi.liquids){
                        removeBar("liquid-" + stack.liquid.name);
                        addLiquidBar(stack.liquid);
                    }
                }
            }

            if(!added){
                addLiquidBar(build -> build.liquids.current());
            }
        }

        if(outputLiquids != null && outputLiquids.length > 0){
            removeBar("liquid");

            for(var stack : outputLiquids){
                removeBar("liquid-" + stack.liquid.name);
                addLiquidBar(stack.liquid);
            }
        }

        if(heatRequirement > 0){
            removeBar("inputHeat");
            addBar("inputHeat", (OmniCrafterBuild entity) -> new Bar(() -> Core.bundle.format("bar.heatpercent", (int) (entity.inputHeat + 0.01f), (int) (entity.efficiencyScale() * 100 + 0.01f)), () -> Pal.lightOrange, () -> entity.inputHeat / heatRequirement));
        }

        if(heatOutput > 0){
            removeBar("outputHeat");
            addBar("outputHeat", (OmniCrafterBuild entity) -> new Bar("bar.heat", Pal.lightOrange, () -> entity.outputHeat / ((entity.efficiencyScale() > 1f) ? (heatOutput * entity.efficiencyScale()) : heatOutput)));
        }

        if(displayEfficiency && attribute != null){
            removeBar("efficiency");
            addBar("efficiency", (OmniCrafterBuild entity) -> new Bar(() -> Core.bundle.format("bar.efficiency", (int) (entity.efficiencyMultiplier() * 100 * displayEfficiencyScale)), () -> Pal.lightOrange, entity::efficiencyMultiplier));
        }

    }

    @Override
    public void addLiquidBar(Liquid liq){
        addBar("liquid-" + liq.name, entity -> !liq.unlockedNow() ? null : new Bar(() -> {
            float current = entity.liquids.get(liq);
            float fill = current / liquidCapacity;
            return liq.localizedName + " " + Fonts.getUnicodeStr(liq.name) + " " + fmtNum(current) + ((fill > 0.99f) ? "" : "/" + fmtNum(liquidCapacity) + " [lightgray]| " + Strings.fixedBuilder(fill * 100, 0) + "%[]");
        }, liq::barColor, () -> entity.liquids.get(liq) / liquidCapacity));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Building> void addLiquidBar(Func<T, Liquid> current){
        addBar("liquid", entity -> new Bar(() -> {
            Liquid liquid = current.get((T) entity);
            if(liquid == null || entity.liquids.get(liquid) <= 0.001f) return Core.bundle.get("bar.liquid");

            float amount = entity.liquids.get(liquid);
            float fill = amount / liquidCapacity;
            return liquid.localizedName + " " + Fonts.getUnicodeStr(liquid.name) + " " + fmtNum(amount) + ((fill > 0.99f) ? "" : "/" + fmtNum(liquidCapacity) + " [lightgray]| " + Strings.fixedBuilder(fill * 100, 0) + "%[]");
        }, () -> current.get((T) entity) == null ? Color.clear : current.get((T) entity).barColor(), () -> current.get((T) entity) == null ? 0f : entity.liquids.get(current.get((T) entity)) / liquidCapacity));
    }

    private static String fmtNum(float number){
        if(Float.isInfinite(number)) return number > 0 ? "∞" : "-∞";
        if(Float.isNaN(number)) return "NaN";
        if(number == 0f) return "0";

        String sign = number < 0 ? "-" : "";
        float abs = Math.abs(number);

        if(abs >= 1_000_000_000f){
            return sign + Strings.autoFixed(abs / 1_000_000_000f, 2) + "[gray]" + UI.billions + "[]";
        }else if(abs >= 1_000_000f){
            return sign + Strings.autoFixed(abs / 1_000_000f, 2) + "[gray]" + UI.millions + "[]";
        }else if(abs >= 100_000f){
            return sign + Strings.autoFixed(abs / 1000f, 0) + "[gray]" + UI.thousands + "[]";
        }else if(abs >= 10_000f){
            return sign + Strings.autoFixed(abs / 1000f, 1) + "[gray]" + UI.thousands + "[]";
        }else if(abs >= 1000f){
            return sign + Strings.autoFixed(abs / 1000f, 2) + "[gray]" + UI.thousands + "[]";
        }

        if(abs >= 100f){
            return sign + Strings.fixed(abs, 0);
        }else if(abs >= 10f){
            return sign + Strings.fixed(abs, 1);
        }else if(abs >= 0.01f){
            return sign + Strings.fixed(abs, 2);
        }

        if(abs < 0.000_001f) return "0.00";
        int exponent = (int) Math.floor(Math.log10(abs));
        float mantissa = (float) (abs / Math.pow(10, exponent));
        mantissa = Mathf.round(mantissa, 2);
        return sign + mantissa + "[gray]E" + exponent + "[]";
    }

    private static String longFmtNum(float number){
        if(Float.isInfinite(number)) return number > 0 ? "∞" : "-∞";
        if(Float.isNaN(number)) return "NaN";
        if(number == 0f) return "0";

        String sign = number < 0 ? "-" : "";
        float abs = Math.abs(number);

        if(abs >= 100_000_000f){
            return sign + Strings.autoFixed(abs / 1_000_000f, 2) + "[gray]" + UI.millions + "[]";
        }else if(abs >= 1_000_000f){
            return sign + Strings.autoFixed(abs / 1000f, 1) + "[gray]" + UI.thousands + "[]";
        }else if(abs >= 100_000f){
            return sign + Strings.autoFixed(abs / 1000f, 2) + "[gray]" + UI.thousands + "[]";
        }

        if(abs >= 0.01f){
            return sign + Strings.autoFixed(abs, 2);
        }

        if(abs < 0.000_001f) return "0.00";
        int exponent = (int) Math.floor(Math.log10(abs));
        float mantissa = (float) (abs / Math.pow(10, exponent));
        mantissa = Mathf.round(mantissa, 2);
        return sign + mantissa + "[gray]E" + exponent + "[]";
    }

    @Override
    public boolean outputsItems(){
        return outputItems != null || randomResults != null;
    }

    //region AttributeCrafter

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid){
        super.drawPlace(x, y, rotation, valid);

        if(attribute == null || !displayEfficiency) return;

        drawPlaceText(Core.bundle.format("bar.efficiency", (int) ((baseEfficiency + Math.min(maxBoost, boostScale * sumAttribute(attribute, x, y))) * 100f)), x, y, valid);
    }

    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation){
        if(attribute == null) return true;

        //make sure there's enough efficiency at this location
        return baseEfficiency + tile.getLinkedTilesAs(this, tempTiles).sumf(other -> other.floor().attributes.get(attribute)) >= minEfficiency;
    }

    //endregion

    public class OmniCrafterBuild extends GenericCrafterBuild implements HeatBlock, HeatConsumer{
        /** 未使用 */
        public float productionEfficiency = 0.0f;

        // HeatCrafter
        public float[] sideHeat = new float[4];
        public float inputHeat = 0f;

        // HeatProducer
        public float outputHeat = 0f;

        // AttributeCrafter
        public float attrsum;

        // randomOutput
        public int seed;


        @Override
        public boolean shouldConsume(){
            // Heat
            if(heatRequirement > 0f && inputHeat <= 0) return false;

            boolean anyOutput = false;
            boolean allFull  = true;

            if(!ignoreItemFullness){

                if(fixedOutputItems != null){
                    anyOutput = true;
                    for(var output : fixedOutputItems){
                        if(items.get(output.item) + output.amount > itemCapacity){
                            if(!dumpExtraItem) return false;

                        }else allFull = false;
                    }
                }

                if(randomResults != null){
                    anyOutput = true;
                    int total = 0;
                    if(randomOutputItems != null){
                        for(Item output : randomOutputItems){
                            total += items.get(output);
                            if(total >= randomItemCapacity){
                                if(!dumpRandomItem) return false;

                            }else allFull = false;
                        }
                    }
                }
            }

            if(!ignoreLiquidFullness && outputLiquids != null){
                anyOutput = true;
                for(var output : outputLiquids){
                    if(liquids.get(output.liquid) >= liquidCapacity - 0.001f){
                        if(!dumpExtraLiquid) return false;

                    }else allFull = false;
                }
            }

            if(anyOutput && allFull) return false;

            return enabled;
        }

        @Override
        public void updateTile(){
            inputHeat = calculateHeat(sideHeat);

            if(efficiency > 0){
                progress += getProgressIncrease(craftTime);
                warmup = Mathf.approachDelta(warmup, warmupTarget(), warmupSpeed);

                if(outputLiquids != null){
                    float inc = getProgressIncrease(1f);
                    for(var output : outputLiquids){
                        handleLiquid(this, output.liquid, Math.min(output.amount * inc, liquidCapacity - liquids.get(output.liquid)));
                    }
                }

                if(wasVisible && Mathf.chanceDelta(updateEffectChance)){
                    updateEffect.at(x + Mathf.range(size * updateEffectSpread), y + Mathf.range(size * updateEffectSpread));
                }
            }else{
                warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
            }

            totalProgress += warmup * Time.delta;

            if(progress >= 1f){
                craft();
            }

            dumpOutputs();

            outputHeat = Mathf.approachDelta(outputHeat, heatOutput * efficiency, warmupRate * delta());
        }

        @Override
        public void dumpOutputs(){
            super.dumpOutputs();

            if(randomOutputItems != null && timer(timerDump, dumpTime / timeScale)){
                for(Item output : randomOutputItems){
                    dump(output);
                }
            }
        }

        @Override
        public void craft(){
            consume();

            if(outputItems != null){
                for(var output : outputItems){
                    Item item = output.item;
                    if(items.get(item) < itemCapacity){
                        for(int i = 0; i < output.amount; i++) offload(output.item);
                    }
                }
            }

            if(randomResults != null){
                int i = Mathf.randomSeed(seed++, 0, weightSum - 1);
                int count = 0;
                Item item = null;

                for(ItemStack stack : randomResults){
                    if(i >= count && i < count + stack.amount){
                        item = stack.item;
                        break;
                    }
                    count += stack.amount;
                }

                if(item != null && items.get(item) < randomItemCapacity) offload(item);
            }

            if(wasVisible){
                craftEffect.at(x, y);
            }
            progress %= 1f;
        }

        //下面这个可以让热量自循环, 但是可能造成死循环.
        /*
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
        */

        @Override
        public float efficiencyScale(){
            float attrScale = super.efficiencyScale();

            if(attribute != null){
                attrScale *= scaleLiquidConsumption ? efficiencyMultiplier() : 1f;
            }

            if(heatRequirement > 0f){
                float over = Math.max(inputHeat - heatRequirement, 0f);
                attrScale *= Math.min(Mathf.clamp(inputHeat / heatRequirement) + over / heatRequirement * overheatScale, maxEfficiency);
            }

            return attrScale;
        }

        @Override
        public void write(Writes write){
            super.write(write);
            write.f(outputHeat);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            outputHeat = read.f();
        }

        //region randomOutput

        @Override
        public void created(){
            seed = Mathf.randomSeed(tile.pos(), 0, Integer.MAX_VALUE - 1);
        }

        //endregion

        //region HeatCrafter

        @Override
        public float heatRequirement(){
            return heatRequirement;
        }

        @Override
        public float[] sideHeat(){
            return sideHeat;
        }

        @Override
        public float warmupTarget(){
            return (heatRequirement > 0) ? Mathf.clamp(inputHeat / heatRequirement) : 1f;
        }

        //endregion

        //region HeatProducer

        @Override
        public float heat(){
            if(!rotate && splitHeat){
                return outputHeat / 4;
            }
            return outputHeat;
        }

        @Override
        public float heatFrac(){
            return (heatOutput > 0) ? outputHeat / heatOutput : 0f;
        }

        //endregion

        //region AttributeCrafter

        @Override
        public float getProgressIncrease(float base){
            return super.getProgressIncrease(base) * efficiencyMultiplier();
        }

        public float efficiencyMultiplier(){
            if(attribute == null) return 1f;
            return baseEfficiency + Math.min(maxBoost, boostScale * attrsum) + attribute.env();
        }

        @Override
        public void pickedUp(){
            attrsum = 0f;
            warmup = 0f;
        }

        @Override
        public void onProximityUpdate(){
            super.onProximityUpdate();

            attrsum = sumAttribute(attribute, tile.x, tile.y);
        }

        //endregion

    }

}
