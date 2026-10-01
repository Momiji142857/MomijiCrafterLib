package momiji;

import arc.func.Boolf;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.util.Scaling;
import arc.util.Strings;
import mindustry.type.Liquid;
import mindustry.ui.Styles;
import mindustry.world.blocks.production.Drill;
import mindustry.world.consumers.ConsumeLiquidBase;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.meta.StatValue;
import mindustry.world.meta.StatValues;

import static mindustry.Vars.content;

/**
 * 可根据冷却液的热容量调整加速倍率的钻头.
 *
 * @since 2026-08-03
 * @see Drill
 * @see StatValues
 * @author 火星人076, Momiji142857 (with DeepSeek)
 * */
public class CoolantDrill extends Drill {

    /** 基准热容, 热容量低于该值的冷却液将不会生效. */
    public float baseHeatCapacity = 0.4f;
    /** 热容加成系数, 冷却液热容量超出基准热容部分的加成系数. */
    public float coolantMultiplier = 0.5f;

    public CoolantDrill(String name){
        super(name);
    }

    @Override
    public void setStats(){
        super.setStats();

        if(liquidBoostIntensity != 1 && findConsumer(f -> f instanceof ConsumeLiquidBase && f.booster) instanceof ConsumeLiquidBase consBase){
            stats.remove(Stat.booster);
            stats.add(Stat.booster,
                    buildCoolantSpeedStat(
                            consBase.amount,
                            consBase::consumes,
                            "{0}" + StatUnit.timesSpeed.localized()
            ));
        }
    }

    /** 完全照搬原版 {@link StatValues speedBoosters()} 的表格绘制, 但倍率按液体热容动态计算 */
    private StatValue buildCoolantSpeedStat(float amount, Boolf<Liquid> filter, String unit){
        return table -> {
            table.row();
            table.table(c -> {
                for(Liquid liquid : content.liquids()){
                    if(!filter.get(liquid)) continue;

                    float heat = liquid.heatCapacity;
                    float speed = liquidBoostIntensity * liquidBoostIntensity *
                            (1f + coolantMultiplier * (heat - baseHeatCapacity)) *
                            (1f + coolantMultiplier * (heat - baseHeatCapacity));

                    c.table(Styles.grayPanel, b -> {
                        b.image(liquid.uiIcon).size(40).pad(10f).left().scaling(Scaling.fit).with(i -> StatValues.withTooltip(i, liquid, false));
                        b.table(info -> {
                            info.add(liquid.localizedName).left().row();
                            info.add(Strings.autoFixed(amount * 60f, 2) + StatUnit.perSecond.localized()).left().color(Color.lightGray);
                        });

                        b.table(bt -> {
                            bt.right().defaults().padRight(3).left();
                            bt.add(unit.replace("{0}", "[stat]" + Strings.autoFixed(speed, 2) + "[lightgray]")).pad(5);
                        }).right().grow().pad(10f).padRight(15f);
                    }).growX().pad(5).row();
                }
            }).growX().colspan(table.getColumns());
            table.row();
        };
    }

    public class CoolantDrillBuild extends DrillBuild {
        @Override
        public void updateTile(){
            if(timer(timerDump, dumpTime / timeScale)){
                dump(dominantItem != null && items.has(dominantItem) ? dominantItem : null);
            }

            if(dominantItem == null){
                return;
            }

            timeDrilled += warmup * delta();

            float delay = getDrillTime(dominantItem);

            Liquid coolantLiquid = liquids.current(); // read the liquid's coolant power and use it

            if(items.total() < itemCapacity && dominantItems > 0 && efficiency > 0){
                float speed = Mathf.lerp(1f, liquidBoostIntensity * (1f + coolantMultiplier * (coolantLiquid.heatCapacity - baseHeatCapacity)), optionalEfficiency) * efficiency;

                lastDrillSpeed = (speed * dominantItems * warmup) / delay;
                warmup = Mathf.approachDelta(warmup, speed, warmupSpeed);
                progress += delta() * dominantItems * speed * warmup;

                if(Mathf.chanceDelta(updateEffectChance * warmup))
                    updateEffect.at(x + Mathf.range(size * 2f), y + Mathf.range(size * 2f));
            }else{
                lastDrillSpeed = 0f;
                warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
                return;
            }

            if(dominantItems > 0 && progress >= delay && items.total() < itemCapacity){
                int amount = (int)(progress / delay);
                for(int i = 0; i < amount; i++){
                    offload(dominantItem);
                }

                progress %= delay;

                if(wasVisible && Mathf.chanceDelta(drillEffectChance * warmup)) drillEffect.at(x + Mathf.range(drillEffectRnd), y + Mathf.range(drillEffectRnd), dominantItem.color);
            }
        }

    }

}
