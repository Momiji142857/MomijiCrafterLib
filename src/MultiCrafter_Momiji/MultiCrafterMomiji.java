package MultiCrafter_Momiji;

import arc.Core;
import arc.Events;
import arc.audio.Sound;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.math.Interp;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Vec2;
import arc.scene.actions.Actions;
import arc.scene.event.Touchable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Image;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Stack;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.content.Fx;
import mindustry.core.UI;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.Effect;
import mindustry.entities.Units;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.UnitCreateEvent;
import mindustry.gen.*;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.type.*;
import mindustry.ui.Bar;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.heat.HeatConsumer;
import mindustry.world.blocks.liquid.Conduit;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.payloads.PayloadBlock;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.consumers.*;
import mindustry.world.draw.DrawBlock;
import mindustry.world.draw.DrawDefault;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

/**
 * 多配方工厂方块，每个配方可独立配置输入/输出/热量/载荷等，
 * 并与原版 {@code GenericCrafter}、{@code HeatProducer}、{@code HeatCrafter}、{@code UnitFactory} 行为对齐。
 *
 * @since 2026-05-27
 * @see Block
 * @see mindustry.world.blocks.production.GenericCrafter
 * @author Momiji142857
 */
public class MultiCrafterMomiji extends Block {

    //region 方块公共属性

    /** 所有可切换的配方列表（JSON 加载后直接赋值） */
    public static Recipe[] recipes = {};

    /** 物品容量，-1 自动计算 */
    public int itemCapacity = -1;

    /** 默认的制作特效（配方未配置时使用） */
    public @Nullable Effect craftEffect = Fx.none;
    /** 默认的更新特效（配方未配置时使用） */
    public @Nullable Effect updateEffect = Fx.none;
    /** 切换配方的默认特效（配方未配置时使用） */
    public @Nullable Effect switchEffect = Fx.rotateBlock;

    /** 如果为 true，液体输出满了也允许生产（排空多余液体） */
    public boolean dumpExtraLiquid = true;
    /** 是否忽略液体满导致的停产 */
    public boolean ignoreLiquidFullness = false;

    /** 热量效率上限（超过 1 的部分来自过热） */
    public float maxEfficiency = 1f;
    /** 过热倍率（超出的热量效率系数） */
    public float overheatScale = 1f;

    /** 绘制器，允许通过 JSON 自定义方块外观 */
    public DrawBlock drawer = new DrawDefault();

    /** 加成属性类型（如 Attribute.water），null 则不启用 */
    public @Nullable Attribute attribute;
    /** 基础效率 */
    public float baseEfficiency = 1f;
    /** 属性加成系数 */
    public float boostScale = 1f;
    /** 属性加成上限 */
    public float maxBoost = 1f;

    /** 所有配方中涉及的载荷类型（在 init 中初始化，供面板遍历用） */
    public Seq<UnlockableContent> allPayloadTypes = new Seq<>();

    //endregion

    public MultiCrafterMomiji(String name) {
        super(name);
        update = true;
        solid = true;
        hasItems = true;
        hasPower = true;
        hasLiquids = true;
        ambientSound = Sounds.loopMachine;
        sync = true;
        ambientSoundVolume = 0.03f;
        drawArrow = false;
        destructible = true;
        breakable = true;
        targetable = true;
        configurable = true;
        flags = EnumSet.of(BlockFlag.factory);
        logicConfigurable = true;
        saveConfig = true;
    }

    //region 初始化

    @Override
    public void init() {
        Recipe.Recipe_set();

        // 动态物品消费者：单位配方时按 unitCost 规则缩放物品需求
        consume(new ConsumeItemDynamic((MultiCrafterBuild b) -> {
            Recipe r = b.getCurrentRecipe();
            if (r == null || r.inputItems == null) return ItemStack.empty;
            if (!b.isUnitRecipe) return r.inputItems;
            float costMul = state.rules.unitCost(b.team);
            ItemStack[] result = new ItemStack[r.inputItems.length];
            for (int i = 0; i < r.inputItems.length; i++) {
                result[i] = new ItemStack(r.inputItems[i].item, Mathf.round(r.inputItems[i].amount * costMul));
            }
            return result;
        }));

        // 动态液体消费者
        consume(new ConsumeLiquidsDynamic((MultiCrafterBuild b) -> {
            Recipe r = b.getCurrentRecipe();
            return r != null && r.inputLiquids != null ? r.inputLiquids : LiquidStack.empty;
        }));

        // 动态载荷消费者
        consume(new ConsumePayloadDynamic(MultiCrafterBuild::getInputPayloadSeq));

        // 检测是否有输出电力的配方
        boolean hasOutputPower = false;
        for (Recipe r : recipes) {
            if (r.outputPower > 0) {
                hasOutputPower = true;
                break;
            }
        }
        outputsPower = hasOutputPower;

        // 检测是否有输出液体的配方
        boolean hasOutputLiquid = false;
        for (Recipe r : recipes) {
            if (r.outputLiquids != null && r.outputLiquids.length > 0) {
                hasOutputLiquid = true;
                break;
            }
        }
        outputsLiquid = hasOutputLiquid;

        // 仅当存在电力需求配方时，注册动态电力消费者
        float maxPower = 0f;
        for (Recipe r : recipes) {
            if (r.inputPower > maxPower) maxPower = r.inputPower;
        }
        if (maxPower > 0) {
            consume(new DynamicConsumePower(maxPower));
        }

        // 自动计算物品/液体容量
        if (itemCapacity < 0) {
            int maxItemCap = 10;
            for (Recipe r : recipes) {
                if (r.inputItems != null) for (ItemStack s : r.inputItems)
                    maxItemCap = Math.max(maxItemCap, s.amount * 2);
                if (r.outputItems != null) for (ItemStack s : r.outputItems)
                    maxItemCap = Math.max(maxItemCap, s.amount * 2);
            }
            itemCapacity = maxItemCap;
        }
        if (liquidCapacity < 0) {
            float maxLiquidCap = 1f;
            for (Recipe r : recipes) {
                if (r.inputLiquids != null) for (LiquidStack stack : r.inputLiquids)
                    maxLiquidCap = Math.max(maxLiquidCap, stack.amount * 60f);
                if (r.outputLiquids != null) for (LiquidStack stack : r.outputLiquids)
                    maxLiquidCap = Math.max(maxLiquidCap, stack.amount * 60f);
            }
            liquidCapacity = Mathf.round(10f * maxLiquidCap);
        }

        // 载荷输入/输出标志
        boolean hasInputPayload = false;
        boolean hasOutputPayload = false;
        for (Recipe r : recipes) {
            if (Recipe.haveInputPayloads(r)) hasInputPayload = true;
            if (Recipe.haveOutputPayloads(r)) hasOutputPayload = true;
        }

        if (!rotate && hasOutputPayload) {
            Log.warn("MultiCrafter '@' has payload output recipes, but rotation is disabled. Forcing rotate=true.", name);
            rotate = true;
            drawArrow = true;
        }
        acceptsPayload = hasInputPayload;
        acceptsUnitPayloads = hasInputPayload;
        outputsPayload = hasOutputPayload;

        // 配方索引配置
        config(Integer.class, (MultiCrafterBuild build, Integer i) -> {
            if (build.currentRecipe != i) {
                build.switchRecipe(i);
            }
        });

        // 收集所有配方中可能出现的载荷类型
        allPayloadTypes = new Seq<>();
        for (Recipe r : recipes) {
            if (r.inputPayloads != null) {
                for (PayloadStack stack : r.inputPayloads) {
                    if (!allPayloadTypes.contains(stack.item)) {
                        allPayloadTypes.add(stack.item);
                    }
                }
            }
            if (r.outputPayloads != null) {
                for (PayloadStack stack : r.outputPayloads) {
                    if (!allPayloadTypes.contains(stack.item)) {
                        allPayloadTypes.add(stack.item);
                    }
                }
            }
        }

        super.init();
    }

