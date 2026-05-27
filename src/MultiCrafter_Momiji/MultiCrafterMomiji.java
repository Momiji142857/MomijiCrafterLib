package MultiCrafter_Momiji;

import arc.Core;
import arc.Events;
import arc.audio.Sound;
import arc.graphics.Color;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.EnumSet;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.*;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.content.Fx;
import mindustry.core.UI;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.Effect;
import mindustry.entities.Units;
import mindustry.game.EventType.UnitCreateEvent;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.Iconc;
import mindustry.gen.Sounds;
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
import mindustry.world.meta.*;

import static mindustry.Vars.*;

/**
 * 多配方工厂方块，每个配方可以独立配置输入/输出/热量/载荷等，<p>
 * 并与原版 {@code GenericCrafter}、{@code HeatProducer}、{@code HeatCrafter}、{@code UnitFactory} 行为对齐。
 *
 * @since 2026-05-27
 * @author Momiji142857 (with DeepSeek)
 */
public class MultiCrafterMomiji extends Block {

    //region 方块公共属性

    /** 所有可切换的配方列表（静态共享，JSON 加载后直接赋值） */
    public static Recipe[] recipes = {};

    /** 载荷输出队列容量（全局默认值，配方可覆盖） */
    public int payloadCapacity = 10;

    /** 物品容量，-1 自动计算 */
    public int itemCapacity = -1;

    /** 默认的制作特效（配方未配置时使用） */
    public @Nullable Effect craftEffect;

    /** 如果为 true，液体输出满了也允许生产（排空多余液体） */
    public boolean dumpExtraLiquid = true;

    /** 热量效率上限（超过 1 的部分来自过热） */
    public float maxEfficiency = 1f;

    /** 过热倍率（超出的热量效率系数） */
    public float overheatScale = 1f;

    /** 是否忽略液体满导致的停产 */
    public boolean ignoreLiquidFullness = false;

    /** 切换配方的默认特效（配方未配置时使用） */
    public @Nullable Effect switchEffect = Fx.rotateBlock;

    //endregion

    public MultiCrafterMomiji(String name) {
        super(name);
        update = true;
        solid = true;
        hasItems = true;
        ambientSound = Sounds.loopMachine;
        sync = true;
        ambientSoundVolume = 0.03f;
        drawArrow = false;
        hasPower = true;
        hasLiquids = true;
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
        // 统一化配方单值字段为数组
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

        super.init();
    }

    //endregion

    //region 统计与条状

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

        // 液体存储条（自动为每个出现的液体添加）
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

        // 生产进度条（显示受电力影响的真实剩余时间）
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
                    float remainSec = aps > 0.00001f ? (1f - p) / aps : 5994f; // 5994s = 99.9min 显示为上限

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

    //region 工具方法（数字格式化、载荷显示）

    /** 格式化数字：>=1000 使用 UI 缩写，否则两位小数，极小值科学计数 */
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

    /** 电力数值格式化：>=1,000,000 使用 UI 缩写 */
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

    /** 显示载荷信息（含每秒产量） */
    public static Table displayPayload(UnlockableContent content, int amount, float craftTime, boolean showName) {
        Table t = new Table();
        t.add(StatValues.stack(content, amount, !showName));
        t.add((showName ? content.localizedName + "\n" : "") +
                "[lightgray]" + Strings.autoFixed(amount / (craftTime / 60f), 3) +
                StatUnit.perSecond.localized()).padLeft(2).padRight(5).style(Styles.outlineLabel);
        return t;
    }

    //endregion

    //region 配方定义

    /** 单个配方的所有参数，支持 JSON 加载 */
    public static class Recipe {
        // 输入
        public @Nullable ItemStack[] inputItems;
        public @Nullable LiquidStack[] inputLiquids;
        public float inputPower;
        public float inputHeat;
        public @Nullable PayloadStack[] inputPayloads;

        // 输出
        public @Nullable ItemStack[] outputItems;
        public @Nullable LiquidStack[] outputLiquids;
        public float outputPower;
        public float outputHeat;
        public @Nullable PayloadStack[] outputPayloads;

        // 时间与效率
        public float craftTime = 80f;
        public boolean allowOverdrive = true;
        public float warmupSpeed = 0.019f;

        // 特效与音效
        public @Nullable Effect craftEffect;
        public @Nullable Effect updateEffect;
        public float updateEffectChance = 0.04f;
        public float updateEffectSpread = 4f;
        public @Nullable Effect switchEffect;
        public @Nullable Sound craftSound;
        public @Nullable Sound updateSound;

