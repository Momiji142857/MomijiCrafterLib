package momiji.type;

import arc.util.Log;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.blocks.distribution.ItemBridge;

import static mindustry.Vars.content;

/**
 * 批量倾倒版物品桥. 相对原版 {@link ItemBridge} 改两处:
 * <ol>
 *   <li>doDump() 使用可配置频率的 dump 节流, 默认每秒 60 次</li>
 *   <li>dump(Item) 改为批量倾倒 + 小物品数快速路径, 突破原版每次 1 个的吞吐上限</li>
 * </ol>
 *
 * @author Momiji142857
 * @see ItemBridge
 * @since 2026-08-01
 */
public class BatchDumpBridge extends ItemBridge{

    /** 每逻辑 tick 传递物品数. */
    public float speed = -1f;
    /** 单次 dump() 调用的物品预算上限. */
    public int dumpBudget = 128;
    /** 每秒调用 dump(Item) 的最大次数. 默认 60. */
    public int dumpPerSecond = 60;
    /** 统计显示 (物品/秒). 目前没有实际作用 */
    public float displayedSpeed = -1;

    public BatchDumpBridge(String name){
        super(name);
        hasPower = false;
        hasItems = true;
        canOverdrive = true;
    }

    @Override
    public void init(){
        // 检查数值合理性
        if(transportTime <= 0f){
            if(speed < 0f){
                transportTime = 2f;
                Log.err("[BatchDumpBridge] Block '@' has neither transportTime nor speed configured. " + "Fallback to transportTime=2f (30 items/s). Please set one of them explicitly.", name);
            }else{
                transportTime = (speed == 0f) ? Float.MAX_VALUE : 1f / speed;
            }
        }
        if(displayedSpeed < 0f){
            displayedSpeed = (transportTime == Float.MAX_VALUE) ? 0f : 60f / transportTime;
        }

        super.init();
    }

    public class BatchDumpBridgeBuild extends ItemBridgeBuild{
        /** dump 节流累积器, 每帧 += delta * dumpPerSecond, >=1 允许 1 次 dump 调用. */
        float dumpAccum = 0f;

        @Override
        public void doDump(){
            if(dumpPerSecond <= 0) return;
            dumpAccum += delta() * dumpPerSecond;
            if(dumpAccum < 1f) return;
            while(dumpAccum >= 1f){
                if(!dump()) break;
                dumpAccum -= 1f;
            }
            if(dumpAccum > dumpPerSecond) dumpAccum = dumpPerSecond;
        }

        @Override
        public boolean dump(Item todump){
            if(!block.hasItems || items.total() == 0 || proximity.size == 0 || (todump != null && !items.has(todump)))
                return false;

            int proxSize = proximity.size;
            int startDir = cdump;

            if(todump == null && items.total() <= proxSize * 2){
                int remaining = items.total();
                boolean anyPushed = false;

                for(int i = 0; i < remaining && items.total() > 0; i++){
                    boolean pushedOne = false;
                    for(int dirStep = 0; dirStep < proxSize; dirStep++){
                        int dirIdx = (startDir + i + dirStep) % proxSize;
                        Building other = proximity.get(dirIdx);
                        if(other == null) continue;

                        Item item = items.take();
                        if(item == null) return anyPushed;

                        if(canDump(other, item) && other.acceptItem(this, item)){
                            other.handleItem(this, item);
                            incrementDump(proxSize);
                            anyPushed = true;
                            pushedOne = true;
                            break;
                        }else{
                            items.add(item, 1);
                            items.undoFlow(item);
                        }
                    }
                    if(!pushedOne) break;
                }
                return anyPushed;
            }

            int totalItemTypes = content.items().size;
            Object[] itemArray = content.items().items;
            int budget = Math.min(items.total(), dumpBudget);
            int dumped = 0;
            boolean anyPushed = false;

            while(dumped < budget && items.total() > 0){
                int prevDumped = dumped;
                for(int dirStep = 0; dirStep < proxSize && dumped < budget; dirStep++){
                    int dirIdx = (startDir + dumped / proxSize + dirStep) % proxSize;
                    Building other = proximity.get(dirIdx);
                    if(other == null) continue;

                    if(todump == null){
                        for(int typeIdx = 0; typeIdx < totalItemTypes && items.total() > 0; typeIdx++){
                            Item item = (Item) itemArray[typeIdx];
                            if(item == null || !items.has(item) || !canDump(other, item)) continue;

                            while(dumped < budget && items.has(item) && canDump(other, item)){
                                if(other.acceptItem(this, item)){
                                    other.handleItem(this, item);
                                    items.remove(item, 1);
                                    incrementDump(proxSize);
                                    dumped++;
                                    anyPushed = true;
                                }else{
                                    break;
                                }
                            }
                            if(dumped >= budget) break;
                        }
                    }else{
                        if(!canDump(other, todump)) continue;
                        while(dumped < budget && items.has(todump) && canDump(other, todump)){
                            if(other.acceptItem(this, todump)){
                                other.handleItem(this, todump);
                                items.remove(todump, 1);
                                incrementDump(proxSize);
                                dumped++;
                                anyPushed = true;
                            }else{
                                break;
                            }
                        }
                    }
                }
                if(dumped == prevDumped) break;
            }
            return anyPushed;
        }
    }
}
