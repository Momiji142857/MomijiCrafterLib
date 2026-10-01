package momiji;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.*;
import arc.input.KeyCode;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

/**
 * 贴图集查看对话框.
 * <p>
 * 在游戏内直接显示 TextureAtlas 的所有纹理页, 可点击切换,
 * 鼠标悬停查看区域信息, 帮助直观了解贴图集的使用情况.
 *
 * @author momiji
 * @since 2026-07-08
 */
public class AtlasViewDialog extends BaseDialog{

    private final Seq<Texture> pages;
    private Texture currentPage;
    private final TextureRegion pageRegion;
    private int pageIndex;
    private final Label pageLabel;

    private float zoom = 1f;
    private float offsetX, offsetY;
    private String hoverInfo = "";

    public AtlasViewDialog(){
        super("Texture Atlas");

        pages = collectPages();
        if(pages.size > 0){
            currentPage = pages.first();
            pageRegion = new TextureRegion(currentPage);
        }else{
            pageRegion = new TextureRegion();
        }

        addCloseButton();

        buttons.button("Prev", this::prevPage).size(120f, 64f);
        pageLabel = buttons.add("Page 1 / 0").pad(12f).get();
        pageLabel.setAlignment(0);
        buttons.button("Next", this::nextPage).size(120f, 64f);

        buildContent();
        updatePageLabel();
    }

    private void buildContent(){
        Table infoTable = new Table(t -> {
            t.top().left();
            t.table(Styles.black6, info -> {
                info.left();
                info.label(() -> hoverInfo).left().growX().wrap();
            }).growX().pad(8f);
        });
        infoTable.touchable = Touchable.disabled;

        Element view = new Element(){
            {
                setFillParent(true);
                touchable = Touchable.enabled;
                Element self = this;

                addListener(new InputListener(){
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                        Core.scene.setScrollFocus(self);
                        return true;
                    }

                    @Override
                    public void touchDragged(InputEvent event, float x, float y, int pointer){
                        offsetX += Core.input.deltaX() * 1.5f;
                        offsetY += Core.input.deltaY() * 1.5f;
                    }

                    @Override
                    public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY){
                        float oldZoom = zoom;
                        zoom = Math.max(0.1f, Math.min(8f, zoom * (1f - amountY * 0.1f)));

                        float cx = getWidth() / 2f + offsetX;
                        float cy = getHeight() / 2f + offsetY;

                        offsetX += (x - cx) * (1f - zoom / oldZoom);
                        offsetY += (y - cy) * (1f - zoom / oldZoom);

                        return true;
                    }
                });
            }

            @Override
            public void draw(){
                if(currentPage == null) return;

                float w = currentPage.width * zoom;
                float h = currentPage.height * zoom;
                float x = (getWidth() - w) / 2f + offsetX;
                float y = (getHeight() - h) / 2f + offsetY;

                Draw.color(Color.black);
                Fill.rect(getWidth()/2f, getHeight()/2f, getWidth(), getHeight());
                Draw.color();

                Blending blending = Draw.getBlend();
                Draw.blend(Blending.disabled);
                Draw.rect(pageRegion, x + w/2f, y + h/2f, w, h);
                Draw.blend(blending);

                updateHoverInfo(x, y, w, h);
            }
        };

        cont.stack(view, infoTable).grow();

        shown(() -> Core.scene.setScrollFocus(view));
    }

    private void updateHoverInfo(float drawX, float drawY, float drawW, float drawH){
        float mx = Core.input.mouseX() - x;
        float my = Core.input.mouseY() - y;

        if(mx < drawX || mx > drawX + drawW || my < drawY || my > drawY + drawH){
            hoverInfo = "Zoom: " + String.format("%.1f", zoom * 100f) + "%";
            return;
        }

        float u = (mx - drawX) / drawW;
        float v = (my - drawY) / drawH;
        int px = (int)(u * currentPage.width);
        int py = (int)((1f - v) * currentPage.height);

        String regionName = findRegionAt(px, py);

        StringBuilder sb = new StringBuilder();
        sb.append("Zoom: ").append(String.format("%.1f", zoom * 100f)).append("%\n");
        sb.append("Page: ").append(pageIndex + 1).append(" / ").append(pages.size);
        sb.append(" (").append(currentPage.width).append("x").append(currentPage.height).append(")\n");
        sb.append("Pixel: (").append(px).append(", ").append(py).append(")\n");
        if(regionName != null){
            sb.append("Region: ").append(regionName);
        }
        hoverInfo = sb.toString();
    }

    private String findRegionAt(int px, int py){
        TextureAtlas atlas = Core.atlas;
        if(atlas == null) return null;

        for(AtlasRegion region : atlas.getRegions()){
            if(region.texture != currentPage) continue;
            if(px >= region.getX() && px < region.getX() + region.width
                && py >= region.getY() && py < region.getY() + region.height){
                return region.name;
            }
        }
        return null;
    }

    private void prevPage(){
        if(pages.size == 0) return;
        pageIndex = (pageIndex - 1 + pages.size) % pages.size;
        currentPage = pages.get(pageIndex);
        pageRegion.set(currentPage, 0, 0, currentPage.width, currentPage.height);
        zoom = 1f;
        offsetX = 0f;
        offsetY = 0f;
        updatePageLabel();
    }

    private void nextPage(){
        if(pages.size == 0) return;
        pageIndex = (pageIndex + 1) % pages.size;
        currentPage = pages.get(pageIndex);
        pageRegion.set(currentPage, 0, 0, currentPage.width, currentPage.height);
        zoom = 1f;
        offsetX = 0f;
        offsetY = 0f;
        updatePageLabel();
    }

    private void updatePageLabel(){
        pageLabel.setText("Page " + (pageIndex + 1) + " / " + pages.size);
    }

    private static Seq<Texture> collectPages(){
        TextureAtlas atlas = Core.atlas;
        if(atlas == null) return new Seq<>();

        ObjectSet<Texture> set = new ObjectSet<>();
        for(AtlasRegion region : atlas.getRegions()){
            if(region.texture != null){
                set.add(region.texture);
            }
        }
        return set.toSeq();
    }
}