    //endregion

    //region 统计信息

    @Override
    public void setStats() {
        stats.add(Stat.size, "@x@", size, size);
        if (synthetic()) {
            stats.add(Stat.health, health, StatUnit.none);
            if (armor > 0) stats.add(Stat.armor, armor, StatUnit.none);
        }
        if (canBeBuilt() && requirements.length > 0) {
            stats.add(Stat.buildTime, buildTime / 60, StatUnit.seconds);
            stats.add(Stat.buildCost, StatValues.items(false, requirements));
        }
        if (instantTransfer) {
            stats.add(Stat.maxConsecutive, 2, StatUnit.none);
        }
        for (var c : consumers) {
            c.display(stats);
        }
        if (hasLiquids) stats.add(Stat.liquidCapacity, liquidCapacity, StatUnit.liquidUnits);
        if (hasItems && itemCapacity > 0) stats.add(Stat.itemCapacity, itemCapacity, StatUnit.items);

        // 显示每个配方的输入/输出/时间/超速
        stats.add(Stat.output, table -> {
            table.clearChildren();
            table.left();

            for (int i = 0; i < recipes.length; i++) {
                Recipe rec = recipes[i];
                int finalI = i;
                int colspan = Recipe.calcColspan(rec);
                table.table(Styles.grayPanel, t -> {
                    t.left().defaults().left();
                    t.add("[accent]Recipe " + (finalI + 1) + "[]").colspan(colspan).padTop(4).padBottom(4).left();
                    t.row();

                    boolean hasInput = rec.inputItems != null || rec.inputLiquids != null
                            || rec.inputPower > 0 || rec.inputHeat > 0 || rec.inputPayloads != null;
                    if (hasInput) {
                        t.add("[lightgray]" + Core.bundle.get("stat.input") + ":[]");
                        if (rec.inputItems != null) for (ItemStack s : rec.inputItems)
                            t.add(StatValues.displayItem(s.item, s.amount, rec.craftTime, true)).pad(5);
                        if (rec.inputLiquids != null) for (LiquidStack s : rec.inputLiquids)
                            t.add(StatValues.displayLiquid(s.liquid, s.amount * 60f, true)).pad(5);
                        if (rec.inputPayloads != null) for (PayloadStack s : rec.inputPayloads)
                            t.add(displayPayload(s.item, s.amount, rec.craftTime, true)).pad(5);
                        if (rec.inputPower > 0)
                            t.table(p -> StatValues.number(rec.inputPower * 60f, StatUnit.powerSecond).display(p)).pad(5);
                        if (rec.inputHeat > 0)
                            t.table(h -> StatValues.number(rec.inputHeat, StatUnit.heatUnits).display(h)).pad(5);
                        t.row();
                    }

                    boolean hasOutput = rec.outputItems != null || rec.outputLiquids != null
                            || rec.outputHeat > 0 || rec.outputPower > 0 || rec.outputPayloads != null;
                    if (hasOutput) {
                        t.add("[lightgray]" + Core.bundle.get("stat.output") + ":[]");
                        if (rec.outputItems != null) for (ItemStack s : rec.outputItems)
                            t.add(StatValues.displayItem(s.item, s.amount, rec.craftTime, true)).pad(5);
                        if (rec.outputLiquids != null) for (LiquidStack s : rec.outputLiquids)
                            t.add(StatValues.displayLiquid(s.liquid, s.amount * 60f, true)).pad(5);
                        if (rec.outputPayloads != null) for (PayloadStack s : rec.outputPayloads)
                            t.add(displayPayload(s.item, s.amount, rec.craftTime, true)).pad(5);
                        if (rec.outputPower > 0)
                            t.table(p -> StatValues.number(rec.outputPower * 60f, StatUnit.powerSecond).display(p)).pad(5);
                        if (rec.outputHeat > 0)
                            t.table(h -> StatValues.number(rec.outputHeat, StatUnit.heatUnits).display(h)).pad(5);
                        t.row();
                    }

                    boolean canOverdrive = !(rec.outputHeat > 0) && rec.allowOverdrive;
                    t.table(info -> {
                        info.left();
                        info.add("[lightgray]" + Core.bundle.get("stat.productiontime") + ":[] " + Strings.autoFixed(rec.craftTime / 60f, 3) + " " + Core.bundle.get("unit.seconds"));
                        info.add("  [lightgray]能否超速:[] " + (canOverdrive ? Core.bundle.get("yes") : Core.bundle.get("no")));
                    }).colspan(colspan).padTop(4).left();
                }).growX().pad(5).row();
            }
        });
    }

    //endregion

    //region 状态栏

