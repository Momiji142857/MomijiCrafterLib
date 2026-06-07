package momiji.crafters;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.math.Interp;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.actions.Actions;
import arc.scene.event.Touchable;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Stack;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectFloatMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.*;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.ai.UnitCommand;
import mindustry.content.Fx;
import mindustry.core.UI;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.Units;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.UnitCreateEvent;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.io.TypeIO;
import mindustry.type.*;
import mindustry.ui.Bar;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.payloads.PayloadBlock;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.production.HeatCrafter;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumeLiquidBase;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.world.consumers.ConsumePower;
import mindustry.world.draw.DrawBlock;
import mindustry.world.draw.DrawDefault;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.meta.StatValues;

import static mindustry.Vars.*;

public class OmniCrafter extends HeatCrafter {

    public float outputHeat;
    public float warmupRate = 0.15f;
    public float outputPower;

    public @Nullable PayloadStack[] outputPayloads;
    public @Nullable PayloadStack[] inputPayloads;
    public int inputPayloadCapacity;
    public float inputPayloadMultiplier = 2f;

    public DrawBlock drawer = new DrawDefault();
    public float payloadSpeed = 0.7f, payloadRotateSpeed = 5f;

    public OmniCrafter(String name) {
        super(name);
        hasItems = true;
        hasLiquids = true;
        hasPower = true;
    }

    @Override
    public void init() {
        super.init();
        outputsPower = outputPower > 0;
        outputsPayload = outputPayloads != null && outputPayloads.length > 0;
        acceptsPayload = inputPayloads != null && inputPayloads.length > 0;

        if (liquidCapacity < 0) {
            float maxLiquid = 1f;
            if (outputLiquids != null) for (LiquidStack s : outputLiquids) maxLiquid = Math.max(maxLiquid, s.amount * 60f);
            for (var c : consumers) {
                if (c instanceof ConsumeLiquidBase liq) maxLiquid = Math.max(maxLiquid, liq.amount * 60f);
                else if (c instanceof ConsumeLiquids liqs) for (var stack : liqs.liquids) maxLiquid = Math.max(maxLiquid, stack.amount * 60f);
            }
            liquidCapacity = Mathf.round(10f * maxLiquid);
        }

        if (outputPayloads != null) {
            for (PayloadStack stack : outputPayloads) {
                if (stack.item instanceof UnitType ut && ut.commands.size > 0) {
                    commandable = true;
                    config(UnitCommand.class, (OmniCrafterBuild b, UnitCommand cmd) -> b.command = cmd);
                    configClear((OmniCrafterBuild b) -> b.command = null);
                    break;
                }
            }
        }
    }