        // 载荷相关
        public int inputPayloadCapacity = 0;
        public float inputPayloadMultiplier = 2f;
        /** 输出载荷队列上限：-1=队列为空时生产，>0=按载荷总数限制，0=禁止生产 */
        public int outputPayloadQueueLimit = -1;

        // 液体输出方向
        public int[] liquidOutputDirections = {-1};

        // 简写兼容字段（JSON 单值自动转为数组）
        public @Nullable ItemStack outputItem;
        public @Nullable LiquidStack outputLiquid;
        public @Nullable ItemStack inputItem;
        public @Nullable LiquidStack inputLiquid;

        /** 将 JSON 单值字段转换为标准数组形式 */
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

        // 快捷查询是否存在特定类型
        public static boolean havePower(Recipe r) { return r.inputPower > 0 || r.outputPower > 0; }
        public static boolean haveItems(Recipe r) { return (r.inputItems != null && r.inputItems.length > 0) || (r.outputItems != null && r.outputItems.length > 0); }
        public static boolean haveLiquids(Recipe r) { return (r.inputLiquids != null && r.inputLiquids.length > 0) || (r.outputLiquids != null && r.outputLiquids.length > 0); }
        public static boolean haveInputPayloads(Recipe r) { return r.inputPayloads != null && r.inputPayloads.length > 0; }
        public static boolean haveOutputPayloads(Recipe r) { return r.outputPayloads != null && r.outputPayloads.length > 0; }

        /** 计算配方描述表格的列跨距 */
        private static int calcColspan(Recipe rec) {
            int inputCount = 0, outputCount = 0;
            if (rec.inputItems != null) inputCount += rec.inputItems.length;
            if (rec.inputLiquids != null) inputCount += rec.inputLiquids.length;
            if (rec.inputPayloads != null) inputCount += rec.inputPayloads.length;
            inputCount += 2; // 电力和热量各占一列
            if (rec.outputItems != null) outputCount += rec.outputItems.length;
            if (rec.outputLiquids != null) outputCount += rec.outputLiquids.length;
            if (rec.outputPayloads != null) outputCount += rec.outputPayloads.length;
            outputCount += 2;
            return Math.max(1, Math.max(inputCount, outputCount)) + 2;
        }
    }

    //endregion

    //region 建筑实体

    /**
     * 多配方工厂建筑实体，同时实现 {@link HeatBlock} 和 {@link HeatConsumer} 接口，
     * 以支持热量输入和输出。
     */
    public class MultiCrafterBuild extends Building implements HeatBlock, HeatConsumer {

        // ---------- 配方相关 ----------
        /** 当前配方索引（序列化/配置用） */
        public int currentRecipe;
        /** 当前配方的缓存对象（运行时直接使用，避免数组查找） */
        private Recipe currentRecipeObj;

        // ---------- 配方派生属性缓存 ----------
        /** 当前配方制造时间（tick） */
        public float craftTime = 80f;
        /** 是否允许超速 */
        public boolean canOverdrive = true;
        /** 效率平滑过渡速度 */
        public float warmupSpeed = 0.019f;
        /** 当前实际的制作特效 */
        public @Nullable Effect currentCraftEffect;
        /** 当前实际的更新特效 */
        public @Nullable Effect currentUpdateEffect;
        /** 当前实际的特效触发概率 */
        public float currentUpdateEffectChance = 0.04f;
        /** 当前实际的特效散布范围 */
        public float currentUpdateEffectSpread = 4f;
        /** 当前实际的切换特效 */
        public @Nullable Effect currentSwitchEffect;
        /** 当前配方是否生产单位（影响 unitCost / unitBuildSpeed） */
        public boolean isUnitRecipe;

        // ---------- 热量 ----------
        public float heat = 0f;
        public float heatOutput = 0f;
        /** 侧面热量记录 */
        public float[] sideHeat = new float[4];

        // ---------- 生产进度 ----------
        public float progress;
        public float totalProgress;
        public float warmup;
        /** 热量输出的平滑速率 */
        public float warmupRate = 0.15f;

        // ---------- 载荷 ----------
        /** 输入的载荷存储（类似原版 PayloadSeq） */
        public PayloadSeq blocks = new PayloadSeq();
        /** 缓存当前配方输入载荷序列，避免重复构建 */
        private final Seq<PayloadStack> cachedInputPayloadSeq = new Seq<>();
        /** 待输出载荷队列 */
        public final Seq<Payload> outputPayloadQueue = new Seq<>();
        /** 用于判断输入载荷序列是否需要重建 */
        private Recipe lastRecipe = null;