    @Override
    public void setBars() {
        addBar("health", entity -> new Bar("stat.health", Pal.health, entity::healthf).blink(Color.white));

        // 电力输入条
        addBar("powerInput", (MultiCrafterBuild e) -> {
            Recipe r = e.getCurrentRecipe();
            if (r == null || r.inputPower <= 0) return null;
            return new Bar(
                    () -> {
                        float recipePower = r.inputPower * 60f;
                        float status = e.power.status;
                        float available = recipePower * status;
                        if (status >= 1f && e.efficiency >= 1f)
                            return StatUnit.powerSecond.icon + "- " + powerFmtNum(recipePower);
                        return StatUnit.powerSecond.icon + "- " +
                                powerFmtNum(available) + "/" + fmtNum(recipePower) +
                                " [lightgray]| " + Strings.autoFixed(e.efficiency * 100, 1) + "%[]";
                    },
                    () -> Pal.powerBar,
                    () -> e.power.status
            );
        });

        // 电力输出条
        addBar("powerOutput", (MultiCrafterBuild e) -> {
            Recipe r = e.getCurrentRecipe();
            if (r == null || r.outputPower <= 0) return null;
            return new Bar(
                    () -> {
                        float recipeOutput = r.outputPower * 60f;
                        float actualOutput = e.getPowerProduction();
                        if (e.efficiency >= 1f)
                            return StatUnit.powerSecond.icon + "+ " + powerFmtNum(recipeOutput);
                        return StatUnit.powerSecond.icon + "+ " +
                                powerFmtNum(actualOutput) + "/" + fmtNum(recipeOutput) +
                                " [lightgray]| " + Strings.autoFixed(e.efficiency * 100, 1) + "%[]";
                    },
                    () -> Pal.powerBar,
                    () -> e.efficiency
            );
        });

        // 物品存储条
        addBar("items", (MultiCrafterBuild e) -> {
            Recipe r = e.getCurrentRecipe();
            if (r == null || (!Recipe.haveItems(r) && e.items.total() == 0)) return null;
            return new Bar(
                    () -> Core.bundle.format("bar.items", e.items.total()),
                    () -> Pal.items,
                    () -> (float) e.items.total() / itemCapacity
            );
        });

        // 液体存储条
        ObjectSet<Liquid> allLiquids = new ObjectSet<>();
        for (Recipe r : recipes) {
            if (r.inputLiquids != null) for (LiquidStack stack : r.inputLiquids) allLiquids.add(stack.liquid);
            if (r.outputLiquids != null) for (LiquidStack stack : r.outputLiquids) allLiquids.add(stack.liquid);
        }
        int liqIdx = 0;
        for (Liquid liq : allLiquids) {
            addBar("liquid" + liqIdx, (MultiCrafterBuild e) -> {
                Recipe r = e.getCurrentRecipe();
                if (r == null || !Recipe.haveLiquids(r)) return null;
                boolean found = false;
                if (r.inputLiquids != null) for (LiquidStack s : r.inputLiquids) if (s.liquid == liq) { found = true; break; }
                if (!found && r.outputLiquids != null) for (LiquidStack s : r.outputLiquids) if (s.liquid == liq) { found = true; break; }
                if (!found) return null;
                return new Bar(
                        () -> {
                            float current = e.liquids.get(liq);
                            float fill = current / liquidCapacity;
                            if (fill > 0.99f) return liq.localizedName + " " + fmtNum(current);
                            return liq.localizedName + " " + fmtNum(current) + "/" + fmtNum(liquidCapacity)
                                    + " [lightgray]| " + Strings.autoFixed(fill * 100f, 1) + "%[]";
                        },
                        liq::barColor,
                        () -> e.liquids.get(liq) / liquidCapacity
                );
            });
            liqIdx++;
        }

        // 热量输入/输出条
        addBar("heatInput", (MultiCrafterBuild e) -> {
            Recipe r = e.getCurrentRecipe();
            if (r == null || r.inputHeat <= 0) return null;
            return new Bar(
                    () -> {
                        boolean hasOutput = r.outputHeat > 0;
                        String label = hasOutput ? Core.bundle.get("stat.input") + Core.bundle.get("bar.heat") : Core.bundle.get("bar.heat");
                        float heatVal = e.heat;
                        float heatReq = r.inputHeat;
                        if (heatVal > heatReq)
                            return label + " " + fmtNum(heatVal) + " [lightgray]| 100%[]";
                        return label + " " + fmtNum(heatVal) + "/" + fmtNum(heatReq)
                                + " [lightgray]| " + Strings.autoFixed(heatVal / heatReq * 100f, 1) + "%[]";
                    },
                    () -> Pal.lightOrange,
                    () -> e.heat / r.inputHeat
            );
        });
        addBar("heatOutput", (MultiCrafterBuild e) -> {
            Recipe r = e.getCurrentRecipe();
            if (r == null || r.outputHeat <= 0) return null;
            return new Bar(
                    () -> {
                        boolean hasInput = r.inputHeat > 0;
                        String label = hasInput ? Core.bundle.get("stat.output") + Core.bundle.get("bar.heat") : Core.bundle.get("bar.heat");
                        float heatVal = e.heatOutput;
                        float heatReq = r.outputHeat;
                        if (heatVal >= heatReq)
                            return label + " " + fmtNum(heatVal) + " (100%)";
                        return label + " " + fmtNum(heatVal) + "/" + fmtNum(heatReq)
                                + " [lightgray]| " + Strings.autoFixed(heatVal / heatReq * 100f, 1) + "%[]";
                    },
                    () -> Pal.lightOrange,
                    () -> e.heatOutput / r.outputHeat
            );
        });

        // 单位上限条
        ObjectSet<UnitType> unitTypes = new ObjectSet<>();
        for (Recipe r : recipes) {
            if (r.outputPayloads != null) {
                for (PayloadStack stack : r.outputPayloads) {
                    if (stack.item instanceof UnitType ut) unitTypes.add(ut);
                }
            }
        }
        int idx = 0;
        for (UnitType type : unitTypes) {
            addBar("unitOutput" + idx, (MultiCrafterBuild e) -> {
                Recipe r = e.getCurrentRecipe();
                if (r == null || !Recipe.haveOutputPayloads(r)) return null;
                boolean found = false;
                for (PayloadStack s : r.outputPayloads) {
                    if (s.item == type) { found = true; break; }
                }
                if (!found) return null;
                return new Bar(
                        () -> Core.bundle.format("bar.unitcap",
                                Fonts.getUnicodeStr(type.name),
                                e.team.data().countType(type),
                                type.useUnitCap ? Units.getStringCap(e.team) : "∞"
                        ),
                        () -> Pal.power,
                        () -> type.useUnitCap ? (float) e.team.data().countType(type) / Units.getCap(e.team) : 1f
                );
            });
            idx++;
        }

        // 生产进度条
        addBar("progress", (MultiCrafterBuild e) -> new Bar(
                () -> {
                    String icon;
                    Recipe r = e.getCurrentRecipe();
                    if (r != null && r.outputPayloads != null && r.outputPayloads.length > 0) {
                        icon = String.valueOf(Iconc.units);
                    } else if (r != null && r.outputPower > 0) {
                        icon = String.valueOf(Iconc.power);
                    } else {
                        icon = String.valueOf(Iconc.crafting);
                    }
                    float p = e.progress();
                    float aps = e.getActualProgressPerSecond();
                    float remainSec = aps > 0.00001f ? (1f - p) / aps : 5994f;

                    String timeStr;
                    if (remainSec >= 60f) {
                        timeStr = Strings.autoFixed(remainSec / 60f, 2) + "min";
                    } else {
                        timeStr = Strings.autoFixed(remainSec, 2) + "s";
                    }
                    return icon + " " + (int) (p * 100) + "% [orange]" + timeStr + "[]";
                },
                () -> Pal.ammo,
                e::progress
        ));
    }

    @Override
    public boolean rotatedOutput(int fromX, int fromY, Tile destination) {
        if (!(destination.build instanceof Conduit.ConduitBuild)) return false;
        Building crafter = world.build(fromX, fromY);
        if (crafter == null) return false;
        if (crafter instanceof MultiCrafterBuild build) {
            Recipe rec = build.getCurrentRecipe();
            if (rec == null || rec.outputLiquids == null) return false;
            int relative = Mathf.mod(crafter.relativeTo(destination) - crafter.rotation, 4);
            for (int dir : rec.liquidOutputDirections) {
                if (dir == -1 || dir == relative) return false;
            }
        }
        return true;
    }

    //endregion

    //region 工具方法

    private static String fmtNum(float value) {
        float abs = Math.abs(value);
        if (abs >= 1000f) return UI.formatAmount((long) value);
        if (abs >= 0.01f) return Strings.autoFixed(value, 2);
        if (abs == 0f) return "0";
        if (abs < 0.000_001f) return "0.0";
        int exponent = (int) Math.floor(Math.log10(abs));
        float mantissa = (float) (value / Math.pow(10, exponent));
        mantissa = Mathf.round(mantissa, 2);
        return mantissa + "[lightgray]E" + exponent + "[]";
    }

    private static String powerFmtNum(float value) {
        float abs = Math.abs(value);
        if (abs >= 1_000_000f) return UI.formatAmount((long) value);
        if (abs >= 0.01f) return Strings.autoFixed(value, 2);
        if (abs == 0f) return "0";
        if (abs < 0.000_001f) return "0";
        int exponent = (int) Math.floor(Math.log10(abs));
        float mantissa = (float) (value / Math.pow(10, exponent));
        mantissa = Mathf.round(mantissa, 2);
        return mantissa + "[lightgray]E" + exponent + "[]";
    }

    public static Table displayPayload(UnlockableContent content, int amount, float craftTime, boolean showName) {
        Table t = new Table();
        t.add(StatValues.stack(content, amount, !showName));
        t.add((showName ? content.localizedName + "\n" : "") +
                "[lightgray]" + Strings.autoFixed(amount / (craftTime / 60f), 3) +
                StatUnit.perSecond.localized()).padLeft(2).padRight(5).style(Styles.outlineLabel);
        return t;
    }

    //endregion

    //region 渲染与补丁

    @Override
    public void load(){
        super.load();
        drawer.load(this);
    }

    @Override
    public void drawPlanRegion(BuildPlan plan, Eachable<BuildPlan> list){
        drawer.drawPlan(this, plan, list);
    }

    @Override
    public TextureRegion[] icons(){
        return drawer.finalIcons(this);
    }

    @Override
    public void getRegionsToOutline(Seq<TextureRegion> out){
        drawer.getRegionsToOutline(this, out);
    }

