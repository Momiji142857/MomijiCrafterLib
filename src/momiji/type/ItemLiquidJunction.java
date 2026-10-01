package momiji.type;

import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.BufferItem;
import mindustry.gen.Building;
import mindustry.gen.Teamc;
import mindustry.type.Item;
import mindustry.world.DirectionalItemBuffer;
import mindustry.world.blocks.distribution.Junction;
import mindustry.world.blocks.liquid.LiquidJunction;
import mindustry.world.meta.BlockGroup;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;

import static mindustry.Vars.content;

/**
 * 可同时传输物品与液体的交叉器.<p>
 * 物品传输逻辑等价于 {@link Junction}, 液体传输逻辑等价于 {@link LiquidJunction}.
 *
 * @author momiji142857
 * @see Junction
 * @see LiquidJunction
 * @since 2026-05-27
 */
public class ItemLiquidJunction extends LiquidJunction{
    public float speed = 26;
    public int capacity = 6;
    public float displayedSpeed = 13f;

    public ItemLiquidJunction(String name){
        super(name);
        update = true;
        solid = false;
        underBullets = true;
        group = BlockGroup.transportation;
        unloadable = false;
        noUpdateDisabled = true;
        floating = true;
    }

    @Override
    public void setStats(){
        super.setStats();

        stats.add(Stat.itemsMoved, displayedSpeed, StatUnit.itemsSecond);
        stats.add(Stat.itemCapacity, table -> table.add(Strings.autoFixed(capacity, 2) + " " + StatUnit.items.localized() + " " + StatUnit.perSide.localized()));
    }

    @Override
    public boolean outputsItems(){
        return true;
    }

    public class ItemLiquidJunctionBuild extends LiquidJunctionBuild{
        public DirectionalItemBuffer buffer = new DirectionalItemBuffer(capacity);

        @Override
        public int acceptStack(Item item, int amount, Teamc source){
            return 0;
        }

        @Override
        public void updateTile(){

            for(int i = 0; i < 4; i++){
                if(buffer.indexes[i] > 0){
                    if(buffer.indexes[i] > capacity) buffer.indexes[i] = capacity;
                    long l = buffer.buffers[i][0];
                    float time = BufferItem.time(l);

                    if(Time.time >= time + speed / timeScale || Time.time < time){

                        Item item = content.item(BufferItem.item(l));
                        Building dest = nearby(i);

                        //skip blocks that don't want the item, keep waiting until they do
                        if(item == null || dest == null || !dest.acceptItem(this, item) || dest.team != team) continue;

                        dest.handleItem(this, item);
                        System.arraycopy(buffer.buffers[i], 1, buffer.buffers[i], 0, buffer.indexes[i] - 1);
                        buffer.indexes[i]--;
                    }
                }
            }
        }

        @Override
        public void handleItem(Building source, Item item){
            int relative = source.relativeTo(tile);
            buffer.accept(relative, item);
        }

        @Override
        public boolean acceptItem(Building source, Item item){
            int relative = source.relativeTo(tile);

            if(relative == -1 || !buffer.accepts(relative)) return false;
            Building to = nearby(relative);
            return to != null && to.team == team;
        }

        @Override
        public byte version(){
            return 1;
        }

        @Override
        public void write(Writes write){
            super.write(write);
            buffer.write(write);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            buffer.read(read, revision == 0);
        }
    }
}
