package momiji;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.*;
import arc.input.KeyCode;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;

import static mindustry.Vars.*;

import java.nio.*;

import arc.util.Buffers;

/**
 * 贴图集诊断工具.
 * <p>
 * 提供按键绑定和客户端指令用于查看 TextureAtlas 统计信息,
 * 以及将整张贴图集或单个贴图导出为 PNG 文件,
 * 帮助排查因贴图过多/过大导致的性能问题.
 *
 * <h3>用法</h3>
 * 在模组主类的 {@code init()} 中注册按键绑定:
 * <pre>
 * {@code
 * @Override
 * public void init(){
 *     AtlasDebug.initKeyBinds();
 * }
 * }
 * </pre>
 *
 * <h3>按键</h3>
 * <ul>
 *   <li><b>F6</b> - 打开贴图集查看器 (游戏内浏览)</li>
 *   <li><b>F7</b> - 导出所有贴图页到 D:/AtlasDebug</li>
 *   <li><b>Shift + F7</b> - 导出所有单个小贴图到 D:/AtlasDebug/regions (较慢)</li>
 *   <li><b>F8</b> - 在日志中输出贴图集统计</li>
 * </ul>
 *
 * <h3>客户端指令 (纯原版环境可用)</h3>
 * 在模组主类中注册:
 * <pre>
 * {@code
 * @Override
 * public void registerClientCommands(CommandHandler handler){
 *     AtlasDebug.registerCommands(handler);
 * }
 * }
 * </pre>
 * <ul>
 *   <li>{@code /atlas} - 查看完整贴图集统计</li>
 *   <li>{@code /atlas <name>} - 查看指定贴图所在的页</li>
 *   <li>{@code /atlasexport} - 导出所有贴图页</li>
 *   <li>{@code /atlasexport <name>} - 导出单个指定贴图</li>
 * </ul>
 *
 * @author momiji
 * @since 2026-07-08
 */
public class AtlasDebug{

    private static final String defaultExportDir = "D:/AtlasDebug";
    private static boolean bindsInitialized = false;

    private AtlasDebug(){}

    /**
     * 初始化按键绑定. 只需调用一次, 建议在模组主类的 init() 中调用.
     * <p>
     * 按键: F6 = 查看器, F7 = 导出整页, Shift+F7 = 导出所有单图, F8 = 输出统计
     */
    public static void initKeyBinds(){
        if(bindsInitialized) return;
        bindsInitialized = true;

        Log.info("[AtlasDebug] Keybinds initialized: F6=viewer, F7=export pages, Shift+F7=export regions, F8=stats");

        Events.run(Trigger.update, () -> {
            if(Core.scene == null) return;

            if(Core.input.keyTap(KeyCode.f6)){
                if(!Core.scene.hasField() && !Core.scene.hasDialog()){
                    new AtlasViewDialog().show();
                }
            }

            if(Core.scene.hasField() || Core.scene.hasDialog()) return;

            if(Core.input.keyTap(KeyCode.f7)){
                Log.info("[AtlasDebug] F7 pressed, exporting pages...");
                if(Core.input.shift()){
                    exportAllRegions(defaultExportDir + "/regions", null);
                }else{
                    exportAllPages(defaultExportDir, null);
                }
            }

            if(Core.input.keyTap(KeyCode.f8)){
                String stats = buildAtlasStats();
                Log.info(stats);
                if(player != null) player.sendMessage(stats);
            }
        });
    }