    @Override
    public void drawOverlay(float x, float y, int rotation){
        Building b = world.buildWorld(x, y);
        if(!(b instanceof MultiCrafterBuild build)) return;

        Recipe rec = build.getCurrentRecipe();
        if(rec == null || rec.outputLiquids == null) return;

        for(int i = 0; i < rec.outputLiquids.length; i++){
            int dir = rec.liquidOutputDirections.length > i ? rec.liquidOutputDirections[i] : -1;
            if(dir != -1){
                Draw.rect(
                        rec.outputLiquids[i].liquid.fullIcon,
                        x + Geometry.d4x(dir + rotation) * (size * tilesize / 2f + 4),
                        y + Geometry.d4y(dir + rotation) * (size * tilesize / 2f + 4),
                        8f, 8f
                );
            }
        }
    }

    @Override
    public boolean outputsItems() {
        return super.outputsItems();
    }

    @Override
    public void afterPatch(){
        super.afterPatch();

        boolean anyItem = false, anyLiquid = false, anyPower = false;
        boolean anyOutputPayload = false, anyInputPayload = false;

        for(Recipe r : recipes){
            if(r.inputItems != null && r.inputItems.length > 0) anyItem = true;
            if(r.outputItems != null && r.outputItems.length > 0) anyItem = true;
            if(r.inputLiquids != null && r.inputLiquids.length > 0) anyLiquid = true;
            if(r.outputLiquids != null && r.outputLiquids.length > 0) anyLiquid = true;
            if(r.inputPower > 0 || r.outputPower > 0) anyPower = true;
            if(r.outputPayloads != null && r.outputPayloads.length > 0) anyOutputPayload = true;
            if(r.inputPayloads != null && r.inputPayloads.length > 0) anyInputPayload = true;
        }

        hasItems = anyItem;
        hasLiquids = anyLiquid;
        hasPower = anyPower;
        outputsLiquid = anyLiquid;
        outputsPayload = anyOutputPayload;
        acceptsPayload = anyInputPayload;
        acceptsUnitPayloads = anyInputPayload;

        if(!hasItems) itemCapacity = 0;
    }

    //endregion

    //region 配方定义

    public static class Recipe {
        public @Nullable ItemStack[] inputItems;
        public @Nullable LiquidStack[] inputLiquids;
        public float inputPower;
        public float inputHeat;
        public @Nullable PayloadStack[] inputPayloads;

        public @Nullable ItemStack[] outputItems;
        public @Nullable LiquidStack[] outputLiquids;
        public float outputPower;
        public float outputHeat;
        public @Nullable PayloadStack[] outputPayloads;

        public float craftTime = 80f;
        public boolean allowOverdrive = true;
        public float warmupSpeed = 0.019f;

        public @Nullable Effect craftEffect;
        public @Nullable Effect updateEffect;
        public float updateEffectChance = 0.04f;
        public float updateEffectSpread = 4f;
        public @Nullable Effect switchEffect;
        public @Nullable Sound craftSound;
        public @Nullable Sound updateSound;

        public int inputPayloadCapacity = 0;
        public float inputPayloadMultiplier = 2f;
        public int outputPayloadQueueLimit = -1;

        public int[] liquidOutputDirections = {-1};

        public @Nullable ItemStack outputItem;
        public @Nullable LiquidStack outputLiquid;
        public @Nullable ItemStack inputItem;
        public @Nullable LiquidStack inputLiquid;

        public static void Recipe_set() {
            for (Recipe r : recipes) {
                if (r.inputItems == null && r.inputItem != null)
                    r.inputItems = new ItemStack[]{r.inputItem};
                if (r.inputLiquids == null && r.inputLiquid != null)
                    r.inputLiquids = new LiquidStack[]{r.inputLiquid};
                if (r.outputItems == null && r.outputItem != null)
                    r.outputItems = new ItemStack[]{r.outputItem};
                if (r.outputLiquids == null && r.outputLiquid != null)
                    r.outputLiquids = new LiquidStack[]{r.outputLiquid};
                if (r.outputLiquid == null && r.outputLiquids != null && r.outputLiquids.length > 0)
                    r.outputLiquid = r.outputLiquids[0];
            }
        }

        public static boolean havePower(Recipe r) { return r.inputPower > 0 || r.outputPower > 0; }
        public static boolean haveItems(Recipe r) { return (r.inputItems != null && r.inputItems.length > 0) || (r.outputItems != null && r.outputItems.length > 0); }
        public static boolean haveLiquids(Recipe r) { return (r.inputLiquids != null && r.inputLiquids.length > 0) || (r.outputLiquids != null && r.outputLiquids.length > 0); }
        public static boolean haveInputPayloads(Recipe r) { return r.inputPayloads != null && r.inputPayloads.length > 0; }
        public static boolean haveOutputPayloads(Recipe r) { return r.outputPayloads != null && r.outputPayloads.length > 0; }

        private static int calcColspan(Recipe rec) {
            int inputCount = 0, outputCount = 0;
            if (rec.inputItems != null) inputCount += rec.inputItems.length;
            if (rec.inputLiquids != null) inputCount += rec.inputLiquids.length;
            if (rec.inputPayloads != null) inputCount += rec.inputPayloads.length;
            inputCount += 2;
            if (rec.outputItems != null) outputCount += rec.outputItems.length;
            if (rec.outputLiquids != null) outputCount += rec.outputLiquids.length;
            if (rec.outputPayloads != null) outputCount += rec.outputPayloads.length;
            outputCount += 2;
            return Math.max(1, Math.max(inputCount, outputCount)) + 2;
        }

        // ============ 物品输入便捷方法 ============

        /** 添加单个物品输入，返回自身以支持链式调用 */
        public Recipe consumeItem(Item item, int amount) {
            inputItems = addToArray(inputItems, new ItemStack(item, amount));
            return this;
        }

        /** 添加多个物品输入，返回自身以支持链式调用 */
        public Recipe consumeItems(ItemStack... stacks) {
            for (ItemStack stack : stacks) {
                inputItems = addToArray(inputItems, stack);
            }
            return this;
        }

        // ============ 液体输入便捷方法 ============

        /** 添加单个液体输入，返回自身以支持链式调用 */
        public Recipe consumeLiquid(Liquid liquid, float amount) {
            inputLiquids = addToArray(inputLiquids, new LiquidStack(liquid, amount));
            return this;
        }

        /** 添加多个液体输入，返回自身以支持链式调用 */
        public Recipe consumeLiquids(LiquidStack... stacks) {
            for (LiquidStack stack : stacks) {
                inputLiquids = addToArray(inputLiquids, stack);
            }
            return this;
        }

        // ============ 电力输入便捷方法 ============

        /** 设置电力输入，返回自身以支持链式调用 */
        public Recipe consumePower(float powerPerTick) {
            inputPower = powerPerTick;
            return this;
        }

        // ============ 热量输入便捷方法 ============

        /** 设置热量输入，返回自身以支持链式调用 */
        public Recipe consumeHeat(float heat) {
            inputHeat = heat;
            return this;
        }

        // ============ 载荷输入便捷方法 ============

        /** 添加单个载荷输入，返回自身以支持链式调用 */
        public Recipe consumePayload(UnlockableContent item, int amount) {
            inputPayloads = addToArray(inputPayloads, new PayloadStack(item, amount));
            return this;
        }

        /** 添加多个载荷输入，返回自身以支持链式调用 */
        public Recipe consumePayloads(PayloadStack... stacks) {
            for (PayloadStack stack : stacks) {
                inputPayloads = addToArray(inputPayloads, stack);
            }
            return this;
        }

        // ============ 输出便捷方法 ============

        /** 添加单个物品输出，返回自身以支持链式调用 */
        public Recipe outputItem(Item item, int amount) {
            outputItems = addToArray(outputItems, new ItemStack(item, amount));
            return this;
        }