    /*
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
        for (var c : consumers) c.display(stats);
        if (hasLiquids) stats.add(Stat.liquidCapacity, liquidCapacity, StatUnit.liquidUnits);
        if (hasItems && itemCapacity > 0) stats.add(Stat.itemCapacity, itemCapacity, StatUnit.items);

        stats.add(Stat.output, table -> {
            table.clearChildren();
            table.left();
            table.table(Styles.grayPanel, t -> {
                t.left().defaults().left();
                t.add("[accent]Recipe[]").padTop(4).padBottom(4).left();
                t.row();

                boolean hasInput = false;
                for (var c : consumers) {
                    if (c instanceof ConsumeItems || c instanceof ConsumeLiquids || c instanceof ConsumePower || c instanceof ConsumeLiquidBase) {
                        hasInput = true;
                        break;
                    }
                }
                if (hasInput || inputPayloads != null) {
                    t.add("[lightgray]" + Core.bundle.get("stat.input") + ":[]");
                    if (inputPayloads != null) for (PayloadStack s : inputPayloads)
                        t.add(displayPayload(s.item, s.amount, craftTime, true)).pad(5);
                    if (heatRequirement > 0)
                        t.table(h -> StatValues.number(heatRequirement, StatUnit.heatUnits).display(h)).pad(5);
                    t.row();
                }

                boolean hasOutput = (outputItems != null && outputItems.length > 0) ||
                        (outputLiquids != null && outputLiquids.length > 0) ||
                        outputPower > 0 || outputHeat > 0 ||
                        (outputPayloads != null && outputPayloads.length > 0);
                if (hasOutput) {
                    t.add("[lightgray]" + Core.bundle.get("stat.output") + ":[]");
                    if (outputItems != null) for (ItemStack s : outputItems)
                        t.add(StatValues.displayItem(s.item, s.amount, craftTime, true)).pad(5);
                    if (outputLiquids != null) for (LiquidStack s : outputLiquids)
                        t.add(StatValues.displayLiquid(s.liquid, s.amount * 60f, true)).pad(5);
                    if (outputPayloads != null) for (PayloadStack s : outputPayloads)
                        t.add(displayPayload(s.item, s.amount, craftTime, true)).pad(5);
                    if (outputPower > 0)
                        t.table(p -> StatValues.number(outputPower * 60f, StatUnit.powerSecond).display(p)).pad(5);
                    if (outputHeat > 0)
                        t.table(h -> StatValues.number(outputHeat, StatUnit.heatUnits).display(h)).pad(5);
                    t.row();
                }

                t.table(info -> {
                    info.left();
                    info.add("[lightgray]" + Core.bundle.get("stat.productiontime") + ":[] " +
                            Strings.autoFixed(craftTime / 60f, 3) + " " + Core.bundle.get("unit.seconds"));
                }).padTop(4).left();
            }).growX().pad(5).row();
        });
    }
    */