    /**
     * 向客户端命令处理器注册 atlas 相关指令.
     * 纯原版环境可用, 如安装了 MindustryX 等可能会拦截 / 前缀指令的模组, 请改用 {@link #initKeyBinds()}.
     * @param handler 客户端 CommandHandler
     */
    public static void registerCommands(CommandHandler handler){
        handler.<Player>register("atlas", "[name]", "Dump texture atlas stats, or look up a region's page.", (args, player) -> {
            if(args.length == 0){
                String result = buildAtlasStats();
                Log.info(result);
                if(player != null) player.sendMessage(result);
            }else{
                String result = buildRegionInfo(args[0]);
                Log.info(result);
                if(player != null) player.sendMessage(result);
            }
        });

        handler.<Player>register("atlasexport", "[input]", "Export all atlas pages, or a single region, as PNG files.", (args, player) -> {
            if(args.length == 0){
                exportAllPages(defaultExportDir, player);
            }else{
                TextureAtlas atlas = Core.atlas;
                if(atlas == null){
                    send(player, "[scarlet]TextureAtlas not loaded.[]");
                    return;
                }

                AtlasRegion region = atlas.find(args[0]);
                if(region != null && atlas.isFound(region) && region.texture != null){
                    exportRegion(args[0], defaultExportDir, player);
                }else{
                    exportAllPages(args[0], player);
                }
            }
        });

        handler.<Player>register("atlasregionsexport", "[dir]", "Export every single atlas region as a separate PNG. SLOW.", (args, player) -> {
            String dir = args.length > 0 ? args[0] : defaultExportDir + "/regions";
            exportAllRegions(dir, player);
        });
    }

    // ================================================================
    // Stats
    // ================================================================

    /** 构建整个 TextureAtlas 的统计信息字符串. */
    public static String buildAtlasStats(){
        TextureAtlas atlas = Core.atlas;
        if(atlas == null){
            return "[scarlet]TextureAtlas not loaded.[]";
        }

        Seq<AtlasRegion> regions = atlas.getRegions();
        ObjectMap<Texture, Seq<AtlasRegion>> byTexture = groupByTexture(regions);

        StringBuilder sb = new StringBuilder();
        sb.append("[accent]=== TextureAtlas Stats ===[]\n");
        sb.append("Total regions: [lightgray]").append(regions.size).append("[]\n");
        sb.append("Total pages: [lightgray]").append(byTexture.size).append("[]\n");

        long totalBytes = 0;
        int pageIndex = 0;

        for(var entry : byTexture.entries()){
            Texture tex = entry.key;
            Seq<AtlasRegion> texRegions = entry.value;

            int w = tex.width;
            int h = tex.height;
            long bytes = (long)w * h * 4;
            totalBytes += bytes;

            sb.append("--- Page [orange]").append(pageIndex).append("[] ---\n");
            sb.append("  Size: [lightgray]").append(w).append("x").append(h).append("[]\n");
            sb.append("  Regions: [lightgray]").append(texRegions.size).append("[]\n");
            sb.append("  Est. VRAM: [lightgray]").append(formatBytes(bytes)).append("[]\n");

            pageIndex++;
        }

        sb.append("[accent]==========================[]\n");
        sb.append("Total est. VRAM: [yellow]").append(formatBytes(totalBytes)).append("[]\n");
        sb.append("[gray]Tip: more pages = more texture switches = lower FPS.[]");

        return sb.toString();
    }

    /** 构建指定贴图区域的信息字符串. */
    public static String buildRegionInfo(String name){
        TextureAtlas atlas = Core.atlas;
        if(atlas == null){
            return "[scarlet]TextureAtlas not loaded.[]";
        }

        AtlasRegion region = atlas.find(name);
        if(region == null || !atlas.isFound(region)){
            return "[scarlet]Region not found: " + name + "[]";
        }

        Texture tex = region.texture;
        int w = tex != null ? tex.width : 0;
        int h = tex != null ? tex.height : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("[accent]Region: ").append(name).append("[]\n");
        sb.append("  Position: [lightgray](").append(region.getX()).append(", ").append(region.getY()).append(")[]\n");
        sb.append("  Size: [lightgray]").append(region.width).append("x").append(region.height).append("[]\n");
        sb.append("  Page size: [lightgray]").append(w).append("x").append(h).append("[]");
        if(tex != null){
            long bytes = (long)w * h * 4;
            sb.append("\n  Page est. VRAM: [lightgray]").append(formatBytes(bytes)).append("[]");
        }

        return sb.toString();
    }

