package momiji.type;

import arc.struct.EnumSet;
import arc.struct.IntSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Nullable;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.blocks.liquid.LiquidRouter;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.meta.BlockFlag;
import mindustry.world.meta.BlockGroup;
import mindustry.world.meta.Env;
import mindustry.world.modules.ItemModule;

import static mindustry.Vars.content;
import static mindustry.Vars.world;

/**
 * 相邻同类建筑之间共享物品库存的基类, 可附加路由器式液体输出.<p>
 * 物品: 相邻同类建筑自动连接成共享组, 容量叠加, 拆除时按比例分配.<br>
 * 液体: 独立于组, 每个建筑单独存储, 由 {@link #outputLiquids} 控制输出行为.
 *
 * @author Momiji142857
 * @see StorageBlock
 * @see LiquidRouter
 * @since 2026-07-14
 */
public class LinkedBlock extends Block{
    /** 启用液体输出, 需同时设置 {@link #outputsLiquid} 为 true */
    public boolean outputLiquids = true;
    /** 仅向同类建筑输出液体 (outputLiquids 为 true 时生效) */
    public boolean linkLiquids = true;

    public LinkedBlock(String name){
        super(name);
        hasItems = true;
        hasLiquids = true;
        solid = true;
        update = true;
        sync = true;
        destructible = true;
        separateItemCapacity = true;
        group = BlockGroup.transportation;
        flags = EnumSet.of(BlockFlag.storage);
        allowResupply = true;
        envEnabled = Env.any;
    }

    @Override
    public boolean outputsItems(){
        return false;
    }

    public class LinkedBuild extends Building{
        /** 共享组组长, 所有组员的 items 都指向组长的模块, 为null时自身为组长 */
        public @Nullable LinkedBuild groupLeader;
        /** 组成员总数, 仅组长的此字段有效 */
        public int groupSize = 1;

        @Override
        public boolean acceptItem(Building source, Item item){
            return items.get(item) < getMaximumAccepted(item);
        }

        @Override
        public int getMaximumAccepted(Item item){
            return (groupLeader == null ? groupSize : groupLeader.groupSize) * itemCapacity;
        }

        @Override
        public boolean canDump(Building to, Item item){
            //不向同组成员输出物品, 共享同一个物品池, 输出无效
            return to.block != block || !(to instanceof LinkedBuild lb) || lb.team != team || lb.leader() != leader();
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid){
            return outputLiquids && (liquids.current() == liquid || liquids.currentAmount() < 0.2f);
        }

        @Override
        public boolean canDumpLiquid(Building to, Liquid liquid){
            return outputLiquids && (!linkLiquids || to.block == block);
        }

        @Override
        public void onProximityUpdate(){
            super.onProximityUpdate();

            LinkedBuild selfLeader = leader();
            IntSet groupSet = new IntSet();
            Seq<LinkedBuild> groups = new Seq<>();

            for(int i = 0; i < proximity.size; i++){
                Building other = proximity.get(i);
                if(!(other.block == block && other instanceof LinkedBuild lb && lb.team == team)) continue;

                LinkedBuild otherLeader = lb.leader();
                if(otherLeader != selfLeader && groupSet.add(otherLeader.tile.pos())){
                    groups.add(otherLeader);
                }
            }

            if(groups.size > 0){
                groups.add(selfLeader);
                mergeGroups(groups);
            }
        }

        /** 合并多个组, 选 tile.pos() 最小的组长作为新组长 */
        void mergeGroups(Seq<LinkedBuild> leaders){
            if(leaders.size <= 1) return;

            LinkedBuild newLeader = leaders.get(0);
            for(int i = 1; i < leaders.size; i++){
                if(leaders.get(i).tile.pos() < newLeader.tile.pos()){
                    newLeader = leaders.get(i);
                }
            }

            ItemModule mergedItems = new ItemModule();
            int totalSize = 0;

            for(int i = 0; i < leaders.size; i++){
                LinkedBuild leader = leaders.get(i);
                mergedItems.add(leader.items);
                totalSize += leader.groupSize;
            }

            newLeader.items = mergedItems;
            newLeader.groupLeader = null;
            newLeader.groupSize = totalSize;

            for(int i = 0; i < leaders.size; i++){
                LinkedBuild leader = leaders.get(i);
                if(leader == newLeader) continue;

                Seq<LinkedBuild> members = collectGroup(leader, leader, new IntSet());
                for(int j = 0; j < members.size; j++){
                    LinkedBuild m = members.get(j);
                    m.items = mergedItems;
                    m.groupLeader = newLeader;
                    m.groupSize = 1;
                }
            }

            newLeader.clampResources();
        }

        /** 将一个建筑加入本组 */
        public void addToGroup(LinkedBuild other){
            LinkedBuild actualLeader = leader();
            if(other.leader() == actualLeader) return;

            if(other.groupLeader != null){
                other.removeFromGroup();
            }

            if(actualLeader.items != other.items){
                actualLeader.items.add(other.items);
            }

            other.items = actualLeader.items;
            other.groupLeader = actualLeader;

            actualLeader.groupSize++;
            other.groupSize = 1;
        }