    /*
    @Override
    public void setBars() {
        addBar("health", entity -> new Bar("stat.health", Pal.health, entity::healthf).blink(Color.white));

        if (consPower != null) {
            addBar("powerInput", (OmniCrafterBuild e) -> new Bar(
                    () -> {
                        float need = consPower.usage * 60f;
                        float status = e.power.status;
                        if (status >= 1f && e.efficiency >= 1f)
                            return StatUnit.powerSecond.icon + "- " + UI.formatAmount((long)need);
                        return StatUnit.powerSecond.icon + "- " +
                                UI.formatAmount((long)(need * status)) + "/" + UI.formatAmount((long)need) +
                                " [lightgray]| " + Strings.autoFixed(e.efficiency * 100, 1) + "%[]";
                    },
                    () -> Pal.powerBar,
                    () -> e.power.status
            ));
        }

        if (outputPower > 0) {
            addBar("powerOutput", (OmniCrafterBuild e) -> new Bar(
                    () -> {
                        float out = outputPower * 60f;
                        float actual = e.getPowerProduction();
                        if (e.efficiency >= 1f)
                            return StatUnit.powerSecond.icon + "+ " + UI.formatAmount((long)out);
                        return StatUnit.powerSecond.icon + "+ " +
                                UI.formatAmount((long)actual) + "/" + UI.formatAmount((long)out) +
                                " [lightgray]| " + Strings.autoFixed(e.efficiency * 100, 1) + "%[]";
                    },
                    () -> Pal.powerBar,
                    () -> e.efficiency
            ));
        }

        if (hasItems && itemCapacity > 0) {
            addBar("items", (OmniCrafterBuild e) -> new Bar(
                    () -> Core.bundle.format("bar.items", e.items.total()),
                    () -> Pal.items,
                    () -> (float)e.items.total() / itemCapacity
            ));
        }

        if (hasLiquids && outputLiquids != null) {
            for (int i = 0; i < outputLiquids.length; i++) {
                Liquid liq = outputLiquids[i].liquid;
                addBar("liquid" + i, (OmniCrafterBuild e) -> new Bar(
                        () -> {
                            float cur = e.liquids.get(liq);
                            float fill = cur / liquidCapacity;
                            String icon = " " + Fonts.getUnicodeStr(liq.name);
                            if (fill > 0.99f) return liq.localizedName + icon + " " + UI.formatAmount((long)cur);
                            return liq.localizedName + icon + " " + UI.formatAmount((long)cur) + "/" + UI.formatAmount((long)liquidCapacity)
                                    + " [lightgray]| " + Strings.autoFixed(fill * 100f, 1) + "%[]";
                        },
                        liq::barColor,
                        () -> e.liquids.get(liq) / liquidCapacity
                ));
            }
        }

        if (heatRequirement > 0) {
            addBar("heatInput", (OmniCrafterBuild e) -> new Bar(
                    () -> {
                        String label = outputHeat > 0 ? Core.bundle.get("stat.input") + Core.bundle.get("bar.heat") : Core.bundle.get("bar.heat");
                        float v = e.heat;
                        if (v > heatRequirement) return label + " " + UI.formatAmount((long)v) + " [lightgray]| 100%[]";
                        return label + " " + UI.formatAmount((long)v) + "/" + UI.formatAmount((long)heatRequirement)
                                + " [lightgray]| " + Strings.autoFixed(v / heatRequirement * 100f, 1) + "%[]";
                    },
                    () -> Pal.lightOrange,
                    () -> e.heat / heatRequirement
            ));
        }
        if (outputHeat > 0) {
            addBar("heatOutput", (OmniCrafterBuild e) -> new Bar(
                    () -> {
                        String label = heatRequirement > 0 ? Core.bundle.get("stat.output") + Core.bundle.get("bar.heat") : Core.bundle.get("bar.heat");
                        float v = e.heatOutput;
                        if (v >= outputHeat) return label + " " + UI.formatAmount((long)v) + " (100%)";
                        return label + " " + UI.formatAmount((long)v) + "/" + UI.formatAmount((long)outputHeat)
                                + " [lightgray]| " + Strings.autoFixed(v / outputHeat * 100f, 1) + "%[]";
                    },
                    () -> Pal.lightOrange,
                    () -> e.heatOutput / outputHeat
            ));
        }

        if (outputPayloads != null) {
            ObjectSet<UnitType> unitTypes = new ObjectSet<>();
            for (PayloadStack stack : outputPayloads) if (stack.item instanceof UnitType ut) unitTypes.add(ut);
            int idx = 0;
            for (UnitType type : unitTypes) {
                addBar("unit" + idx, (OmniCrafterBuild e) -> new Bar(
                        () -> Core.bundle.format("bar.unitcap", Fonts.getUnicodeStr(type.name),
                                e.team.data().countType(type),
                                type.useUnitCap ? Units.getStringCap(e.team) : "∞"),
                        () -> Pal.power,
                        () -> type.useUnitCap ? (float)e.team.data().countType(type) / Units.getCap(e.team) : 1f
                ));
                idx++;
            }
        }

        addBar("progress", (OmniCrafterBuild e) -> new Bar(
                () -> {
                    String icon = (outputPayloads != null && outputPayloads.length > 0) ? String.valueOf(Iconc.units) :
                            outputPower > 0 ? String.valueOf(Iconc.power) : String.valueOf(Iconc.crafting);
                    float p = e.progress();
                    float aps = e.getProgressIncrease(craftTime) / Time.delta * 60f;
                    float remainSec = aps > 0.00001f ? (1f - p) / aps : 5994f;
                    String timeStr = remainSec >= 60f ? Strings.autoFixed(remainSec / 60f, 2) + "min" : Strings.autoFixed(remainSec, 2) + "s";
                    return icon + " " + (int)(p * 100) + "% [orange]" + timeStr + "[]";
                },
                () -> Pal.ammo,
                e::progress
        ));
    }
    */

    @Override
    public void load() { super.load(); drawer.load(this); }
    @Override
    public void drawPlanRegion(BuildPlan plan, Eachable<BuildPlan> list) { drawer.drawPlan(this, plan, list); }
    @Override
    public TextureRegion[] icons() { return drawer.finalIcons(this); }
    @Override
    public void getRegionsToOutline(Seq<TextureRegion> out) { drawer.getRegionsToOutline(this, out); }