    // ================================================================
    // Export - whole pages
    // ================================================================

    /**
     * 将所有贴图集页面导出为 PNG 文件.
     * @param dirPath 输出目录路径 (绝对或相对)
     * @param player  接收消息的玩家, 可为 null
     */
    public static void exportAllPages(String dirPath, Player player){
        TextureAtlas atlas = Core.atlas;
        if(atlas == null){
            send(player, "[scarlet]TextureAtlas not loaded.[]");
            return;
        }

        Fi dir = Fi.get(dirPath);
        dir.mkdirs();

        Seq<AtlasRegion> regions = atlas.getRegions();
        ObjectMap<Texture, Seq<AtlasRegion>> byTexture = groupByTexture(regions);

        int pageIndex = 0;
        int total = byTexture.size;

        for(var entry : byTexture.entries()){
            Texture tex = entry.key;
            Pixmap pixmap = textureToPixmap(tex);

            String fileName = "page_" + pageIndex + "_" + tex.width + "x" + tex.height + ".png";
            Fi outFile = dir.child(fileName);
            PixmapIO.writePng(outFile, pixmap);

            pixmap.dispose();
            pageIndex++;
        }

        String msg = "[accent]Exported [yellow]" + total + "[accent] page(s) to [lightgray]" + dir.absolutePath() + "[]";
        Log.info(msg);
        send(player, msg);
    }

    // ================================================================
    // Export - single region
    // ================================================================

    /**
     * 导出单个贴图区域为 PNG 文件.
     * @param name    区域名称
     * @param dirPath 输出目录路径
     * @param player  接收消息的玩家, 可为 null
     */
    public static void exportRegion(String name, String dirPath, Player player){
        TextureAtlas atlas = Core.atlas;
        if(atlas == null){
            send(player, "[scarlet]TextureAtlas not loaded.[]");
            return;
        }

        AtlasRegion region = atlas.find(name);
        if(region == null || !atlas.isFound(region) || region.texture == null){
            send(player, "[scarlet]Region not found: " + name + "[]");
            return;
        }

        Fi dir = Fi.get(dirPath);
        dir.mkdirs();

        Pixmap full = textureToPixmap(region.texture);
        Pixmap crop = new Pixmap(region.width, region.height);
        crop.draw(full, 0, 0, region.getX(), region.getY(), region.width, region.height);

        String safeName = name.replace('/', '_').replace('\\', '_');
        Fi outFile = dir.child(safeName + ".png");
        PixmapIO.writePng(outFile, crop);

        full.dispose();
        crop.dispose();

        String msg = "[accent]Exported region [yellow]" + name + "[accent] to [lightgray]" + outFile.absolutePath() + "[]";
        Log.info(msg);
        send(player, msg);
    }

    // ================================================================
    // Export - all regions (slow)
    // ================================================================