        /** 添加多个物品输出，返回自身以支持链式调用 */
        public Recipe outputItems(ItemStack... stacks) {
            for (ItemStack stack : stacks) {
                outputItems = addToArray(outputItems, stack);
            }
            return this;
        }

        /** 添加单个液体输出，返回自身以支持链式调用 */
        public Recipe outputLiquid(Liquid liquid, float amount) {
            outputLiquids = addToArray(outputLiquids, new LiquidStack(liquid, amount));
            return this;
        }

        /** 添加多个液体输出，返回自身以支持链式调用 */
        public Recipe outputLiquids(LiquidStack... stacks) {
            for (LiquidStack stack : stacks) {
                outputLiquids = addToArray(outputLiquids, stack);
            }
            return this;
        }

        /** 设置电力输出，返回自身以支持链式调用 */
        public Recipe outputPower(float power) {
            outputPower = power;
            return this;
        }

        /** 设置热量输出，返回自身以支持链式调用 */
        public Recipe outputHeat(float heat) {
            outputHeat = heat;
            return this;
        }

        /** 添加单个载荷输出，返回自身以支持链式调用 */
        public Recipe outputPayload(UnlockableContent item, int amount) {
            outputPayloads = addToArray(outputPayloads, new PayloadStack(item, amount));
            return this;
        }

        /** 添加多个载荷输出，返回自身以支持链式调用 */
        public Recipe outputPayloads(PayloadStack... stacks) {
            for (PayloadStack stack : stacks) {
                outputPayloads = addToArray(outputPayloads, stack);
            }
            return this;
        }

        // ============ 辅助方法 ============

        /** 动态扩展数组，正确处理数组类型 */
        @SuppressWarnings("unchecked")
        private static <T> T[] addToArray(T[] original, T element) {
            if (original == null) {
                T[] arr = (T[]) java.lang.reflect.Array.newInstance(element.getClass(), 1);
                arr[0] = element;
                return arr;
            }
            T[] newArray = java.util.Arrays.copyOf(original, original.length + 1);
            newArray[original.length] = element;
            return newArray;
        }
    }

    //endregion

    //region 建筑实体

    public class MultiCrafterBuild extends Building implements HeatBlock, HeatConsumer {

        public int currentRecipe;
        private Recipe currentRecipeObj;

        public float craftTime = 80f;
        public boolean canOverdrive = true;
        public float warmupSpeed = 0.019f;
        public @Nullable Effect currentCraftEffect;
        public @Nullable Effect currentUpdateEffect;
        public float currentUpdateEffectChance = 0.04f;
        public float currentUpdateEffectSpread = 4f;
        public @Nullable Effect currentSwitchEffect;
        public boolean isUnitRecipe;

        public float heat;
        public float heatOutput;
        public float[] sideHeat = new float[4];

        public float progress;
        public float totalProgress;
        public float warmup;
        public float warmupRate = 0.15f;

        public float attrsum;

        public PayloadSeq inputPayloads = new PayloadSeq();
        private final Seq<PayloadStack> cachedInputPayloadSeq = new Seq<>();
        public final Seq<Payload> outputPayloads = new Seq<>();
        private Recipe lastRecipe;

        private final Vec2 payVector = new Vec2();
        private float payRotation;
        public final float payloadSpeed = 0.7f;
        public final float payloadRotateSpeed = 5f;

        // ============ 公共访问 ============

        public Recipe getCurrentRecipe() { return currentRecipeObj; }

        public Seq<PayloadStack> getInputPayloadSeq() {
            if (lastRecipe == currentRecipeObj) return cachedInputPayloadSeq;
            lastRecipe = currentRecipeObj;
            cachedInputPayloadSeq.clear();
            if (currentRecipeObj != null && currentRecipeObj.inputPayloads != null)
                cachedInputPayloadSeq.addAll(currentRecipeObj.inputPayloads);
            return cachedInputPayloadSeq;
        }

        // ============ 核心更新 ============

        @Override
        public void updateTile() {
            if (currentRecipeObj != null && currentRecipeObj.inputHeat > 0)
                heat = calculateHeat(sideHeat);

            if (efficiency > 0) {
                float buildMul = isUnitRecipe ? state.rules.unitBuildSpeed(team) : 1f;
                progress += getProgressIncrease(craftTime) * buildMul;
                warmup = Mathf.approachDelta(warmup, warmupTarget(), warmupSpeed);
                totalProgress += warmup * delta();

                if (currentRecipeObj != null && currentRecipeObj.outputLiquids != null) {
                    float inc = getProgressIncrease(1f);
                    for (LiquidStack output : currentRecipeObj.outputLiquids) {
                        handleLiquid(this, output.liquid, Math.min(output.amount * inc, liquidCapacity - liquids.get(output.liquid)));
                    }
                }

                if (wasVisible && currentUpdateEffect != null && Mathf.chanceDelta(currentUpdateEffectChance)) {
                    currentUpdateEffect.at(
                            x + Mathf.range(size * currentUpdateEffectSpread),
                            y + Mathf.range(size * currentUpdateEffectSpread));
                }
                if (currentRecipeObj != null && currentRecipeObj.updateSound != null && Mathf.chanceDelta(currentUpdateEffectChance)) {
                    currentRecipeObj.updateSound.at(x, y);
                }
            } else {
                warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
            }

            if (currentRecipeObj != null && currentRecipeObj.outputHeat > 0)
                heatOutput = Mathf.approachDelta(heatOutput, currentRecipeObj.outputHeat * efficiency, warmupRate * delta());
            else
                heatOutput = Mathf.approachDelta(heatOutput, 0, warmupRate * delta());

            moveOutPayload();
            if (progress >= 1f) craft();
            dumpOutputs();
        }

        public float warmupTarget() {
            if (currentRecipeObj == null || currentRecipeObj.inputHeat <= 0) return 1f;
            return Mathf.clamp(heat / currentRecipeObj.inputHeat);
        }

        // ============ 载荷输出 ============

        public void moveOutPayload() {
            if (outputPayloads.isEmpty()) return;

            Payload p = outputPayloads.first();
            p.update(null, this);

            Vec2 dest = Tmp.v1.trns(rotdeg(), size * tilesize / 2f);
            payRotation = Angles.moveToward(payRotation, rotdeg(), payloadRotateSpeed * delta());
            payVector.approach(dest, payloadSpeed * delta());
            p.set(x + payVector.x, y + payVector.y, payRotation);

            Building front = front();
            boolean canDump = front == null || !front.tile.solid();
            boolean canMove = front != null && (front.block.outputsPayload || front.block.acceptsPayload);

            if (canDump && !canMove) {
                PayloadBlock.pushOutput(p, 1f - (payVector.dst(dest) / (size * tilesize / 2f)));
            }

            if (payVector.within(dest, 0.001f)) {
                payVector.clamp(-size * tilesize / 2f, -size * tilesize / 2f, size * tilesize / 2f, size * tilesize / 2f);
                if (canMove) {
                    if (movePayload(p)) {
                        outputPayloads.remove(0);
                        payVector.setZero();
                    }
                } else if (canDump) {
                    float tx = Angles.trnsx(p.rotation(), 0.1f);
                    float ty = Angles.trnsy(p.rotation(), 0.1f);
                    p.set(p.x() + tx, p.y() + ty, p.rotation());
                    if (p.dump()) {
                        outputPayloads.remove(0);
                        payVector.setZero();
                    } else {
                        p.set(p.x() - tx, p.y() - ty, p.rotation());
                    }
                }
            }
        }

        // ============ 生产完成 ============