    public class OmniCrafterBuild extends HeatCrafterBuild implements HeatBlock {

        public float heatOutput;
        public PayloadSeq inputPayloads = new PayloadSeq();
        public final Seq<Payload> outputPayloadQueue = new Seq<>();
        private final Vec2 payVector = new Vec2();
        private float payRotation;

        public @Nullable Vec2 commandPos;
        public @Nullable UnitCommand command;

        private @Nullable Table payloadPanel;
        private float payloadEmptyTime;
        private final Seq<UnlockableContent> displayedTypes = new Seq<>();
        private final ObjectFloatMap<UnlockableContent> shrinkHoldTimes = new ObjectFloatMap<>();
        private boolean payloadsChanged = true;

        @Override
        public void updateTile() {
            super.updateTile();

            if (outputHeat > 0) {
                heatOutput = Mathf.approachDelta(heatOutput, outputHeat * efficiency, warmupRate * delta());
            } else {
                heatOutput = Mathf.approachDelta(heatOutput, 0, warmupRate * delta());
            }

            moveOutPayload();
        }

        @Override
        public void craft() {
            super.craft();

            if (outputPayloads != null) {
                for (PayloadStack stack : outputPayloads) {
                    for (int i = 0; i < stack.amount; i++) {
                        Payload p = createPayload(stack.item);
                        if (p != null) {
                            outputPayloadQueue.add(p);
                            if (p instanceof UnitPayload up) {
                                Unit unit = up.unit;
                                if (unit.isCommandable()) {
                                    if (commandPos != null) unit.command().commandPosition(commandPos);
                                    unit.command().command(command == null && unit.type.defaultCommand != null ? unit.type.defaultCommand : command);
                                }
                                Events.fire(new UnitCreateEvent(unit, this));
                            }
                            if (payVector.isZero()) {
                                payVector.setZero();
                                payRotation = rotdeg();
                            }
                        }
                    }
                }
                payloadsChanged = true;
            }
        }