        // ---------- 载荷移动动画 ----------
        private final Vec2 payVector = new Vec2();
        private float payRotation = 0f;
        public final float payloadSpeed = 0.7f;
        public final float payloadRotateSpeed = 5f;

        // ============ 公共访问 ============

        /** 返回当前配方的缓存对象（可能为 null） */
        public Recipe getCurrentRecipe() {
            return currentRecipeObj;
        }

        /** 动态获取当前配方所需的输入载荷序列（带缓存） */
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
            // 计算热量输入
            if (currentRecipeObj != null && currentRecipeObj.inputHeat > 0)
                heat = calculateHeat(sideHeat);

            if (efficiency > 0) {
                // 单位配方享受 unitBuildSpeed 加成
                float buildMul = isUnitRecipe ? state.rules.unitBuildSpeed(team) : 1f;
                progress += getProgressIncrease(craftTime) * buildMul;
                warmup = Mathf.approachDelta(warmup, warmupTarget(), warmupSpeed);
                totalProgress += warmup * delta();

                // 平滑输出液体
                if (currentRecipeObj != null && currentRecipeObj.outputLiquids != null) {
                    float inc = getProgressIncrease(1f);
                    for (LiquidStack output : currentRecipeObj.outputLiquids) {
                        handleLiquid(this, output.liquid, Math.min(output.amount * inc, liquidCapacity - liquids.get(output.liquid)));
                    }
                }

                // 随机更新特效
                if (wasVisible && currentUpdateEffect != null) {
                    if (Mathf.chanceDelta(currentUpdateEffectChance)) {
                        currentUpdateEffect.at(
                                x + Mathf.range(size * currentUpdateEffectSpread),
                                y + Mathf.range(size * currentUpdateEffectSpread)
                        );
                    }
                }
            } else {
                warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
            }

            // 热量输出平滑
            if (currentRecipeObj != null && currentRecipeObj.outputHeat > 0)
                heatOutput = Mathf.approachDelta(heatOutput, currentRecipeObj.outputHeat * efficiency, warmupRate * delta());
            else
                heatOutput = Mathf.approachDelta(heatOutput, 0, warmupRate * delta());

            // 输出载荷运动与制造完成检测
            moveOutPayload();
            if (progress >= 1f) craft();
            dumpOutputs();
        }

        /** 热量目标比例 */
        public float warmupTarget() {
            if (currentRecipeObj == null || currentRecipeObj.inputHeat <= 0) return 1f;
            return Mathf.clamp(heat / currentRecipeObj.inputHeat);
        }

        // ============ 载荷输出 ============