        public void craft() {
            if (currentRecipeObj == null) return;
            consume();

            if (currentRecipeObj.outputItems != null) {
                for (ItemStack output : currentRecipeObj.outputItems) {
                    for (int i = 0; i < output.amount; i++) offload(output.item);
                }
            }

            if (currentRecipeObj.outputPayloads != null) {
                for (PayloadStack stack : currentRecipeObj.outputPayloads) {
                    for (int i = 0; i < stack.amount; i++) {
                        Payload p = createPayload(stack.item);
                        if (p != null) {
                            outputPayloads.add(p);
                            if (p instanceof UnitPayload) {
                                Events.fire(new UnitCreateEvent(((UnitPayload) p).unit, this));
                            }
                            if (payVector.isZero()) {
                                payVector.setZero();
                                payRotation = rotdeg();
                            }
                        }
                    }
                }
            }

            if (currentCraftEffect != null && wasVisible) currentCraftEffect.at(x, y);
            if (currentRecipeObj.craftSound != null && wasVisible) currentRecipeObj.craftSound.at(x, y);
            progress %= 1f;
        }

        public Payload createPayload(UnlockableContent content) {
            if (content instanceof Block b) return new BuildPayload(b, team);
            else if (content instanceof UnitType unitType) return new UnitPayload(unitType.create(team));
            return null;
        }

        // ============ 输出 ============

        public void dumpOutputs() {
            if (currentRecipeObj == null) return;

            if (currentRecipeObj.outputItems != null && timer(timerDump, dumpTime / timeScale)) {
                for (ItemStack output : currentRecipeObj.outputItems) {
                    dump(output.item);
                }
            }

            if (currentRecipeObj.outputLiquids != null) {
                for (int i = 0; i < currentRecipeObj.outputLiquids.length; i++) {
                    int dir = currentRecipeObj.liquidOutputDirections.length > i
                            ? currentRecipeObj.liquidOutputDirections[i] : -1;
                    dumpLiquid(currentRecipeObj.outputLiquids[i].liquid, 2f, dir);
                }
            }
        }

        // ============ 配置界面 ============

        @Override
        public void buildConfiguration(Table table) {
            showPayloadPanel();

            table.clearChildren();
            table.background(Styles.black6);
            table.top().left();
            table.margin(2f);

            for (int i = 0; i < recipes.length; i++) {
                final int idx = i;
                Recipe rec = recipes[i];

                TextButton btn = new TextButton("");
                btn.clearChildren();
                btn.setStyle(new TextButton.TextButtonStyle() {{
                    up = Styles.none;
                    over = Styles.flatOver;
                    down = Styles.flatDown;
                    checked = Styles.flatDown;
                    font = Fonts.def;
                    fontColor = Color.white;
                    disabledFontColor = Color.gray;
                }});

                int finalI = i;
                btn.table(btnTable -> {
                    btnTable.left().defaults().left();
                    btnTable.add(String.valueOf(finalI + 1)).width(24f).right().padRight(8f).padLeft(8f).color(Color.lightGray);

                    btnTable.table(iconRow -> {
                        iconRow.left().defaults().left();
                        if (rec.inputItems != null) for (ItemStack s : rec.inputItems)
                            iconRow.image(s.item.uiIcon).size(24f).pad(2);
                        if (rec.inputLiquids != null) for (LiquidStack s : rec.inputLiquids)
                            iconRow.image(s.liquid.uiIcon).size(24f).pad(2);
                        if (rec.inputPayloads != null) for (PayloadStack s : rec.inputPayloads)
                            iconRow.image(s.item.uiIcon).size(24f).pad(2);
                        if (rec.inputPower > 0)
                            iconRow.add(StatUnit.powerSecond.icon).style(Styles.outlineLabel).fontScale(1.3f).pad(2);
                        if (rec.inputHeat > 0)
                            iconRow.add(StatUnit.heatUnits.icon).style(Styles.outlineLabel).fontScale(1.3f).pad(2);

                        iconRow.image().size(24f).pad(6)
                                .update(i2 -> i2.setDrawable(new TextureRegionDrawable(Icon.right)))
                                .with(i2 -> i2.setScaling(Scaling.fit));

                        if (rec.outputItems != null) for (ItemStack s : rec.outputItems)
                            iconRow.image(s.item.uiIcon).size(24f).pad(2);
                        if (rec.outputLiquids != null) for (LiquidStack s : rec.outputLiquids)
                            iconRow.image(s.liquid.uiIcon).size(24f).pad(2);
                        if (rec.outputPayloads != null) for (PayloadStack s : rec.outputPayloads)
                            iconRow.image(s.item.uiIcon).size(24f).pad(2);
                        if (rec.outputPower > 0)
                            iconRow.add(StatUnit.powerSecond.icon).style(Styles.outlineLabel).fontScale(1.25f).pad(2);
                        if (rec.outputHeat > 0)
                            iconRow.add(StatUnit.heatUnits.icon).style(Styles.outlineLabel).fontScale(1.25f).pad(2);
                    });
                });

                btn.clicked(() -> {
                    if (currentRecipe == idx) switchRecipe(-1);
                    else switchRecipe(idx);
                    deselect();
                });
                btn.update(() -> btn.setChecked(currentRecipe == idx));
                table.add(btn).growX().pad(0);
                table.row();
            }
        }

        @Override
        public boolean onConfigureBuildTapped(Building other) {
            if (this == other) {
                deselect();
                return false;
            }
            return true;
        }

        // ============ 配方切换 ============

        private void switchRecipe(int idx) {
            if (idx < -1 || idx >= recipes.length) idx = -1;
            if (currentRecipe == idx) return;
            currentRecipe = idx;
            progress = 0f;
            heatOutput = 0f;
            refreshFromRecipe();

            Effect effect = currentSwitchEffect != null ? currentSwitchEffect : switchEffect;
            effect.at(x, y, block.size);
            updateProximity();
        }

        private void refreshFromRecipe() {
            Recipe rec = (currentRecipe >= 0 && currentRecipe < recipes.length) ? recipes[currentRecipe] : null;
            currentRecipeObj = rec;

            if (rec == null) {
                craftTime = 80f;
                canOverdrive = false;
                warmupSpeed = 0.019f;
                currentCraftEffect = craftEffect;
                currentUpdateEffect = null;
                currentUpdateEffectChance = 0.04f;
                currentUpdateEffectSpread = 4f;
                currentSwitchEffect = null;
                isUnitRecipe = false;
            } else {
                craftTime = rec.craftTime;
                canOverdrive = !(rec.outputHeat > 0) && rec.allowOverdrive;
                warmupSpeed = rec.warmupSpeed;
                currentCraftEffect = rec.craftEffect != null ? rec.craftEffect : craftEffect;
                currentUpdateEffect = rec.updateEffect != null ? rec.updateEffect : updateEffect;
                currentUpdateEffectChance = rec.updateEffectChance;
                currentUpdateEffectSpread = rec.updateEffectSpread;
                currentSwitchEffect = rec.switchEffect != null ? rec.switchEffect : switchEffect;

                boolean unit = false;
                if (rec.outputPayloads != null) {
                    for (PayloadStack ps : rec.outputPayloads) {
                        if (ps.item instanceof UnitType) { unit = true; break; }
                    }
                }
                isUnitRecipe = unit;
            }
            lastRecipe = null;
        }

        public float getActualProgressPerSecond() {
            if (currentRecipeObj == null) return 0f;
            float inc = getProgressIncrease(craftTime);
            if (isUnitRecipe) inc *= state.rules.unitBuildSpeed(team);
            return inc / Time.delta * 60f;
        }

        // ============ 生产条件 ============