        private Payload createPayload(UnlockableContent content) {
            if (content instanceof Block b) return new BuildPayload(b, team);
            else if (content instanceof UnitType unitType) return new UnitPayload(unitType.create(team));
            return null;
        }

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
                        payloadsChanged = true;
                        payVector.setZero();
                    }
                } else if (canDump) {
                    float tx = Angles.trnsx(p.rotation(), 0.1f);
                    float ty = Angles.trnsy(p.rotation(), 0.1f);
                    p.set(p.x() + tx, p.y() + ty, p.rotation());
                    if (p.dump()) {
                        outputPayloadQueue.remove(0);
                        payloadsChanged = true;
                        payVector.setZero();
                    } else {
                        p.set(p.x() - tx, p.y() - ty, p.rotation());
                    }
                }
            }
        }

        @Override
        public boolean acceptPayload(Building source, Payload payload) {
            if (inputPayloads == null) return false;
            var content = payload.content();
            for (var stack : OmniCrafter.this.inputPayloads) {
                if (stack.item == content) {
                    int capacity = inputPayloadCapacity != 0 ? inputPayloadCapacity : (int)(stack.amount * inputPayloadMultiplier);
                    return inputPayloads.get(content) < capacity;
                }
            }
            return false;
        }

        @Override
        public void handlePayload(Building source, Payload payload) {
            Fx.payloadDeposit.at(x, y);
            inputPayloads.add(payload.content(), 1);
            if (payload instanceof UnitPayload up) up.unit.remove();
            payloadsChanged = true;
        }

        @Override public Payload getPayload() { return outputPayloadQueue.isEmpty() ? null : outputPayloadQueue.first(); }
        @Override public Payload takePayload() { return outputPayloadQueue.isEmpty() ? null : outputPayloadQueue.remove(0); }
        @Override public PayloadSeq getPayloads() { return inputPayloads; }

        @Override public float heat() { return heatOutput; }
        @Override public float heatFrac() { return outputHeat > 0 ? heatOutput / outputHeat : 0f; }

        @Override
        public boolean isCommandable() {
            if (outputPayloads == null) return false;
            for (PayloadStack stack : outputPayloads) if (stack.item instanceof UnitType ut && ut.commands.size > 0) return true;
            return false;
        }
        @Override public Vec2 getCommandPosition() { return commandPos; }
        @Override public void onCommand(Vec2 target) { commandPos = target; }

        @Override
        public void tapped() {
            if (payloadPanel != null && payloadPanel.getScene() != null) {
                hidePayloadPanel();
            } else {
                showPayloadPanel();
            }
        }

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
                if (!isValid() || state.isMenu()) {
                    hidePayloadPanel();
                    return;
                }

                if (payloadsChanged) {
                    payloadsChanged = false;

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

                        for (var entry : current.entries()) {
                            if (!displayedTypes.contains(entry.key)) {
                                needRebuild = true;
                                break;
                            }
                        }

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
                }

                positionPayloadPanel();
            });
            payloadsChanged = true;
        }

        private void rebuildPayloadPanel(boolean initial) {
            if (payloadPanel == null) return;

            ObjectMap<UnlockableContent, Integer> current = collectAllPayloads();

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
            for (Payload p : outputPayloadQueue) {
                if (p instanceof UnitPayload up && up.unit.type == content) count++;
                else if (p instanceof BuildPayload bp && bp.block() == content) count++;
            }
            return count;
        }

        private ObjectMap<UnlockableContent, Integer> collectAllPayloads() {
            ObjectMap<UnlockableContent, Integer> map = new ObjectMap<>();
            Seq<UnlockableContent> types = new Seq<>();
            if (OmniCrafter.this.inputPayloads != null) for (var s : OmniCrafter.this.inputPayloads) if (!types.contains(s.item)) types.add(s.item);
            if (outputPayloads != null) for (var s : outputPayloads) if (!types.contains(s.item)) types.add(s.item);
            for (UnlockableContent type : types) {
                int count = 0;
                if (inputPayloads != null) {
                    count = inputPayloads.get(type);
                }
                if (count > 0) map.put(type, count);
            }
            for (Payload p : outputPayloadQueue) {
                if (p instanceof UnitPayload up && types.contains(up.unit.type)) {
                    map.put(up.unit.type, map.get(up.unit.type, 0) + 1);
                } else if (p instanceof BuildPayload bp && types.contains(bp.block())) {
                    map.put(bp.block(), map.get(bp.block(), 0) + 1);
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

        @Override
        public void write(Writes w) {
            super.write(w);
            w.f(heatOutput);
            inputPayloads.write(w);
            w.f(payVector.x); w.f(payVector.y); w.f(payRotation);
            w.i(outputPayloadQueue.size);
            for (Payload p : outputPayloadQueue) Payload.write(p, w);
            TypeIO.writeVecNullable(w, commandPos);
            TypeIO.writeCommand(w, command);
        }

        @Override
        public void read(Reads r, byte revision) {
            super.read(r, revision);
            heatOutput = r.f();
            inputPayloads.read(r);
            payVector.set(r.f(), r.f()); payRotation = r.f();
            outputPayloadQueue.clear();
            int size = r.i();
            for (int i = 0; i < size; i++) { Payload p = Payload.read(r); if (p != null) outputPayloadQueue.add(p); }
            commandPos = TypeIO.readVecNullable(r);
            command = TypeIO.readCommand(r);
        }
    }

    public static Table displayPayload(UnlockableContent content, int amount, float craftTime, boolean showName) {
        Table t = new Table();
        t.add(StatValues.stack(content, amount, !showName));
        t.add((showName ? content.localizedName + "\n" : "") +
                "[lightgray]" + Strings.autoFixed(amount / (craftTime / 60f), 3) +
                StatUnit.perSecond.localized()).padLeft(2).padRight(5).style(Styles.outlineLabel);
        return t;
    }
}