    /**
     * 导出所有单个贴图区域为独立的 PNG 文件.
     * 注意: 区域数量可能很多 (数千), 此操作比较慢.
     * @param dirPath 输出目录路径
     * @param player  接收消息的玩家, 可为 null
     */
    public static void exportAllRegions(String dirPath, Player player){
        TextureAtlas atlas = Core.atlas;
        if(atlas == null){
            send(player, "[scarlet]TextureAtlas not loaded.[]");
            return;
        }

        Fi dir = Fi.get(dirPath);
        dir.mkdirs();

        Seq<AtlasRegion> regions = atlas.getRegions();

        ObjectMap<Texture, Pixmap> pixmapCache = new ObjectMap<>();
        int count = 0;

        try{
            for(AtlasRegion region : regions){
                if(region.texture == null) continue;

                Pixmap full = pixmapCache.get(region.texture);
                if(full == null){
                    full = textureToPixmap(region.texture);
                    pixmapCache.put(region.texture, full);
                }

                Pixmap crop = new Pixmap(region.width, region.height);
                crop.draw(full, 0, 0, region.getX(), region.getY(), region.width, region.height);

                String safeName = (region.name != null ? region.name : "region_" + count).replace('/', '_').replace('\\', '_');
                Fi outFile = dir.child(safeName + ".png");
                PixmapIO.writePng(outFile, crop);

                crop.dispose();
                count++;

                if(count % 100 == 0){
                    Log.info("Exported @ / @ regions...", count, regions.size);
                }
            }
        }finally{
            for(Pixmap p : pixmapCache.values()){
                p.dispose();
            }
        }

        String msg = "[accent]Exported [yellow]" + count + "[accent] regions to [lightgray]" + dir.absolutePath() + "[]";
        Log.info(msg);
        send(player, msg);
    }

    // ================================================================
    // Helpers
    // ================================================================

    /** 将所有区域按所属纹理分组. */
    private static ObjectMap<Texture, Seq<AtlasRegion>> groupByTexture(Seq<AtlasRegion> regions){
        ObjectMap<Texture, Seq<AtlasRegion>> byTexture = new ObjectMap<>();
        for(AtlasRegion region : regions){
            if(region.texture == null) continue;
            byTexture.get(region.texture, Seq::new).add(region);
        }
        return byTexture;
    }

    /**
     * 从 GPU 读取整张纹理, 转为 CPU 端 Pixmap.
     * 通过创建临时 FBO 将纹理附加为颜色附件, 再用 glReadPixels 读取.
     * 注意: 这是一个相对昂贵的操作.
     */
    private static Pixmap textureToPixmap(Texture texture){
        int fbo = Gl.genFramebuffer();

        IntBuffer prevFbo = Buffers.newIntBuffer(1);
        Gl.getIntegerv(Gl.framebufferBinding, prevFbo);

        Gl.bindFramebuffer(Gl.framebuffer, fbo);
        Gl.framebufferTexture2D(Gl.framebuffer, Gl.colorAttachment0, Gl.texture2d, texture.getTextureObjectHandle(), 0);

        Gl.pixelStorei(Gl.packAlignment, 1);

        Pixmap pixmap = new Pixmap(texture.width, texture.height);
        ByteBuffer pixels = pixmap.pixels;
        Gl.readPixels(0, 0, texture.width, texture.height, Gl.rgba, Gl.unsignedByte, pixels);

        Gl.bindFramebuffer(Gl.framebuffer, prevFbo.get(0));
        Gl.deleteFramebuffer(fbo);

        flipPixmapY(pixmap);

        return pixmap;
    }

    /** 就地垂直翻转 Pixmap. */
    private static void flipPixmapY(Pixmap pixmap){
        int w = pixmap.width;
        int h = pixmap.height;
        byte[] topRow = new byte[w * 4];
        byte[] botRow = new byte[w * 4];
        ByteBuffer buf = pixmap.pixels;

        for(int y = 0; y < h / 2; y++){
            int topPos = y * w * 4;
            int botPos = (h - 1 - y) * w * 4;

            buf.position(topPos);
            buf.get(topRow);

            buf.position(botPos);
            buf.get(botRow);

            buf.position(topPos);
            buf.put(botRow);

            buf.position(botPos);
            buf.put(topRow);
        }

        buf.position(0);
    }

    private static void send(Player player, String msg){
        if(player != null) player.sendMessage(msg);
    }

    /** 将字节数格式化为人类可读的字符串. */
    private static String formatBytes(long bytes){
        if(bytes < 1024) return bytes + " B";
        if(bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024f);
        if(bytes < 1024L * 1024 * 1024) return String.format("%.2f MB", bytes / (1024f * 1024f));
        return String.format("%.2f GB", bytes / (1024f * 1024f * 1024f));
    }
}