        @Override
        public boolean shouldConsume() {
            if (currentRecipeObj == null) return false;
            if (currentRecipeObj.inputHeat > 0 && heat <= 0) return false;

            if (currentRecipeObj.outputItems != null) {
                for (ItemStack s : currentRecipeObj.outputItems) {
                    if (items.get(s.item) + s.amount > itemCapacity) return false;
                }
            }

            if (currentRecipeObj.outputLiquids != null && !ignoreLiquidFullness) {
                boolean allFull = true;
                for (LiquidStack s : currentRecipeObj.outputLiquids) {
                    if (liquids.get(s.liquid) >= liquidCapacity - 0.001f) {
                        if (!dumpExtraLiquid) return false;
                    } else allFull = false;
                }
                if (allFull) return false;
            }

            if (currentRecipeObj.outputPayloads != null) {
                int limit = currentRecipeObj.outputPayloadQueueLimit;
                if (limit == -1) {
                    if (!outputPayloads.isEmpty()) return false;
                } else if (limit > 0) {
                    int toProduce = 0;
                    for (PayloadStack stack : currentRecipeObj.outputPayloads) toProduce += stack.amount;
                    if (outputPayloads.size + toProduce > limit) return false;
                } else {
                    return false;
                }
            }

            return enabled;
        }

        // ============ 序列化 ============

        @Override
        public void write(Writes w) {
            super.write(w);
            w.i(currentRecipe);
            inputPayloads.write(w);
            w.f(payVector.x);
            w.f(payVector.y);
            w.f(payRotation);
            w.i(outputPayloads.size);
            for (Payload p : outputPayloads) Payload.write(p, w);
            w.f(heatOutput);
            w.f(heat);
            w.f(progress);
            w.f(warmup);
        }

        @Override
        public void read(Reads r, byte revision) {
            super.read(r, revision);
            currentRecipe = r.i();
            if (currentRecipe >= recipes.length || currentRecipe < -1) currentRecipe = -1;
            inputPayloads.read(r);
            payVector.set(r.f(), r.f());
            payRotation = r.f();
            outputPayloads.clear();
            int size = r.i();
            for (int i = 0; i < size; i++) {
                Payload p = Payload.read(r);
                if (p != null) outputPayloads.add(p);
            }
            heatOutput = r.f();
            heat = r.f();
            progress = r.f();
            warmup = r.f();
            refreshFromRecipe();
        }

        // ============ 效率与热量接口 ============

        @Override
        public float efficiencyScale() {
            if (currentRecipeObj == null || currentRecipeObj.inputHeat <= 0) return 1f;
            float over = Math.max(heat - currentRecipeObj.inputHeat, 0f);
            return Math.min(Mathf.clamp(heat / currentRecipeObj.inputHeat) + over / currentRecipeObj.inputHeat * overheatScale, maxEfficiency);
        }

        @Override
        public float heat() { return heatOutput; }
        @Override
        public float heatFrac() { return (currentRecipeObj != null && currentRecipeObj.outputHeat > 0) ? heatOutput / currentRecipeObj.outputHeat : 0f; }
        @Override
        public float[] sideHeat() { return sideHeat; }
        @Override
        public float heatRequirement() { return currentRecipeObj != null ? currentRecipeObj.inputHeat : 0f; }

        // ============ 重写方法 ============

        @Override
        public void update() {
            if ((timeScaleDuration -= Time.delta) <= 0f || !canOverdrive) timeScale = 1f;
            if (!headless && block.ambientSound != Sounds.none && shouldAmbientSound()) {
                control.sound.loop(block.ambientSound, this, block.ambientSoundVolume * ambientVolume());
            }
            updateConsumption();
            if (enabled || !block.noUpdateDisabled) updateTile();
        }

        @Override
        public Object config() { return currentRecipe; }

        @Override
        public void placed() {
            super.placed();
            refreshFromRecipe();
        }

        @Override
        public boolean acceptItem(Building source, Item item) {
            if (currentRecipeObj == null || currentRecipeObj.inputItems == null) return false;
            for (ItemStack s : currentRecipeObj.inputItems) {
                if (s.item == item) return items.get(item) < itemCapacity;
            }
            return false;
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            if (currentRecipeObj == null || currentRecipeObj.inputLiquids == null) return false;
            for (LiquidStack s : currentRecipeObj.inputLiquids) {
                if (s.liquid == liquid) return liquids.get(liquid) < liquidCapacity;
            }
            return false;
        }

        @Override
        public float warmup() { return warmup; }
        @Override
        public float totalProgress() { return totalProgress; }
        @Override
        public float getPowerProduction() { return (currentRecipeObj != null && currentRecipeObj.outputPower > 0) ? currentRecipeObj.outputPower * efficiency : 0f; }
        @Override
        public float progress() { return Mathf.clamp(progress); }

        @Override
        public float getProgressIncrease(float baseTime) {
            float scale = 1f;
            if (currentRecipeObj != null && currentRecipeObj.outputLiquids != null && !ignoreLiquidFullness) {
                float scaling = 1f, max = 0f;
                for (var s : currentRecipeObj.outputLiquids) {
                    float value = (liquidCapacity - liquids.get(s.liquid)) / (s.amount * edelta());
                    scaling = Math.min(scaling, value);
                    max = Math.max(max, value);
                }
                scale = dumpExtraLiquid ? Math.min(max, 1f) : scaling;
            }
            return super.getProgressIncrease(baseTime) * scale * efficiencyMultiplier();
        }

        @Override
        public void onProximityUpdate() {
            super.onProximityUpdate();
            if (attribute != null) attrsum = sumAttribute(attribute, tile.x, tile.y);
        }

        public float efficiencyMultiplier() {
            if (attribute == null) return 1f;
            return baseEfficiency + Math.min(maxBoost, boostScale * attrsum) + attribute.env();
        }

        @Override
        public void pickedUp() {
            attrsum = 0f;
            warmup = 0f;
        }

        // ============ 载荷输入接口 ============

        @Override
        public boolean acceptPayload(Building source, Payload payload) {
            if (currentRecipeObj == null || currentRecipeObj.inputPayloads == null) return false;
            var content = payload.content();
            for (var stack : currentRecipeObj.inputPayloads) {
                if (stack.item == content) {
                    if (currentRecipeObj.inputPayloadCapacity != 0)
                        return inputPayloads.get(content) < currentRecipeObj.inputPayloadCapacity;
                    else
                        return inputPayloads.get(content) < stack.amount * currentRecipeObj.inputPayloadMultiplier;
                }
            }
            return false;
        }

        @Override
        public void handlePayload(Building source, Payload payload) {
            var content = payload.content();
            Fx.payloadDeposit.at(x, y);
            inputPayloads.add(content, 1);
            if (payload instanceof UnitPayload up) up.unit.remove();
        }

        @Override
        public Payload getPayload() { return outputPayloads.isEmpty() ? null : outputPayloads.first(); }
        @Override
        public Payload takePayload() { return outputPayloads.isEmpty() ? null : outputPayloads.remove(0); }
        @Override
        public PayloadSeq getPayloads() { return inputPayloads; }

        // ============ 逻辑控制 ============

        @Override
        public Object senseObject(LAccess sensor) {
            if (sensor == LAccess.config) return currentRecipe;
            return super.senseObject(sensor);
        }

        @Override
        public double sense(LAccess sensor) {
            if (sensor == LAccess.progress) return progress();
            if (sensor == LAccess.totalLiquids && currentRecipeObj != null
                    && currentRecipeObj.outputLiquids != null && currentRecipeObj.outputLiquids.length > 0) {
                return liquids.get(currentRecipeObj.outputLiquids[0].liquid);
            }
            return super.sense(sensor);
        }

        @Override
        public void control(LAccess type, double p1, double p2, double p3, double p4) {
            if (type == LAccess.config) {
                int idx = (int) p1;
                if (idx >= -1 && idx < recipes.length) switchRecipe(idx);
                return;
            }
            super.control(type, p1, p2, p3, p4);
        }