        /** 从组中移除, 处理组分裂 */
        public void removeFromGroup(){
            boolean isLeader = groupLeader == null;
            LinkedBuild oldLeader = isLeader ? this : groupLeader;
            ItemModule oldItems = items;

            Seq<LinkedBuild> neighbors = new Seq<>();
            for(int i = 0; i < proximity.size; i++){
                Building other = proximity.get(i);
                if(other.block == block && other instanceof LinkedBuild lb && lb.team == team && lb.leader() == oldLeader){
                    neighbors.add(lb);
                }
            }

            if(!isLeader){
                groupLeader = null;
                items = new ItemModule();
                groupSize = 1;
                oldLeader.groupSize--;
            }else{
                items = new ItemModule();
                groupLeader = null;
                groupSize = 1;
            }

            splitCheck(oldLeader, oldItems, neighbors);
        }

        /** 检查组分裂, 按各子组容量比例分配资源 */
        void splitCheck(LinkedBuild oldLeader, ItemModule oldItems, Seq<LinkedBuild> neighbors){
            if(neighbors.size == 0) return;

            Seq<Seq<LinkedBuild>> subGroups = new Seq<>();
            IntSet visited = new IntSet();

            for(int i = 0; i < neighbors.size; i++){
                LinkedBuild nb = neighbors.get(i);
                if(visited.contains(nb.tile.pos())) continue;
                subGroups.add(collectGroup(nb, oldLeader, visited));
            }

            if(subGroups.size == 1){
                setupNewLeader(subGroups.get(0), oldItems);
                return;
            }

            int totalSize = 0;
            for(int i = 0; i < subGroups.size; i++){
                totalSize += subGroups.get(i).size;
            }

            Seq<ItemModule> subItemsList = new Seq<>();
            for(int i = 0; i < subGroups.size; i++){
                int size = subGroups.get(i).size;
                float ratio = totalSize > 0 ? (float) size / totalSize : 0f;

                ItemModule subItems = new ItemModule();
                for(Item item : content.items()){
                    subItems.set(item, (int) (oldItems.get(item) * ratio));
                }
                subItemsList.add(subItems);
            }

            //补足整数分配的余数, 确保物品总数不变
            for(Item item : content.items()){
                int original = oldItems.get(item);
                int assigned = 0;
                for(int i = 0; i < subItemsList.size; i++){
                    assigned += subItemsList.get(i).get(item);
                }
                int remainder = original - assigned;
                for(int i = 0; i < remainder && i < subItemsList.size; i++){
                    subItemsList.get(i).add(item, 1);
                }
            }

            for(int i = 0; i < subGroups.size; i++){
                setupNewLeader(subGroups.get(i), subItemsList.get(i));
            }
        }

        /** 从 start 出发 BFS 收集同组成员 */
        Seq<LinkedBuild> collectGroup(LinkedBuild start, LinkedBuild targetLeader, IntSet visited){
            Seq<LinkedBuild> result = new Seq<>();
            Queue<LinkedBuild> queue = new Queue<>();

            queue.add(start);
            visited.add(start.tile.pos());
            result.add(start);

            while(queue.size > 0){
                LinkedBuild current = queue.removeFirst();
                for(int i = 0; i < current.proximity.size; i++){
                    Building other = current.proximity.get(i);
                    if(!(other.block == block && other instanceof LinkedBuild lb && lb.team == team)) continue;

                    if(lb.leader() != targetLeader || !visited.add(lb.tile.pos())) continue;

                    result.add(lb);
                    queue.add(lb);
                }
            }
            return result;
        }

        /** 为一组建筑选新组长并同步 items */
        void setupNewLeader(Seq<LinkedBuild> members, ItemModule newItems){
            LinkedBuild newLeader = members.get(0);
            for(int i = 1; i < members.size; i++){
                if(members.get(i).tile.pos() < newLeader.tile.pos()){
                    newLeader = members.get(i);
                }
            }

            newLeader.items = newItems;
            newLeader.groupLeader = null;
            newLeader.groupSize = members.size;

            for(int i = 0; i < members.size; i++){
                LinkedBuild m = members.get(i);
                if(m == newLeader) continue;

                m.items = newItems;
                m.groupLeader = newLeader;
                m.groupSize = 1;
            }

            newLeader.clampResources();
        }

        /** 将物品裁剪到容量上限内 */
        void clampResources(){
            if(world.isGenerating()) return;

            int cap = groupSize * itemCapacity;
            for(Item item : content.items()){
                if(items.get(item) > cap){
                    items.set(item, cap);
                }
            }
        }

        /** 获取组长, 自身为组长时返回 this */
        LinkedBuild leader(){
            return groupLeader == null ? this : groupLeader;
        }

        /** 获取组内所有成员 */
        public Seq<LinkedBuild> getGroupMembers(){
            LinkedBuild l = leader();
            return collectGroup(l, l, new IntSet());
        }

        @Override
        public void onRemoved(){
            if(groupLeader != null || groupSize > 1){
                removeFromGroup();
            }
            super.onRemoved();
        }

        @Override
        public void updateTile(){
            if(groupLeader != null && groupLeader.items != items){
                items = groupLeader.items;
            }

            if(outputLiquids){
                dumpLiquid(liquids.current());
            }
        }

        @Override
        public boolean canPickup(){
            return groupLeader == null;
        }

        @Override
        public void pickedUp(){
            if(groupLeader != null){
                removeFromGroup();
            }
        }
    }
}