        /** 推动输出队列中的载荷向出口移动 */
        public void moveOutPayload() {
            if (outputPayloadQueue.isEmpty()) return;

            Payload p = outputPayloadQueue.first();
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
                        outputPayloadQueue.remove(0);
                        payVector.setZero();
                    }
                } else if (canDump) {
                    float tx = Angles.trnsx(p.rotation(), 0.1f);
                    float ty = Angles.trnsy(p.rotation(), 0.1f);
                    p.set(p.x() + tx, p.y() + ty, p.rotation());
                    if (p.dump()) {
                        outputPayloadQueue.remove(0);
                        payVector.setZero();
                    } else {
                        p.set(p.x() - tx, p.y() - ty, p.rotation());
                    }
                }
            }
        }

        // ============ 生产完成 ============

        /** 生产完成：消耗原料，生成物品/载荷，播放特效，触发事件 */
        public void craft() {
            if (currentRecipeObj == null) return;
            consume();

            // 输出物品
            if (currentRecipeObj.outputItems != null) {
                for (ItemStack output : currentRecipeObj.outputItems) {
                    for (int i = 0; i < output.amount; i++) offload(output.item);
                }
            }

            // 输出载荷
            if (currentRecipeObj.outputPayloads != null) {
                for (PayloadStack stack : currentRecipeObj.outputPayloads) {
                    for (int i = 0; i < stack.amount; i++) {
                        Payload p = createPayload(stack.item);
                        if (p != null) {
                            outputPayloadQueue.add(p);
                            // 单位负载触发 UnitCreateEvent
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

            // 制作特效
            if (currentCraftEffect != null && wasVisible) {
                currentCraftEffect.at(x, y);
            }
            progress %= 1f;
        }

        /** 创建载荷对象 */
        public Payload createPayload(UnlockableContent content) {
            if (content instanceof Block b) return new BuildPayload(b, team);
            else if (content instanceof UnitType unitType) return new UnitPayload(unitType.create(team));
            return null;
        }

        // ============ 输出 ============

        /** 每帧尝试输出物品和液体 */
        public void dumpOutputs() {
            if (currentRecipeObj == null) return;

            // 物品输出受 timer 控制
            if (currentRecipeObj.outputItems != null && timer(timerDump, dumpTime / timeScale)) {
                for (ItemStack output : currentRecipeObj.outputItems) {
                    dump(output.item);
                }
            }

            // 液体输出每帧无限制
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
            table.clearChildren();
            table.background(Styles.black6);
            table.top().left();
            table.margin(2f);

            for (int i = 0; i < recipes.length; i++) {
                final int idx = i;
                Recipe rec = recipes[i];
                final int recipeNum = i + 1;

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

                btn.table(btnTable -> {
                    btnTable.left().defaults().left();
                    btnTable.add(String.valueOf(recipeNum))
                            .width(24f).right()
                            .padRight(8f).padLeft(8f)
                            .color(Color.lightGray);

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

        // ============ 配方切换与同步 ============

        /** 切换配方索引，重置进度和热量输出，播放特效 */
        private void switchRecipe(int idx) {
            if (idx < -1 || idx >= recipes.length) idx = -1;
            if (currentRecipe == idx) return;
            currentRecipe = idx;
            progress = 0f;
            heatOutput = 0f;
            refreshFromRecipe();

            Effect effect = currentSwitchEffect != null ? currentSwitchEffect : switchEffect;
            effect.at(x, y, block.size);
        }

        /** 根据 currentRecipe 索引一次性同步所有配方派生属性及缓存对象 */
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
                currentUpdateEffect = rec.updateEffect;
                currentUpdateEffectChance = rec.updateEffectChance;
                currentUpdateEffectSpread = rec.updateEffectSpread;
                currentSwitchEffect = rec.switchEffect != null ? rec.switchEffect : switchEffect;

                boolean unit = false;
                if (rec.outputPayloads != null) {
                    for (PayloadStack ps : rec.outputPayloads) {
                        if (ps.item instanceof UnitType) {
                            unit = true;
                            break;
                        }
                    }
                }
                isUnitRecipe = unit;
            }
            lastRecipe = null; // 强制重建输入载荷序列
        }

        /** 计算受所有因素影响的实际每秒进度（用于进度条显示） */
        public float getActualProgressPerSecond() {
            if (currentRecipeObj == null) return 0f;
            float inc = getProgressIncrease(craftTime);   // 已含效率、液体缩放、超速
            if (isUnitRecipe) inc *= state.rules.unitBuildSpeed(team);
            return inc / Time.delta * 60f; // 转换 tick 为秒
        }

        // ============ 是否可生产 ============

        @Override
        public boolean shouldConsume() {
            if (currentRecipeObj == null) return false;

            // 需要热量但无热源时不消耗
            if (currentRecipeObj.inputHeat > 0 && heat <= 0) return false;

            // 物品输出空间检查
            if (currentRecipeObj.outputItems != null) {
                for (ItemStack s : currentRecipeObj.outputItems) {
                    if (items.get(s.item) + s.amount > itemCapacity) return false;
                }
            }

            // 液体输出空间检查
            if (currentRecipeObj.outputLiquids != null && !ignoreLiquidFullness) {
                boolean allFull = true;
                for (LiquidStack s : currentRecipeObj.outputLiquids) {
                    if (liquids.get(s.liquid) >= liquidCapacity - 0.001f) {
                        if (!dumpExtraLiquid) return false;
                    } else allFull = false;
                }
                if (allFull) return false;
            }

            // 载荷输出队列限制
            if (currentRecipeObj.outputPayloads != null) {
                int limit = currentRecipeObj.outputPayloadQueueLimit;
                if (limit == -1) {
                    if (!outputPayloadQueue.isEmpty()) return false;
                } else if (limit > 0) {
                    int currentCount = outputPayloadQueue.size;
                    int toProduce = 0;
                    for (PayloadStack stack : currentRecipeObj.outputPayloads) toProduce += stack.amount;
                    if (currentCount + toProduce > limit) return false;
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
            blocks.write(w);
            w.f(payVector.x);
            w.f(payVector.y);
            w.f(payRotation);
            w.i(outputPayloadQueue.size);
            for (Payload p : outputPayloadQueue) Payload.write(p, w);
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
            blocks.read(r);
            payVector.set(r.f(), r.f());
            payRotation = r.f();
            outputPayloadQueue.clear();
            int size = r.i();
            for (int i = 0; i < size; i++) {
                Payload p = Payload.read(r);
                if (p != null) outputPayloadQueue.add(p);
            }
            heatOutput = r.f();
            heat = r.f();
            progress = r.f();
            warmup = r.f();

            refreshFromRecipe();
        }

        // ============ 效率与热量相关接口 ============

        @Override
        public float efficiencyScale() {
            if (currentRecipeObj == null || currentRecipeObj.inputHeat <= 0) return 1f;
            float over = Math.max(heat - currentRecipeObj.inputHeat, 0f);
            return Math.min(Mathf.clamp(heat / currentRecipeObj.inputHeat) + over / currentRecipeObj.inputHeat * overheatScale, maxEfficiency);
        }

        @Override
        public float heat() { return heatOutput; }

        @Override
        public float heatFrac() {
            return (currentRecipeObj != null && currentRecipeObj.outputHeat > 0) ? heatOutput / currentRecipeObj.outputHeat : 0f;
        }

        @Override
        public float[] sideHeat() { return sideHeat; }

        @Override
        public float heatRequirement() {
            return currentRecipeObj != null ? currentRecipeObj.inputHeat : 0f;
        }

        // ============ 基本重写 ============

        @Override
        public void update() {
            if ((timeScaleDuration -= Time.delta) <= 0f || !canOverdrive) {
                timeScale = 1f;
            }
            if (!headless && block.ambientSound != Sounds.none && shouldAmbientSound()) {
                control.sound.loop(block.ambientSound, this, block.ambientSoundVolume * ambientVolume());
            }
            updateConsumption();
            if (enabled || !block.noUpdateDisabled) {
                updateTile();
            }
        }

        @Override
        public Object config() {
            return currentRecipe;
        }

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
        public float getPowerProduction() {
            return (currentRecipeObj != null && currentRecipeObj.outputPower > 0) ? currentRecipeObj.outputPower * efficiency : 0f;
        }
        @Override
        public float progress() { return Mathf.clamp(progress); }

        @Override
        public float getProgressIncrease(float baseTime) {
            if (currentRecipeObj == null || currentRecipeObj.outputLiquids == null || ignoreLiquidFullness) {
                return super.getProgressIncrease(baseTime);
            }
            float scaling = 1f, max = 0f;
            for (var s : currentRecipeObj.outputLiquids) {
                float value = (liquidCapacity - liquids.get(s.liquid)) / (s.amount * edelta());
                scaling = Math.min(scaling, value);
                max = Math.max(max, value);
            }
            return super.getProgressIncrease(baseTime) * (dumpExtraLiquid ? Math.min(max, 1f) : scaling);
        }

        // ============ 载荷输入接口 ============

        @Override
        public boolean acceptPayload(Building source, Payload payload) {
            if (currentRecipeObj == null || currentRecipeObj.inputPayloads == null) return false;
            var content = payload.content();
            for (var stack : currentRecipeObj.inputPayloads) {
                if (stack.item == content) {
                    if (currentRecipeObj.inputPayloadCapacity != 0)
                        return blocks.get(content) < currentRecipeObj.inputPayloadCapacity;
                    else
                        return blocks.get(content) < stack.amount * currentRecipeObj.inputPayloadMultiplier;
                }
            }
            return false;
        }

        @Override
        public void handlePayload(Building source, Payload payload) {
            var content = payload.content();
            Fx.payloadDeposit.at(x, y);
            blocks.add(content, 1);
            if (payload instanceof UnitPayload up) up.unit.remove();
        }

        @Override
        public Payload getPayload() {
            return outputPayloadQueue.isEmpty() ? null : outputPayloadQueue.first();
        }

        @Override
        public Payload takePayload() {
            if (outputPayloadQueue.isEmpty()) return null;
            return outputPayloadQueue.remove(0);
        }

        @Override
        public PayloadSeq getPayloads() {
            return blocks;
        }

        @Override
        public Object senseObject(LAccess sensor) {
            if (sensor == LAccess.config) {
                return currentRecipe; // 当前配方索引
            }
            return super.senseObject(sensor);
        }

        @Override
        public void control(LAccess type, double p1, double p2, double p3, double p4) {
            if (type == LAccess.config) {
                int idx = (int) p1;
                if (idx >= -1 && idx < recipes.length) {
                    switchRecipe(idx);
                }
                return;
            }
            super.control(type, p1, p2, p3, p4);
        }

    }

    //endregion

    //region 动态电力消费者

    /**
     * 动态电力消费者：无电力需求的配方不消耗电力、效率恒为 1；
     * 有电力需求的配方则按电网状态决定效率。
     */
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
                if (r == null || r.inputPower <= 0) return 1f; // 不需要电，满效率
            }
            return entity.power.status;
        }

        // 非缓冲模式无需 trigger 操作
    }

    //endregion
}