        // ============ 渲染 ============

        @Override
        public void draw() {
            drawer.draw(this);

            if (currentRecipeObj != null && currentRecipeObj.outputPayloads != null) {
                for (PayloadStack stack : currentRecipeObj.outputPayloads) {
                    if (stack.item instanceof UnitType unitType) {
                        Draw.draw(Layer.blockOver, () ->
                                Drawf.construct(this, unitType, rotdeg() - 90f, progress, 1f, totalProgress));
                        break;
                    }
                }
            }

            if (!outputPayloads.isEmpty()) {
                Draw.z(Layer.blockOver);
                outputPayloads.first().draw();
            }
        }

        @Override
        public void drawLight() {
            super.drawLight();
            drawer.drawLight(this);
        }

        @Override
        public boolean shouldAmbientSound() {
            return efficiency > 0;
        }

        // ============ 载荷面板 ============

        private @Nullable Table payloadPanel;
        private float payloadEmptyTime;
        private final Seq<UnlockableContent> displayedTypes = new Seq<>();
        private final ObjectFloatMap<UnlockableContent> shrinkHoldTimes = new ObjectFloatMap<>();

        private void showPayloadPanel() {
            if (payloadPanel != null && payloadPanel.getScene() != null) return;

            payloadPanel = new Table(Tex.inventory);
            payloadPanel.touchable = Touchable.disabled;
            payloadPanel.setTransform(true);
            payloadPanel.margin(4f);

            rebuildPayloadPanel(true);
            if (payloadPanel == null) return;

            payloadPanel.setScale(0f, 1f);
            payloadPanel.actions(Actions.scaleTo(1f, 1f, 0.07f, Interp.pow3Out));
            payloadPanel.visible = true;
            ui.hudGroup.addChild(payloadPanel);
            positionPayloadPanel();

            payloadEmptyTime = 0f;
            shrinkHoldTimes.clear();

            payloadPanel.update(() -> {
                if (!isValid() || state.isMenu() || control.input.config.getSelected() != this) {
                    hidePayloadPanel();
                    return;
                }

                ObjectMap<UnlockableContent, Integer> current = collectAllPayloads();
                boolean empty = current.isEmpty();

                if (empty) {
                    payloadEmptyTime += Time.delta;
                    if (payloadEmptyTime >= 120f) {
                        hidePayloadPanel();
                        return;
                    }
                } else {
                    payloadEmptyTime = 0f;

                    boolean needRebuild = false;

                    // 检查新出现的载荷类型
                    for (var entry : current.entries()) {
                        if (!displayedTypes.contains(entry.key)) {
                            needRebuild = true;
                            break;
                        }
                    }

                    // 更新已显示载荷类型的消失计时器
                    for (int i = displayedTypes.size - 1; i >= 0; i--) {
                        UnlockableContent type = displayedTypes.get(i);
                        if (current.containsKey(type)) {
                            shrinkHoldTimes.put(type, 0f);
                        } else {
                            float time = shrinkHoldTimes.get(type, 0f) + Time.delta;
                            shrinkHoldTimes.put(type, time);
                            if (time >= 120f) {
                                needRebuild = true;
                            }
                        }
                    }

                    if (needRebuild) {
                        rebuildPayloadPanel(false);
                    }
                }

                positionPayloadPanel();
            });
        }

        private void rebuildPayloadPanel(boolean initial) {
            if (payloadPanel == null) return;

            ObjectMap<UnlockableContent, Integer> current = collectAllPayloads();

            // 更新显示列表：包含当前存在的和仍在延迟显示中的类型
            displayedTypes.clear();
            for (var entry : current.entries()) {
                displayedTypes.add(entry.key);
            }
            for (UnlockableContent type : shrinkHoldTimes.keys()) {
                if (!displayedTypes.contains(type) && shrinkHoldTimes.get(type, 0f) < 120f) {
                    displayedTypes.add(type);
                }
            }

            if (displayedTypes.isEmpty()) {
                payloadPanel.clearChildren();
                payloadEmptyTime = 0f;
                if (initial) {
                    payloadPanel.remove();
                    payloadPanel = null;
                }
                return;
            }

            payloadPanel.clearChildren();
            int cols = 3, index = 0;
            for (UnlockableContent c : displayedTypes) {
                Stack stack = new Stack();
                stack.add(new Image(c.uiIcon).setScaling(Scaling.fit));
                stack.add(new Table(t -> t.left().bottom()
                        .label(() -> UI.formatAmount(getPayloadCount(c)))
                        .style(Styles.outlineLabel)));
                payloadPanel.add(stack).size(40f).pad(4f);
                if (++index % cols == 0) payloadPanel.row();
            }
        }

        private int getPayloadCount(UnlockableContent content) {
            int count = inputPayloads.get(content);
            for (Payload p : outputPayloads) {
                if (p instanceof UnitPayload up && up.unit.type == content) count++;
                else if (p instanceof BuildPayload bp && bp.block() == content) count++;
            }
            return count;
        }

        private ObjectMap<UnlockableContent, Integer> collectAllPayloads() {
            ObjectMap<UnlockableContent, Integer> map = new ObjectMap<>();
            // 遍历配方中定义的所有载荷类型（而不是全局内容）
            for (UnlockableContent type : allPayloadTypes) {
                int count = inputPayloads.get(type);
                if (count > 0) map.put(type, count);
            }
            // 输出队列中的载荷也需要统计
            for (Payload p : outputPayloads) {
                if (p instanceof UnitPayload up) {
                    UnitType unitType = up.unit.type;
                    if (allPayloadTypes.contains(unitType)) {
                        map.put(unitType, map.get(unitType, 0) + 1);
                    }
                } else if (p instanceof BuildPayload bp) {
                    Block block = bp.block();
                    if (allPayloadTypes.contains(block)) {
                        map.put(block, map.get(block, 0) + 1);
                    }
                }
            }
            return map;
        }

        private void positionPayloadPanel() {
            if (payloadPanel == null) return;
            if (payloadPanel.parent != ui.hudGroup) {
                payloadPanel.remove();
                ui.hudGroup.addChild(payloadPanel);
            }
            float offsetY = 54f;
            Vec2 v = Core.input.mouseScreen(x + size * tilesize / 2f, y + size * tilesize / 2f);
            payloadPanel.pack();
            payloadPanel.setPosition(v.x, v.y + offsetY, Align.topLeft);
        }

        private void hidePayloadPanel() {
            if (payloadPanel == null) return;
            Table panel = payloadPanel;
            payloadPanel = null;
            displayedTypes.clear();
            shrinkHoldTimes.clear();
            panel.actions(Actions.sequence(
                    Actions.scaleTo(0f, 1f, 0.06f, Interp.pow3Out),
                    Actions.run(panel::remove)
            ));
        }

        @Override
        public void onRemoved() {
            super.onRemoved();
            if (payloadPanel != null) {
                payloadPanel.clearActions();
                payloadPanel.remove();
                payloadPanel = null;
            }
        }
    }

    //endregion

    //region 动态电力消费者

    public static class DynamicConsumePower extends ConsumePower {
        public DynamicConsumePower(float usage) {
            super(usage, 0f, false);
        }

        @Override
        public float requestedPower(Building entity) {
            if (entity instanceof MultiCrafterBuild build) {
                Recipe r = build.getCurrentRecipe();
                return (r != null && r.inputPower > 0 && entity.shouldConsume()) ? r.inputPower : 0f;
            }
            return 0f;
        }

        @Override
        public float efficiency(Building entity) {
            if (entity instanceof MultiCrafterBuild build) {
                Recipe r = build.getCurrentRecipe();
                if (r == null || r.inputPower <= 0) return 1f;
            }
            return entity.power.status;
        }
    }

    //endregion
}
