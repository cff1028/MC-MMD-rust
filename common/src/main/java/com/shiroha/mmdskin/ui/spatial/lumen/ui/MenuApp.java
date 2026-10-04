package com.shiroha.mmdskin.ui.spatial.lumen.ui;

import com.shiroha.mmdskin.ui.spatial.lumen.input.InputState;
import com.shiroha.mmdskin.ui.spatial.lumen.input.ScrollGesture;
import com.shiroha.mmdskin.ui.spatial.lumen.model.*;
import com.shiroha.mmdskin.ui.spatial.lumen.render.Canvas;
import java.util.*;
import java.util.function.Consumer;
import static org.lwjgl.glfw.GLFW.*;

/** Portable menu composition; desktop window, GL context, and game bindings stay outside this class. */
public final class MenuApp implements AutoCloseable {
    private static final int INK=0xFFF3F5FA, BLUE=0xFFA9C9F6,
        PALE=0x203F6B9C, LINE=0x1CFFFFFF, WHITE=0xFFF3F5FA, GREEN=0xFFA3D8BB,
        CARD=0x09FFFFFF, CONTROL=0x14FFFFFF, ON_ACCENT=0xFF17263C;
    private int MUTED=0xFFADB5C4;
    private static final String[] PAGES={"home","worlds","avatars","motions","account","settings"};
    private static final String[] NAMES={"概览","世界","虚拟形象","动作与舞台","账户","设置"};
    private static final String[] ICONS={"home","world","avatar","stage","account","settings"};
    private final MenuBackend backend;
    private final ScrollGesture scrolling = new ScrollGesture();
    private final Map<String,Float> animations=new HashMap<>();
    private final List<HitTarget> targets=new ArrayList<>();
    private Canvas c; private InputState in;
    private float dt,time,pageReveal=1,navPosition=0,scroll=0,playhead=0;
    private String page="home",lastPage="home",pressedId="",dragId="",keyActivation="",focusId="";
    private boolean quick=false,hidden=false,allowInput=true,reducedMotion=false,help=false,searchFocused=false;
    private boolean surfaceOnly=false, scrollUpdated;
    private boolean highContrast=false;
    private int keyboardMove=0,worldFilter=0,avatarTab=0,motionTab=0,selectedAction=-1,selectedPack=0;
    private boolean playing=false,mirror=false,hud=true,favoriteOnly=false,cinematic=false,ready=false;
    private String query="",settingsCategory="Minecraft",settingsSection="",selectedAvatar="",equippedAvatar="",activeWorld="",activeWorldId="";
    private Modal modal; private String toast="",serverName="",serverAddress=""; private float toastTime=0;
    private String editField="", textValue="", lastStatus="";
    private int sectionPage;
    // Distance is a transaction: the live panel remains usable until the trigger is released.
    private Double panelDistancePreview;

    public MenuApp(MenuBackend backend) {
        this.backend=Objects.requireNonNull(backend);
        if(!backend.avatars().isEmpty()){String saved=backend.modelContext();selectedAvatar=backend.avatars().stream().anyMatch(a->a.id().equals(saved))?saved:backend.avatars().get(0).id();equippedAvatar=selectedAvatar;backend.selectModelContext(selectedAvatar);}
    }
    public String screenName(){return hidden?"closed":quick?"quick":page;}
    public double panelDistance(){return backend.number("ui.panelDistance");}
    public Double panelDistancePreview(){return panelDistancePreview;}
    public double panelScale(){return backend.number("ui.panelScale");}
    public SurfaceSpec surfaceSpec(){return quick?SurfaceSpec.wrist():SurfaceSpec.detail();}
    public void navigateForTest(String name){
        if(name.equals("quick")){quick=true;hidden=false;modal=null;return;}
        if(name.equals("closed")){hidden=true;quick=false;return;}
        switch(name){
            case "settings-mmdskin" -> {navigate("settings");selectCategory("MMD Skin");}
            case "settings-vivecraft" -> {navigate("settings");selectCategory("Vivecraft");}
            case "settings-ui" -> {navigate("settings");selectCategory("界面");}
            case "avatar-materials" -> {navigate("avatars");avatarTab=2;}
            case "avatar-calibration" -> {navigate("avatars");avatarTab=3;}
            case "stage" -> {navigate("motions");motionTab=2;}
            case "expressions" -> {navigate("motions");motionTab=1;}
            default -> navigate(name);
        }pageReveal=1;
    }
    public void render(Canvas canvas,InputState input,float delta) {renderInternal(canvas,input,delta,false);}
    /** Draw just transparent UI, without the desktop studio environment. Call once per logical frame. */
    public void renderSurface(Canvas canvas,InputState input,float delta) {renderInternal(canvas,input,delta,true);}
    private void renderInternal(Canvas canvas,InputState input,float delta,boolean surfaceOnly) {
        this.surfaceOnly=surfaceOnly;
        c=canvas;in=input;dt=Math.min(delta,.05f);time+=dt;targets.clear();scrollUpdated=false;
        equippedAvatar=backend.equippedAvatar();activeWorld=backend.currentWorld();activeWorldId=backend.currentWorldId();mirror=!backend.mirrorMode().equals("OFF");
        if(!Objects.equals(lastStatus,backend.status())){lastStatus=backend.status();if(!lastStatus.isBlank())notifyUser(lastStatus);}
        reducedMotion=backend.bool("ui.reducedMotion");
        highContrast=backend.bool("ui.highContrast");MUTED=highContrast?0xFFE4E8F0:0xFFADB5C4;
        pageReveal=reducedMotion?1:Math.min(1,pageReveal+dt*5);
        toastTime=Math.max(0,toastTime-dt);

        allowInput=modal==null&&!help;
        if(!allowInput || quick || hidden){if(panelDistancePreview!=null)cancelPointer();scrolling.cancel();}
        if(!surfaceOnly){Art.background(c,reducedMotion?0:time);chrome();}
        if(hidden) closedView(); else if(quick) quickView(); else detailView();
        if(!scrollUpdated)scrolling.cancel();
        if(modal!=null) renderModal();
        if(help) helpOverlay();
        if(toastTime>0) {
            c.save();c.alpha(Math.min(1,toastTime*3));
            float tx=surfaceOnly?(quick?234:444):380,ty=surfaceOnly?(quick?265:117):28,tw=surfaceOnly&&quick?420:680;
            c.fill(tx,ty,tw,44,12,0xF2202732);c.stroke(tx,ty,tw,44,12,LINE,1);Art.icon(c,"info",tx+14,ty+12,20,BLUE);
            c.clip(tx+43,ty+4,tw-55,36);c.text(toast,tx+43,ty+13,14,INK);c.restore();
        } else if(!surfaceOnly) {
            c.textCenter("TAB  腕部菜单     ·     ESC  返回     ·     F6  空间预览     ·     F1  操作说明",720,877,12,0x90D8DEEB);
        }
        if(keyboardMove!=0&&!targets.isEmpty()) {
            int i=-1;for(int j=0;j<targets.size();j++)if(targets.get(j).id.equals(focusId))i=j;
            focusId=targets.get(Math.floorMod(i+keyboardMove,targets.size())).id;keyboardMove=0;
        }
        if(!focusId.isEmpty()) for(HitTarget hit:targets)if(hit.id.equals(focusId))c.stroke(hit.x-3,hit.y-3,hit.w+6,hit.h+6,12,BLUE,2);
        if(in.released){
            if(panelDistancePreview!=null){backend.set("ui.panelDistance",panelDistancePreview);panelDistancePreview=null;}
            pressedId="";dragId="";backend.flush();
        }
        keyActivation="";
    }
    private void chrome() {
        c.text("L U M E N",64,42,16,INK);c.text("SPATIAL MENU",193,44,11,0xFF8D98AA);
        c.fill(594,29,252,44,14,0x45131823);c.stroke(594,29,252,44,14,LINE,1);
        if(pill("mode.quick","腕部菜单",600,34,118,34,quick)){quick=true;hidden=false;modal=null;searchFocused=false;}
        if(pill("mode.detail","详细菜单",722,34,118,34,!quick&&!hidden))navigate(lastPage);
        c.text("桌面预览",1192,43,13,MUTED);
        if(iconButton("help","info",1296,27,44,CONTROL,INK))help=true;
    }
    /** Only the material is translucent; glyph opacity is never reduced with the surface. */
    private void glass(float x,float y,float w,float h,float radius) {
        c.shadow(x,y,w,h,radius,28,7,0x40000000);
        int opacity=highContrast?0xEF:surfaceOnly?0xB8:0xDE;
        c.gradient(x,y,w,h,radius,(opacity<<24)|0x202631,(opacity<<24)|0x181E27);
        c.stroke(x+.5f,y+.5f,w-1,h-1,radius,highContrast?0x80E0E6EF:0x38DFE7F5,1);
        c.line(x+radius,y+1,x+w-radius,y+1,0x2FE5EDFF,1);
    }
    private void card(float x,float y,float w,float h,boolean selected,float hover) {
        c.fill(x,y,w,h,12,selected?0x244F719E:mix(CARD,0x18FFFFFF,hover));
        c.stroke(x+.5f,y+.5f,w-1,h-1,12,selected?0xB0A9C9F6:highContrast?0x60C9D3E3:mix(0x14FFFFFF,0x50B0C8E6,hover),1);
    }
    private void detailView() {
        glass(180,112,1080,636,24);
        c.save();c.alpha(.80f+.20f*ease(pageReveal));
        switch(page){case "worlds"->worlds();case "avatars"->avatars();case "motions"->motions();case "account"->account();case "settings"->settings();default->home();}
        c.restore();
        c.line(216,702,1224,702,0x12FFFFFF,1);
        c.circle(222,725,3,GREEN);c.text("MMD Skin · VR",236,717,13,MUTED);
        c.text("Minecraft   /   MMD Skin   /   Vivecraft",940,717,13,MUTED);
        if(iconButton("detail.wrist","hand",1276,144,48,0x8C202733,INK)){quick=true;searchFocused=false;}
        if(iconButton("hide","close",1276,204,48,0x8C202733,INK)){hidden=true;quick=false;}
        if(iconButton("detail.help","info",1276,264,48,0x8C202733,INK))help=true;
        if(iconButton("detail.refresh","reset",1276,324,48,0x8C202733,INK))command("resources.refresh","");
        glass(328,776,784,80,22);
        int index=Math.max(0,Arrays.asList(PAGES).indexOf(page));
        navPosition=reducedMotion?index:navPosition+(index-navPosition)*Math.min(1,dt*15);
        c.fill(342+navPosition*126,785,120,61,13,0x13FFFFFF);
        for(int i=0;i<PAGES.length;i++){
            float x=342+i*126;boolean active=page.equals(PAGES[i]);
            boolean clicked=hit("nav."+PAGES[i],x,784,120,64);
            float hv=animate("nav.hover."+i,hover(x,784,120,64)?1:0);
            if(!active)c.fill(x,785,120,61,13,alpha(0x10FFFFFF,hv));
            Art.icon(c,ICONS[i],x+49,792,22,active?BLUE:MUTED);
            c.textCenter(NAMES[i],x+60,824,14,active?INK:MUTED);
            if(active)c.fill(x+47,850,26,2,1,BLUE);
            if(clicked)navigate(PAGES[i]);
        }
    }
    private void pageHeading(String title,String subtitle) {
        c.text(title,216,145,32,INK);c.text(subtitle,216,191,14,MUTED);
    }
    private void home() {
        pageHeading("欢迎回来，"+backend.accountName(),"你的世界与形象，都在这里。");
        c.text("我的空间",1148,161,14,MUTED);
        card(216,244,492,226,false,0);
        imageOrPlaceholder(backend.worldImage(activeWorldId),236,266,136,138,"world",false);
        c.text("当前世界",397,268,13,MUTED);c.text(ellipsize(activeWorld,10),396,298,25,INK);
        c.text(activeWorldKind(),397,342,14,MUTED);
        if(button("home.continue","继续","play",397,392,168,48,BLUE,ON_ACCENT)){
            command("menu.resume", ""); hidden=true;
        }
        c.text("当前世界",269,425,12,MUTED);
        card(732,244,492,226,false,0);
        avatarPortrait(equippedAvatar,752,266,136,138);
        c.text("当前形象",913,268,13,MUTED);c.text(ellipsize(equippedName(),10),912,298,25,INK);
        c.text(equippedKind()+" · 本地形象",913,342,14,MUTED);
        if(button("home.avatar","管理形象","arrow",913,392,196,48,CONTROL,INK))navigate("avatars");
        c.text("虚拟形象",785,425,12,MUTED);
        featureCard("home.world","world","世界","单人存档与多人联机",216,494,320,166,0,()->navigate("worlds"));
        featureCard("home.stage","stage","动作与舞台","动作、表情与联机演出",560,494,320,166,0,()->navigate("motions"));
        featureCard("home.calibration","hand","VR 校准","眼位、手臂与手指追踪",904,494,320,166,0,()->{navigate("avatars");avatarTab=3;});
    }
    private void featureCard(String id,String icon,String title,String desc,float x,float y,float w,float h,int ignored,Runnable action) {
        boolean clicked=hit(id,x,y,w,h);float hv=animate(id,hover(x,y,w,h)?1:0);
        card(x,y,w,h,false,hv);Art.icon(c,icon,x+22,y+23,26,BLUE);
        c.text(title,x+22,y+76,22,INK);c.text(desc,x+22,y+117,14,MUTED);
        Art.icon(c,"arrow",x+w-42,y+81,18,MUTED);if(clicked)action.run();
    }
    private void worlds() {
        pageHeading("世界","选择一个目的地。");searchBox(926,146,298,"搜索世界");
        String[] filters={"全部","单人存档","多人联机","收藏"};
        for(int i=0;i<filters.length;i++)if(pill("world.filter."+i,filters[i],216+i*123,237,113,44,worldFilter==i)){worldFilter=i;resetScroll();}
        if(button("world.add","添加服务器","plus",1032,237,192,44,CONTROL,INK)){
            serverName="";serverAddress="";editField="name";searchFocused=false;
            modal=new Modal("添加服务器","保存到 Minecraft 服务器列表；连接前仍需确认。",null,"添加",this::addServer,"server");
        }
        if(textButton("world.source","存档位置",788,239,116,40))modal=new Modal("当前实例的存档目录","与 Minecraft 原版存档选择页面使用同一目录。",backend.worldsDirectory(),"知道了",()->{},"info");
        if(iconButton("world.refresh","reset",944,237,44,CONTROL,INK))command("resources.refresh","");
        List<WorldEntry> entries=new ArrayList<>(backend.worlds());
        entries=entries.stream().filter(w->worldFilter==0||(worldFilter==1&&w.id().startsWith("local:"))||(worldFilter==2&&w.id().startsWith("server:"))||(worldFilter==3&&backend.favorite(w.id())))
            .filter(w->matches(w.name()+w.subtitle(),query)).toList();
        String loadError=backend.worldsLoadError();
        if(backend.worldsLoading() && worldFilter!=2)c.text("正在同步本机存档…",216,285,12,MUTED);
        else if(!loadError.isBlank() && worldFilter!=2){
            if(textButton("world.error","存档读取失败 · 查看原因",216,277,400,30))modal=new Modal("存档读取失败","可重试刷新，或在原版存档页面检查。",loadError+"\n\n目录："+backend.worldsDirectory(),"知道了",()->{},"info");
        }
        if(entries.isEmpty()){
            boolean loading=backend.worldsLoading() && worldFilter!=2;
            emptyState(loading?"正在读取存档":!loadError.isBlank()&&worldFilter!=2?"存档读取失败":"没有找到世界",loading?"首次读取大型存档列表可能需要片刻。":"查看存档位置，或尝试其他关键词和分类。",552,447);return;
        }
        if(worldFilter==2){serverList(entries);return;}
        float contentH=(float)Math.ceil(entries.size()/3.0)*196;scrollArea(216,306,685,contentH);
        c.save();c.clip(212,302,1016,386);
        for(int i=0;i<entries.size();i++){
            WorldEntry w=entries.get(i);float x=216+i%3*344,y=306+i/3*196-scroll;
            if(y>688||y+176<302)continue;
            boolean clicked=hitClipped("world.open."+w.id(),x,y,320,176,303,688);
            card(x,y,320,176,false,animate("world.card."+w.id(),hover(x,y,320,176)?1:0));
            imageOrPlaceholder(backend.worldImage(w.id()),x+16,y+17,64,64,"world",false);
            c.text(ellipsize(w.name(),11),x+96,y+24,20,INK);c.text(w.kind(),x+96,y+56,13,MUTED);
            MenuBackend.ServerDetails server=backend.serverDetails(w.id());
            c.text(ellipsize(server==null?w.subtitle():server.motd().replace('\n',' '),21),x+18,y+101,13,MUTED);
            if(server!=null)c.text(ellipsize(server.connectionLabel(),9),x+122,y+141,12,server.reachable()?GREEN:MUTED);
            c.text(backend.worldBusy(w.id())?"正在删除…":w.id().equals(activeWorldId)?"回到游戏":"进入世界",x+18,y+140,14,BLUE);Art.icon(c,"arrow",x+89,y+140,17,BLUE);
            if(iconButtonClipped("world.favorite."+w.id(),"heart",x+257,y+120,44,0x00FFFFFF,backend.favorite(w.id())?BLUE:MUTED,303,688)){
                backend.toggleFavorite(w.id());clickedFavorite=true;
            }
            if(iconButtonClipped("world.manage."+w.id(),"settings",x+207,y+120,44,0,BLUE,303,688)){manageWorld(w);clicked=false;}
            if(clicked&&!clickedFavorite&&!backend.worldBusy(w.id()))openWorld(w);clickedFavorite=false;
        }
        c.restore();drawScrollbar(1237,306,379,contentH);
    }
    private boolean clickedFavorite=false;

    private void serverList(List<WorldEntry> entries){
        float contentH=entries.size()*154;scrollArea(216,306,685,contentH);
        c.save();c.clip(212,302,1016,386);
        for(int i=0;i<entries.size();i++){
            WorldEntry world=entries.get(i);float y=306+i*154-scroll;if(y+140<303||y>688)continue;
            var details=backend.serverDetails(world.id());
            boolean selected=hitClipped("world.open."+world.id(),216,y,1008,140,303,688);
            card(216,y,1008,140,false,animate("world.card."+world.id(),hover(216,y,1008,140)?1:0));
            imageOrPlaceholder(backend.worldImage(world.id()),234,y+18,80,80,"world",false);
            c.text(ellipsize(world.name(),34),332,y+17,21,INK);
            if(details!=null){
                int color=details.state().equals("SUCCESSFUL")?GREEN:details.loading()?MUTED:0xFFE2B598;
                c.text(ellipsize(details.population(),16),884,y+22,13,MUTED);
                c.text(details.connectionLabel(),1078,y+22,13,color);
                serverMotd(details,332,y+50,826);
                c.text(ellipsize(details.version(),21),726,y+111,12,MUTED);
            }
            c.text(ellipsize(world.subtitle(),40),332,y+111,12,MUTED);
            c.text(world.id().equals(activeWorldId)?"回到游戏":"进入服务器",1080,y+109,14,BLUE);
            if(iconButtonClipped("world.favorite."+world.id(),"heart",1170,y+91,40,0,backend.favorite(world.id())?BLUE:MUTED,303,688)){backend.toggleFavorite(world.id());selected=false;}
            if(iconButtonClipped("world.manage."+world.id(),"settings",1008,y+91,40,0,BLUE,303,688)){manageWorld(world);selected=false;}
            if(selected)openWorld(world);
        }
        c.restore();drawScrollbar(1237,306,379,contentH);
    }
    private void serverMotd(MenuBackend.ServerDetails details,float x,float y,float width){
        var spans=details.description().isEmpty()?List.of(new MenuBackend.TextSpan(details.motd(),MUTED,false)):details.description();
        float dx=0;int row=0;c.save();c.clip(x,y,width,44);
        for(var span:spans){
            StringBuilder run=new StringBuilder();
            for(int offset=0;offset<span.text().length()&&row<2;){
                int cp=span.text().codePointAt(offset);offset+=Character.charCount(cp);if(cp=='\r')continue;
                String character=new String(Character.toChars(cp));
                if(cp=='\n'||dx+c.width(run+character,14)>width){
                    if(span.bold())c.textBold(run.toString(),x+dx,y+row*22,14,span.color());else c.text(run.toString(),x+dx,y+row*22,14,span.color());
                    row++;dx=0;run.setLength(0);if(cp=='\n')continue;
                }
                if(row<2)run.append(character);
            }
            if(row<2){if(span.bold())c.textBold(run.toString(),x+dx,y+row*22,14,span.color());else c.text(run.toString(),x+dx,y+row*22,14,span.color());dx+=c.width(run.toString(),14);}
        }
        c.restore();
    }

    private void openWorld(WorldEntry world) {
        if(world.id().equals(backend.currentWorldId())){command("world.open",world.id());return;}
        String body=backend.inWorld()?"确认后会保存并退出当前世界，再进入所选目的地。":"进入所选世界或服务器。";
        var server=backend.serverDetails(world.id());
        if(server!=null)body+="\n\n"+server.motd()+"\n"+server.connectionLabel()+"  ·  "+server.population()+"  ·  "+server.version()
                +(server.players().isEmpty()?"":"\n在线玩家："+String.join("、",server.players()));
        modal=new Modal(world.name(),world.kind()+"  ·  "+world.subtitle(),body,backend.inWorld()?"保存并进入":"进入",()->command("world.open",world.id()),"world");modal.imageId=world.id();
    }
    private void addServer() {
        String error=backend.serverValidationError(serverName,serverAddress);if(!error.isBlank()){notifyUser(error);return;}
        backend.dispatch(new MenuCommand("server.add",serverAddress.trim(),Map.of("name",serverName.trim())));
        backend.refresh();worldFilter=2;modal=null;editField="";notifyUser(backend.status());
    }
    private void manageWorld(WorldEntry world) {
        searchFocused=false;editField="";
        modal=new Modal(world.name(),world.id().startsWith("server:")?world.subtitle():"单人存档 · "+world.id().substring(6),null,"完成",()->{},"manage");
        modal.world=world;
    }
    private void editServer(WorldEntry world) {
        serverName=world.name();serverAddress=world.subtitle();editField="name";
        modal=new Modal("编辑服务器","修改名称或地址；保存不会自动连接。",null,"保存",()->{
            String error=backend.serverValidationError(serverName,serverAddress);if(!error.isBlank()){notifyUser(error);return;}
            backend.dispatch(new MenuCommand("server.edit",world.id(),Map.of("expectedName",world.name(),"expectedAddress",world.subtitle(),"name",serverName.strip(),"address",serverAddress.strip())));
            modal=null;editField="";
        },"server");
    }
    private void confirmDelete(WorldEntry world) {
        boolean server=world.id().startsWith("server:");
        String body=server?"确认从服务器列表中删除此记录？\n地址："+world.subtitle()+"\n\n不会删除服务器上的世界，也不会断开当前连接。"
                :"确认永久删除这个单人存档？\n存档目录："+world.id().substring(6)+"\n\n该存档中的世界和玩家进度将被删除，无法撤销。";
        modal=new Modal(server?"删除服务器？":"永久删除存档？",world.name(),body,server?"确认删除":"永久删除",()->
                backend.dispatch(new MenuCommand(server?"server.delete":"world.delete",world.id(),Map.of("confirmed",true,"expectedName",world.name(),"expectedAddress",world.subtitle()))),"delete");
    }
    private void avatars() {
        pageHeading("虚拟形象","选择形象，调整属于你的细节。");
        String[] tabs={"形象库","形象调整","材质","VR 校准"};
        for(int i=0;i<tabs.length;i++)if(pill("avatar.tab."+i,tabs[i],216+i*130,237,120,44,avatarTab==i)){avatarTab=i;resetScroll();}
        c.text("PMX / PMD / VRM",1078,252,13,MUTED);
        AvatarEntry selected=selectedAvatar();
        card(216,306,276,380,false,0);
        avatarPortrait(selected.id(),240,332,228,227);
        c.textCenter(selected.name(),354,581,23,INK);c.textCenter(selected.kind()+" · 使用随身镜查看",354,619,13,MUTED);
        if(textButton("avatar.mirror",mirror?"关闭随身镜":"打开随身镜",293,640,124,40))toggleMirror();
        if(avatarTab==0)avatarLibrary(selected);if(avatarTab==1)avatarAdjustments();if(avatarTab==2)avatarMaterials();if(avatarTab==3)avatarCalibration();
    }
    private void avatarLibrary(AvatarEntry selected) {
        List<AvatarEntry> list=backend.avatars();if(list.isEmpty()){c.text("暂无可用形象",518,350,20,MUTED);return;}
        float content=(float)Math.ceil(list.size()/2.0)*142;scrollArea(516,306,684,content);
        c.save();c.clip(516,306,710,378);
        for(int i=0;i<list.size();i++){
            AvatarEntry a=list.get(i);float x=516+i%2*362,y=306+i/2*142-scroll;if(y+126<306||y>684)continue;
            boolean sel=a.id().equals(selectedAvatar),choose=hitClipped("avatar.select."+a.id(),x,y,346,126,306,684);
            card(x,y,346,126,sel,animate("avatar.hover."+i,hover(x,y,346,126)?1:0));
            avatarPortrait(a.id(),x+16,y+17,76,91);
            c.text(ellipsize(a.name(),12),x+111,y+20,20,INK);c.text(a.kind(),x+111,y+54,13,MUTED);
            c.text(a.id().equals(equippedAvatar)?"使用中":sel?"已选择":"选择形象",x+111,y+85,13,a.id().equals(equippedAvatar)?GREEN:MUTED);
            if(choose)selectAvatar(a.id());
        }
        c.restore();drawScrollbar(1237,306,378,content);
        if(button("avatar.equip",selected.id().equals(equippedAvatar)?"正在使用":"使用此形象","check",1014,150,210,48,BLUE,ON_ACCENT))command("avatar.select",selectedAvatar);
    }
    private void avatarAdjustments() {
        List<Setting> rows=backend.settings().stream().filter(s->s.id().startsWith("model.")).toList();
        float content=rows.size()*86;scrollArea(516,306,684,content);c.save();c.clip(516,306,710,378);
        for(int i=0;i<rows.size();i++){float y=306+i*86-scroll;if(y+78<306||y>684)continue;settingRow(rows.get(i),516,y,708,78);}
        c.restore();drawScrollbar(1237,306,378,content);
    }
    private void avatarMaterials() {
        var materials=backend.materials();float content=materials.size()*86;scrollArea(516,306,684,content);
        if(materials.isEmpty()){c.text("先使用并加载所选模型",536,358,19,MUTED);return;}
        c.save();c.clip(516,306,710,378);
        for(int i=0;i<materials.size();i++){
            var mat=materials.get(i);float y=306+i*86-scroll;if(y+78<306||y>684)continue;
            card(516,y,708,78,false,0);c.text(ellipsize(mat.name(),28),536,y+15,18,INK);c.text("当前模型材质",536,y+46,13,MUTED);
            boolean previous=allowInput;allowInput&=y>=306&&y+78<=684;
            if(toggle("material."+mat.id(),mat.selected(),1143,y+24))backend.dispatch(new MenuCommand("avatar.material",selectedAvatar,Map.of("material",mat.id(),"visible",!mat.selected())));
            allowInput=previous;
        }c.restore();drawScrollbar(1237,306,378,content);
    }
    private void avatarCalibration() {
        card(516,306,708,90,false,0);Art.icon(c,"hand",538,336,27,BLUE);
        c.text("站直身体，双臂向两侧平举",587,324,20,INK);c.text("校准模型追踪锚点，保持头显视角自然。",587,360,13,MUTED);
        String[] titles={"站姿与眼位","手臂映射","手指与拇指"};String[] desc={"对齐模型视线与站姿","调整触达比例，保持骨骼比例","独立调整左右手与拇指"};
        for(int i=0;i<3;i++){
            float y=416+i*71;c.text("0"+(i+1),531,y+12,15,BLUE);c.text(titles[i],580,y+2,18,INK);c.text(desc[i],580,y+32,13,MUTED);
            if(iconButton("calibrate.step."+i,"arrow",1164,y,48,CONTROL,INK)){
                navigate("settings");selectCategory("MMD Skin");settingsSection=i==2?"手指追踪 / 模式":"当前模型 / VR 校准";
                List<String> groups=backend.settings().stream().filter(s->s.category().equals(settingsCategory)).map(Setting::section).distinct().toList();
                sectionPage=Math.max(0,groups.indexOf(settingsSection))/6;
            }
        }
        if(button("calibrate.start","开始 T-Pose 校准","reset",1004,642,220,44,BLUE,ON_ACCENT)){
            command("calibration.start",selectedAvatar);
        }
    }

    private void motions() {
        pageHeading("动作与舞台","来自当前模型与本地资源目录。");
        String[] tabs={"快捷动作","表情控制","舞台"};
        for(int i=0;i<3;i++)if(pill("motion.tab."+i,tabs[i],216+i*142,237,132,44,motionTab==i)){motionTab=i;resetScroll();}
        if(motionTab==2){stageWorkbench();return;}
        var entries=motionTab==0?backend.actions():backend.morphs();
        float content=(float)Math.ceil(entries.size()/3.0)*148;scrollArea(216,306,624,content);c.save();c.clip(216,306,1010,318);
        for(int i=0;i<entries.size();i++){
            var entry=entries.get(i);float x=216+i%3*344,y=306+i/3*148-scroll;if(y+130<306||y>624)continue;
            if(hitClipped("motion.play."+entry.id(),x,y,320,130,306,624))command(motionTab==0?"motion.play":"morph.apply",entry.id());
            card(x,y,320,130,entry.selected(),animate("motion.hover."+i,hover(x,y,320,130)?1:0));
            Art.icon(c,motionTab==0?"play":"avatar",x+18,y+18,24,BLUE);c.text(ellipsize(entry.name(),17),x+18,y+56,20,INK);
            c.text(ellipsize(entry.description(),24),x+18,y+96,12,MUTED);
        }c.restore();drawScrollbar(1237,306,318,content);
        if(entries.isEmpty())emptyState("暂无可用资源","选择模型并将资源加入对应目录。",590,425);
        if(button("motion.stop",motionTab==0?"停止动作":"重置表情","stop",1004,640,220,44,CONTROL,INK))command(motionTab==0?"motion.stop":"morph.reset","");
    }
    private void stageWorkbench() {
        var entries=backend.stages();float content=entries.size()*94;scrollArea(216,306,612,content);c.save();c.clip(216,306,1010,306);
        for(int i=0;i<entries.size();i++){
            var entry=entries.get(i);float y=306+i*94-scroll;if(y+82<306||y>612)continue;
            card(216,y,1008,82,entry.selected(),0);c.text(ellipsize(entry.name(),30),236,y+15,21,INK);c.text(ellipsize(entry.description(),65),236,y+49,13,MUTED);
            boolean previous=allowInput;allowInput&=y>=306&&y+82<=612;
            if(button("stage.play."+entry.id(),"播放","play",1018,y+17,186,48,BLUE,ON_ACCENT))command("stage.play",entry.id());allowInput=previous;
        }c.restore();drawScrollbar(1237,306,306,content);
        if(entries.isEmpty())emptyState("暂无舞台包","在舞台管理中添加动作与音乐。",587,432);
        if(button("stage.configure","舞台与联机管理","settings",216,638,250,48,CONTROL,INK))command("stage.configure","");
        if(button("stage.stop","停止演出","stop",1014,638,210,48,CONTROL,INK))command("stage.stop","");
    }
    private void account() {
        pageHeading("账户","当前 Minecraft 会话。");
        card(216,244,1008,181,false,0);imageOrPlaceholder(backend.accountFace(),240,269,128,128,"account",true);
        c.text(backend.accountName(),398,279,30,INK);c.text(backend.accountKindDescription(),399,327,16,MUTED);
        c.text("身份来自游戏会话；登录由启动器管理。",399,363,14,MUTED);
        c.text("当前状态",216,465,18,INK);
        statusCard("Minecraft",ellipsize(backend.currentWorld(),19),"当前世界",216,506,"world",0);
        statusCard("MMD Skin",ellipsize(equippedName(),19),"当前形象",560,506,"avatar",0);
        statusCard("Vivecraft","VR 空间菜单","右手摇杆向前打开",904,506,"headset",0);
    }
    private void statusCard(String title,String status,String desc,float x,float y,String icon,int ignored) {
        card(x,y,320,170,false,0);Art.icon(c,icon,x+22,y+23,24,MUTED);c.text(title,x+62,y+24,21,INK);
        c.text(status,x+22,y+86,17,INK);c.text(desc,x+22,y+126,14,MUTED);
    }
    private void settings() {
        pageHeading("设置","为你的游玩方式调整。");searchBox(926,146,298,"搜索当前分类");
        String[] categories={"Minecraft","MMD Skin","Vivecraft","界面"};
        for(int i=0;i<4;i++)if(pill("settings.category."+i,categories[i],216+i*160,237,148,44,settingsCategory.equals(categories[i])))selectCategory(categories[i]);
        List<Setting> all=backend.settings().stream().filter(s -> s.category().equals(settingsCategory)).toList();List<String> sections=all.stream().map(Setting::section).distinct().toList();
        if(!sections.contains(settingsSection)&&!sections.isEmpty())settingsSection=sections.get(0);
        c.line(417,307,417,681,0x16FFFFFF,1);
        int sectionPages=Math.max(1,(sections.size()+5)/6);sectionPage=Math.min(sectionPage,sectionPages-1);
        for(int i=sectionPage*6;i<Math.min(sections.size(),sectionPage*6+6);i++){
            String name=sections.get(i);float y=307+(i%6)*54;boolean sel=settingsSection.equals(name)&&query.isBlank();
            if(hit("settings.section."+i,216,y,184,48)){settingsSection=name;resetScroll();query="";}
            c.fill(216,y,184,48,8,sel?0x14FFFFFF:0x00FFFFFF);if(sel)c.fill(216,y+14,3,20,1.5f,BLUE);
            c.text(ellipsize(name,11),231,y+15,15,sel?INK:MUTED);
        }
        if(sectionPages>1){
            if(iconButton("settings.sections.prev","back",216,641,42,CONTROL,INK))sectionPage=Math.floorMod(sectionPage-1,sectionPages);
            c.textCenter((sectionPage+1)+" / "+sectionPages,306,654,13,MUTED);
            if(iconButton("settings.sections.next","arrow",358,641,42,CONTROL,INK))sectionPage=(sectionPage+1)%sectionPages;
        }
        List<Setting> visible=all.stream().filter(t->query.isBlank()?t.section().equals(settingsSection):matches(t.title()+t.description()+t.section(),query)).toList();
        c.text(query.isBlank()?settingsSection:"搜索结果 · "+visible.size()+" 项",444,307,17,INK);
        if(textButton("settings.reset","恢复默认",1128,299,96,40)){
            String category=settingsCategory;modal=new Modal("恢复默认设置？",category,"恢复此分类可编辑选项的默认值，\n并应用到游戏配置。","恢复默认",()->{backend.resetCategory(category);notifyUser(category+" 已恢复默认");},"reset");
        }
        if(visible.isEmpty()){emptyState("没有匹配的设置","尝试其他关键词。",630,464);return;}
        float contentH=visible.size()*88;scrollArea(444,345,685,contentH);
        c.save();c.clip(441,343,788,346);
        for(int i=0;i<visible.size();i++){float y=345+i*88-scroll;if(y+80<344||y>688)continue;settingRow(visible.get(i),444,y,780,80);}
        c.restore();drawScrollbar(1237,345,340,contentH);
    }

    private void selectCategory(String category){settingsCategory=category;settingsSection="";sectionPage=0;resetScroll();query="";searchFocused=false;}
    private void settingRow(Setting s,float x,float y,float w,float h) {
        card(x,y,w,h,false,0);Object value=backend.get(s.id());
        if(value==null)value=s.defaultValue();
        c.text(ellipsize(s.title(),w>750?23:19),x+18,y+14,17,INK);c.text(ellipsize(s.description(),w>750?28:24),x+18,y+46,12,MUTED);
        boolean before=allowInput;allowInput=allowInput&&(page.equals("settings")?y>=341&&y+h<=692:y>=306&&y+h<=684);
        if(hit("setting.info."+s.id(),x+12,y+6,w-324,h-12))modal=new Modal(s.title(),s.category()+" · "+s.section(),s.description()+"\n\n默认值："+s.defaultValue(),"知道了",()->{},"info");
        allowInput&=backend.enabled(s.id());
        switch(s.kind()){
            case TOGGLE->{boolean on=(Boolean)value;c.text(on?"开启":"关闭",x+w-133,y+29,14,MUTED);if(toggle("setting."+s.id(),on,x+w-79,y+24))backend.set(s.id(),!on);}
            case SLIDER->{boolean distance=s.id().equals("ui.panelDistance");
                double v=distance&&panelDistancePreview!=null?panelDistancePreview:((Number)value).doubleValue();float tw=w>750?176:145,tx=x+w-tw-130;
                localSlider("setting."+s.id(),(float)((v-s.min())/(s.max()-s.min())),tx,y+38,tw,a->{
                    double next=((Number)s.normalize(s.min()+a*(s.max()-s.min()))).doubleValue();
                    if(distance&&!keyActivation.equals("setting."+s.id())){
                        // A ray turned away from the plane must not reset the preview to the minimum.
                        if(in.x>=0&&in.y>=0)panelDistancePreview=next;
                    }else backend.set(s.id(),next);
                });
                String display=distance?String.format(Locale.ROOT,"%.2f m",panelDistancePreview!=null?panelDistancePreview:backend.number(s.id())):backend.display(s.id());
                c.save();c.clip(x+w-124,y+19,109,42);c.text(display,x+w-121,y+29,13,INK);c.restore();}
            case CHOICE->{if(button("setting."+s.id(),ellipsize(backend.display(s.id()),13),"chevron-down",x+w-260,y+16,242,48,CONTROL,INK))openChoices(s);}
            case ACTION->{if(button("setting."+s.id(),"打开","arrow",x+w-180,y+16,162,48,CONTROL,INK))backend.set(s.id(),value);}
            case TEXT->{if(button("setting."+s.id(),ellipsize(String.valueOf(value),16),"edit",x+w-260,y+16,242,48,CONTROL,INK)){
                textValue=String.valueOf(value);editField="setting";modal=new Modal(s.title(),s.description(),null,"保存",()->backend.set(s.id(),textValue),"text");modal.setting=s;
            }}
        }
        allowInput=before;
    }

    private void openChoices(Setting s){modal=new Modal(s.title(),s.description(),null,"完成",()->{},"choice");modal.setting=s;modal.choicePage=0;}
    private void quickView() {
        glass(220,216,448,506,24);
        imageOrPlaceholder(backend.accountFace(),246,244,29,29,"account",true);c.text(backend.accountName(),295,239,22,INK);c.text(activeWorld,295,272,13,MUTED);
        if(iconButton("quick.expand","grid",602,240,44,CONTROL,INK))navigate(lastPage);
        c.line(244,309,644,309,LINE,1);
        int[] indices={1,2,3,5};
        for(int i=0;i<4;i++){
            int pi=indices[i];float x=244+i%2*206,y=331+i/2*105;
            if(hit("quick.nav."+PAGES[pi],x,y,194,91))navigate(PAGES[pi]);
            card(x,y,194,91,false,animate("quick.nav."+i,hover(x,y,194,91)?1:0));
            Art.icon(c,ICONS[pi],x+17,y+15,24,BLUE);c.text(NAMES[pi],x+17,y+55,18,INK);
        }
        String[] icons={"mirror","reset","volume","account"},names={mirror?"镜子已开":"随身镜","归中","声音","账户"};
        for(int i=0;i<4;i++){
            float x=247+i*104;
            if(iconButton("quick.action."+i,icons[i],x+18,551,48,i==0&&mirror?PALE:CONTROL,i==0&&mirror?BLUE:INK)){
                if(i==0)toggleMirror();if(i==1){command("vr.recenter","");notifyUser(backend.status());}
                if(i==2){navigate("settings");selectCategory("Minecraft");settingsSection="声音与音量";}if(i==3)navigate("account");
            }
            c.textCenter(names[i],x+42,613,13,MUTED);
        }
        if(button("quick.resume","回到游戏","play",244,656,400,44,CONTROL,INK)){hidden=true;quick=false;}
        c.fill(412,741,64,4,2,0x75D4DEEF);
        if(!surfaceOnly)c.textCenter("右手菜单",444,767,13,MUTED);
    }
    private void closedView() {
        if(button("closed.open","打开菜单","hand",606,694,228,52,0xBA202733,INK)){quick=true;hidden=false;}
    }

    private void renderModal() {
        c.fill(0,0,1440,900,0,0x60060B12);allowInput=true;
        boolean choice=modal.type.equals("choice"),server=modal.type.equals("server"),text=modal.type.equals("text");
        boolean deleting=modal.type.equals("delete"),manage=modal.type.equals("manage"),info=modal.type.equals("info")||deleting;
        float height=choice||info||modal.type.equals("world")||manage?548:server?460:432,top=(900-height)/2;
        c.shadow(434,top,572,height,20,30,10,0x65000000);c.fill(434,top,572,height,20,0xF2222935);c.stroke(434,top,572,height,20,0x42D9E3F2,1);
        c.save();c.clip(465,top+20,472,45);c.text(modal.title,465,top+30,25,INK);c.restore();
        if(iconButton("modal.close","close",945,top+20,44,CONTROL,MUTED)){modal=null;editField="";return;}
        String sub=modal.subtitle==null?"":modal.subtitle;
        c.save();c.clip(465,top+75,510,49);textLines(sub,465,top+78,13,MUTED,65,22);c.restore();
        if(info) {
            var lines=wrapLines(modal.body,14,65);float bodyTop=top+134,visible=height-224,content=lines.size()*25;
            updateScroll(modal.scrolling,465,bodyTop,510,visible,content,987);
            modal.scroll=modal.scrolling.offset();
            c.save();c.clip(465,bodyTop,510,visible);
            for(int i=0;i<lines.size();i++)c.text(lines.get(i),465,bodyTop+i*25-modal.scroll,14,MUTED);
            c.restore();
            drawScrollbar(modal.scrolling,987,bodyTop,visible,content);
        } else if(text) {
            inputBox("setting",textValue,"输入值",465,top+160,510);
        } else if(server) {
            c.textBold("服务器名称",465,top+139,14,INK);inputBox("name",serverName,"例如：朋友的小岛",465,top+170,510);
            c.textBold("服务器地址",465,top+235,14,INK);inputBox("address",serverAddress,"例如：play.example.com:25565",465,top+266,510);
            c.text("保存到 Minecraft 服务器列表；不会自动连接。",465,top+339,12,MUTED);
        } else if(manage) {
            WorldEntry world=modal.world;boolean remote=world.id().startsWith("server:");
            if(remote&&button("world.edit","编辑名称与地址","settings",465,top+147,510,54,CONTROL,INK)){editServer(world);return;}
            float deleteY=top+(remote?225:147);
            if(backend.canDeleteWorld(world.id())) {
                if(button("world.delete","删除"+(remote?"服务器":"单人存档"),null,465,deleteY,510,54,0x24E1A7AA,0xFFF2B8BD)){confirmDelete(world);return;}
                textLines(remote?"只删除本机保存的服务器记录。":"删除前会再次确认存档名称。",465,deleteY+79,14,MUTED,45,26);
            } else textLines(backend.worldBusy(world.id())?"正在删除此存档，请等待完成。":"该存档正在使用或不可删除。\n请先退出存档，再刷新列表。",465,deleteY+14,16,MUTED,40,28);
        } else if(choice) {
            List<String> options=modal.setting.options();int pages=(int)Math.ceil(options.size()/8.0);
            int from=modal.choicePage*8,to=Math.min(from+8,options.size());
            for(int i=from;i<to;i++) {
                String opt=options.get(i);float x=465+((i-from)%2)*260,y=top+142+((i-from)/2)*61;
                if(pill("modal.choice."+i,choiceLabel(opt),x,y,250,49,backend.string(modal.setting.id()).equals(opt)))backend.set(modal.setting.id(),opt);
            }
            if(pages>1){if(iconButton("modal.prev","back",465,top+399,38,PALE,BLUE))modal.choicePage=Math.floorMod(modal.choicePage-1,pages);
                c.text((modal.choicePage+1)+" / "+pages,520,top+409,13,MUTED);if(iconButton("modal.next","arrow",590,top+399,38,PALE,BLUE))modal.choicePage=(modal.choicePage+1)%pages;}
        } else if(modal.type.equals("world")) {
            imageOrPlaceholder(backend.worldImage(modal.imageId),465,top+122,510,127,"world",false);
            var lines=wrapLines(modal.body,14,65);float bodyTop=top+269,visible=height-359,content=lines.size()*25;
            updateScroll(modal.scrolling,465,bodyTop,510,visible,content,987);
            c.save();c.clip(465,bodyTop,510,visible);
            for(int i=0;i<lines.size();i++)c.text(lines.get(i),465,bodyTop+i*25-modal.scrolling.offset(),14,MUTED);
            c.restore();drawScrollbar(modal.scrolling,987,bodyTop,visible,content);
        } else {
            c.fill(465,top+131,58,58,17,PALE);Art.icon(c,modal.type.equals("calibration")?"hand":modal.type.equals("session")?"account":"info",481,top+147,26,BLUE);
            textLines(modal.body,465,top+214,14,MUTED,45,27);
        }
        float buttonY=top+height-69;
        if(button("modal.cancel",choice?"关闭":"取消",null,465,buttonY,152,48,CONTROL,INK)){modal=null;editField="";return;}
        if(button("modal.confirm",modal.confirm,null,739,buttonY,236,48,deleting?0xFFE1A7AA:BLUE,ON_ACCENT)) {
            Modal chosen=modal;
            if(!server)modal=null;
            chosen.action.run();if(modal==null)editField="";
        }
    }
    private void helpOverlay() {
        c.fill(0,0,1440,900,0,0x60060B12);allowInput=true;
        c.fill(420,194,600,512,22,0xF2222935);c.stroke(420,194,600,512,22,LINE,1);c.text("操作说明",454,229,27,INK);
        c.text("右手快捷入口与头前详细菜单",455,275,14,MUTED);
        String[][] rows={{"右手摇杆向前","打开右手简略菜单"},{"扳机","点击控件，按住页面可拖动滚动"},{"右手摇杆上下","滚动当前列表"},{"返回 / 关闭","返回简略菜单或继续游戏"},{"搜索输入框","唤出虚拟键盘"},{"设置分组翻页","浏览完整分类与更多选项"}};
        for(int i=0;i<rows.length;i++){float y=326+i*46;c.textBold(rows[i][0],455,y,14,INK);c.text(rows[i][1],664,y,14,MUTED);}
        if(button("help.close","开始探索","arrow",454,635,532,48,BLUE,ON_ACCENT))help=false;
    }
    private void searchBox(float x,float y,float w,String placeholder) {
        if(hit("search",x,y,w,48)){searchFocused=true;focusId="";}
        c.fill(x,y,w,48,10,0x22000000);c.stroke(x+.5f,y+.5f,w-1,47,10,searchFocused?BLUE:LINE,1);
        Art.icon(c,"search",x+15,y+14,20,MUTED);
        c.save();c.clip(x+45,y+5,w-86,38);c.text(query.isEmpty()?placeholder:query,x+45,y+15,15,query.isEmpty()?MUTED:INK);c.restore();
        if(searchFocused&&(int)(time*2)%2==0){float tx=x+46+Math.min(w-92,c.width(query,15));c.line(tx,y+15,tx,y+34,INK,1);}
        if(!query.isEmpty()&&iconButton("search.clear","close",x+w-42,y+3,40,0x00FFFFFF,MUTED)){query="";resetScroll();}
    }
    private void inputBox(String id,String value,String placeholder,float x,float y,float w) {
        if(hit("input."+id,x,y,w,48))editField=id;
        c.fill(x,y,w,48,8,0x30000000);c.stroke(x,y,w,48,8,editField.equals(id)?BLUE:LINE,1);
        c.save();c.clip(x+14,y+6,w-28,36);c.text(value.isEmpty()?placeholder:value,x+15,y+15,16,value.isEmpty()?MUTED:INK);c.restore();
    }

    private boolean hit(String id,float x,float y,float w,float h) {
        if(!allowInput||w<=0||h<=0||pointerConsumed())return false;
        targets.add(new HitTarget(id,x,y,w,h));boolean over=hover(x,y,w,h);
        if(in.pressed&&over){pressedId=id;focusId="";}
        boolean click=(in.released&&over&&pressedId.equals(id))||keyActivation.equals(id);
        if(click&&id.startsWith("world.favorite."))clickedFavorite=true;
        return click;
    }
    private boolean hitClipped(String id,float x,float y,float w,float h,float top,float bottom) {
        return hit(id,x,Math.max(top,y),w,Math.max(0,Math.min(bottom,y+h)-Math.max(top,y)));
    }
    private boolean controlHit(String id,float x,float y,float w,float h) {
        float px=Math.max(0,(48-w)/2),py=Math.max(0,(48-h)/2);
        return hit(id,x-px,y-py,w+2*px,h+2*py);
    }
    private boolean hover(float x,float y,float w,float h){return allowInput&&!pointerConsumed()&&in.x>=x&&in.x<x+w&&in.y>=y&&in.y<y+h;}
    private boolean button(String id,String label,String icon,float x,float y,float w,float h,int bg,int fg) {
        boolean clicked=controlHit(id,x,y,w,h);float hv=animate(id,hover(x,y,w,h)?1:0),press=in.down&&pressedId.equals(id)?1:0;
        boolean primary=bg==BLUE;c.fill(x,y+press,w,h,9,mix(bg,primary?0xFFC5DDFB:0x28FFFFFF,hv*.65f));
        c.stroke(x+.5f,y+.5f+press,w-1,h-1,9,primary?0x70E9F4FF:highContrast?0x80FFFFFF:0x23FFFFFF,1);
        if(icon==null)c.textCenter(label,x+w/2,y+(h-18)/2,16,fg);
        else {float tw=c.width(label,16),sx=x+(w-tw-31)/2;Art.icon(c,icon,sx,y+(h-20)/2,20,fg);c.text(label,sx+31,y+(h-18)/2,16,fg);}
        return clicked;
    }
    private boolean pill(String id,String label,float x,float y,float w,float h,boolean selected) {
        boolean clicked=controlHit(id,x,y,w,h);float hv=animate(id,hover(x,y,w,h)?1:0);
        c.fill(x,y,w,h,8,selected?0x28A9C9F6:alpha(0x12FFFFFF,hv));
        if(selected)c.stroke(x+.5f,y+.5f,w-1,h-1,8,0x36BDD8FA,1);
        c.save();c.clip(x+7,y,w-14,h);c.textCenter(label,x+w/2,y+(h-18)/2,16,selected?INK:MUTED);c.restore();return clicked;
    }
    private boolean textButton(String id,String text,float x,float y,float w,float h) {
        boolean clicked=controlHit(id,x,y,w,h);c.text(text,x,y+(h-17)/2,15,hover(x,y,w,h)?INK:BLUE);return clicked;
    }
    private boolean iconButton(String id,String icon,float x,float y,float size,int bg,int fg) {
        boolean clicked=controlHit(id,x,y,size,size);drawIconButton(id,icon,x,y,size,bg,fg,hover(x,y,size,size));return clicked;
    }
    private boolean iconButtonClipped(String id,String icon,float x,float y,float size,int bg,int fg,float top,float bottom) {
        float pad=Math.max(0,(48-size)/2);
        boolean clicked=hitClipped(id,x-pad,y-pad,size+pad*2,size+pad*2,top,bottom);drawIconButton(id,icon,x,y,size,bg,fg,hover(x,y,size,size)&&in.y>=top&&in.y<bottom);return clicked;
    }
    private void drawIconButton(String id,String icon,float x,float y,float size,int bg,int fg,boolean over) {
        float hv=animate(id,over?1:0);c.fill(x,y,size,size,10,mix(bg,bg==BLUE?0xFFC5DDFB:0x25FFFFFF,hv*.7f));
        if((bg>>>24)>0)c.stroke(x+.5f,y+.5f,size-1,size-1,10,0x20FFFFFF,1);
        Art.icon(c,icon,x+(size-22)/2,y+(size-22)/2,22,fg);
    }
    private boolean toggle(String id,boolean on,float x,float y) {
        boolean clicked=hit(id,x-8,y-10,68,48);float state=animate(id+".state",on?1:0);
        c.fill(x,y,52,28,14,mix(0x30434C5F,BLUE,state));c.stroke(x+.5f,y+.5f,51,27,14,on?0x50FFFFFF:0x85B3C0D4,1);
        c.circle(x+14+24*state,y+14,9,mix(INK,ON_ACCENT,state));return clicked;
    }
    private void localSlider(String id,float value,float x,float y,float width,Consumer<Float> changed) {
        boolean clicked=hit(id,x-10,y-24,width+20,48);
        if(allowInput&&in.pressed&&hover(x-10,y-24,width+20,48)){dragId=id;scrolling.captureControl();}
        if(allowInput&&((dragId.equals(id)&&(in.down||in.released))||clicked)
                &&(!id.equals("setting.ui.panelDistance")||keyActivation.equals(id)||in.x>=0&&in.y>=0)){
            float v=keyActivation.equals(id)?Math.min(1,value+.1f):Math.max(0,Math.min(1,(in.x-x)/width));changed.accept(v);value=v;
        }
        c.fill(x,y-2,width,4,2,0x35ADBBD0);c.fill(x,y-2,width*value,4,2,BLUE);
        c.circle(x+width*value,y,10,0xFF303B4D);c.stroke(x+width*value-10,y-10,20,20,10,0xC0D9E4F5,1);c.circle(x+width*value,y,5,BLUE);
    }

    private void imageOrPlaceholder(MenuBackend.ImageData image,float x,float y,float w,float h,String icon,boolean pixelated){
        if(!c.image(image,x,y,w,h,Math.min(12,Math.min(w,h)*.14f),pixelated))Art.placeholder(c,x,y,w,h,icon);
    }
    private void avatarPortrait(String id,float x,float y,float w,float h){
        var image=backend.avatarImage(id);
        if(image!=null){float scale=Math.min(w/image.width(),h/image.height()),iw=image.width()*scale,ih=image.height()*scale;
            if(c.image(image,x+(w-iw)/2,y+(h-ih)/2,iw,ih,0,false))return;}
        Art.placeholder(c,x,y,w,h,"avatar");
    }
    private void label(String text,float x,float y,int bg,int fg){float w=c.width(text,11)+20;c.fill(x,y,w,24,12,bg);c.text(text,x+10,y+5,11,fg);}
    private void emptyState(String title,String body,float x,float y){Art.icon(c,"search",x+100,y-55,34,MUTED);c.text(title,x,y,23,INK);c.text(body,x,y+44,14,MUTED);}
    private void resetScroll(){scroll=0;scrolling.reset();}
    private boolean pointerConsumed(){return scrolling.consumesPointer() || modal!=null && modal.scrolling.consumesPointer();}
    private void scrollArea(float left,float top,float bottom,float contentHeight){
        updateScroll(scrolling,left,top,1224-left,bottom-top,contentHeight,1237);
        scroll=scrolling.offset();
    }
    private void updateScroll(ScrollGesture gesture,float left,float top,float width,float height,float content,float trackX){
        if(gesture==scrolling)scrollUpdated=true;
        gesture.update(in,left,top,width,height,content,trackX,dt,allowInput,!dragId.isEmpty(),reducedMotion);
        if(gesture.consumesPointer()){pressedId="";focusId="";}
        if(content>height)targets.add(new HitTarget(gesture==scrolling?"scroll.main":"scroll.modal",trackX-14,top,30,height));
    }
    private void drawScrollbar(float x,float y,float height,float contentH){drawScrollbar(scrolling,x,y,height,contentH);}
    private void drawScrollbar(ScrollGesture gesture,float x,float y,float height,float contentH) {
        if(contentH<=height)return;
        float thumb=gesture.thumbSize(),sy=y+gesture.thumbTop();
        float excess=gesture.offset()<0?-gesture.offset():Math.max(0,gesture.offset()-gesture.maximum());
        if(excess>0){thumb=Math.max(18,thumb-excess*.45f);sy=gesture.offset()<0?y:y+height-thumb;}
        boolean over=in.inside(x-14,y,30,height)||gesture.consumesPointer();
        c.fill(x-10,y,24,height,12,over?0x0EFFFFFF:0x00FFFFFF);
        c.fill(x-(over?1:0),sy,over?5:3,thumb,2.5f,over?0xDBBCD4F5:0x809FACC2);
    }
    private void roundedClip(float x,float y,float w,float h,float r){c.clip(x,y,w,h);/* Artwork stays inside the parent card bounds. */}
    private float animate(String id,float target){float before=animations.getOrDefault(id,target),result=reducedMotion?target:before+(target-before)*Math.min(1,dt*14);animations.put(id,result);return result;}
    private void navigate(String next){cancelPointer();page=Arrays.asList(PAGES).contains(next)?next:"home";lastPage=page;quick=false;hidden=false;modal=null;help=false;searchFocused=false;query="";resetScroll();focusId="";pageReveal=0;}
    private void toggleMirror(){command("mirror.mode",backend.mirrorMode().equals("OFF")?"LOW":"OFF");}
    private void command(String type,String target){backend.dispatch(new MenuCommand(type,target));notifyUser(backend.status());}
    private void notifyUser(String message){toast=message;toastTime=3.6f;}
    private AvatarEntry selectedAvatar(){return backend.avatars().stream().filter(a->a.id().equals(selectedAvatar)).findFirst().orElse(new AvatarEntry("","默认形象","","Minecraft",0,false));}
    private void selectAvatar(String id){selectedAvatar=id;backend.selectModelContext(id);}
    private String equippedName(){return backend.avatars().stream().filter(a->a.id().equals(equippedAvatar)).map(AvatarEntry::name).findFirst().orElse("默认形象");}
    private String equippedKind(){return backend.avatars().stream().filter(a->a.id().equals(equippedAvatar)).map(AvatarEntry::kind).findFirst().orElse("Minecraft");}
    private String activeWorldKind(){
        for(WorldEntry w:backend.worlds())if(w.id().equals(activeWorldId))return w.kind();
        return backend.currentWorld();
    }
    private static String formatTime(float seconds){return String.format(Locale.ROOT,"%02d:%02d",(int)seconds/60,(int)seconds%60);}
    private static String formatValue(Setting s,double value){
        if(s.id().equals("pointer.rayWidth"))return String.format(Locale.ROOT,"%.1f mm",value*1000);
        if(s.step()>=1)return String.format(Locale.ROOT,"%.0f",value);
        if(s.step()<.001)return String.format(Locale.ROOT,"%.4f",value);
        return String.format(Locale.ROOT,"%.2f",value);
    }
    private static String choiceLabel(String value){return switch(value){case "FIXED_CLOSE"->"不跟随，超距关闭";case "FIXED_STAY"->"不跟随，不自动关闭";case "FOLLOW_POSITION"->"跟随玩家，保持距离";case "FOLLOW_VIEW"->"跟随玩家和视角";case "ALWAYS"->"始终显示";case "UI_ONLY"->"打开界面时";case "UI_HIT"->"命中界面时";case "OFF"->"关闭";case "TRIGGER"->"扳机键";case "GRIP"->"握持键";case "TRIGGER_OR_GRIP"->"扳机或握持键";default->value;};}
    private static boolean matches(String value,String needle){return value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT).trim());}
    private static String ellipsize(String s,int limit){
        s=s.replaceAll("§[0-9a-fk-orA-FK-OR]", "").replaceAll("[\\r\\n\\t]+", " ");
        return s.codePointCount(0,s.length())>limit?s.substring(0,s.offsetByCodePoints(0,limit-1))+"…":s;
    }
    private void textLines(String body,float x,float y,float size,int color,int maxChars,float lineHeight){
        var rows=wrapLines(body,size,maxChars);
        for(int i=0;i<rows.size();i++)c.text(rows.get(i),x,y+i*lineHeight,size,color);
    }
    private List<String> wrapLines(String body,float size,int maxChars){
        if(body==null)return List.of();
        List<String> lines=new ArrayList<>();
        for(String part:body.replaceAll("§[0-9a-fk-orA-FK-OR]", "").split("\n",-1)){
            StringBuilder row=new StringBuilder();
            for(int cp:part.codePoints().toArray()){
                String glyph=new String(Character.toChars(cp));
                if(!row.isEmpty()&&(c.width(row+glyph,size)>510||row.codePointCount(0,row.length())>=maxChars)){
                    lines.add(row.toString());row.setLength(0);
                }
                row.append(glyph);
            }
            lines.add(row.toString());
        }
        return lines;
    }
    private static int alpha(int color,float amount){return ((int)(((color>>>24)&255)*Math.max(0,Math.min(1,amount)))<<24)|(color&0xFFFFFF);}
    private static int mix(int a,int b,float t){t=Math.max(0,Math.min(1,t));int result=0;for(int shift:new int[]{0,8,16,24}){int v=Math.round(((a>>>shift)&255)*(1-t)+((b>>>shift)&255)*t);result|=v<<shift;}return result;}
    private static float ease(float t){return 1-(1-t)*(1-t)*(1-t);}
    public void key(int key,int action,int mods) {
        if(action!=GLFW_PRESS&&action!=GLFW_REPEAT)return;
        if(key==GLFW_KEY_ESCAPE||key==GLFW_KEY_TAB||key==GLFW_KEY_F1)cancelPointer();
        if(key==GLFW_KEY_F1){help=!help;return;}
        if(key==GLFW_KEY_ESCAPE){if(help){help=false;return;}if(modal!=null){modal=null;editField="";return;}if(searchFocused){searchFocused=false;return;}if(hidden){hidden=false;quick=true;}else if(quick){hidden=true;quick=false;}else quick=true;return;}
        if(key==GLFW_KEY_TAB){if(modal!=null)return;hidden=false;quick=!quick;searchFocused=false;focusId="";return;}
        if(key==GLFW_KEY_F&&(mods&GLFW_MOD_CONTROL)!=0){if(page.equals("worlds")||page.equals("settings"))searchFocused=true;return;}
        if(key==GLFW_KEY_BACKSPACE){if(modal!=null&&modal.type.equals("text")){textValue=removeLast(textValue);}else if(modal!=null&&modal.type.equals("server")){if(editField.equals("name"))serverName=removeLast(serverName);else serverAddress=removeLast(serverAddress);}else if(searchFocused){query=removeLast(query);resetScroll();}return;}
        if(key==GLFW_KEY_ENTER){if(searchFocused){searchFocused=false;return;}keyActivation=focusId;return;}
        if(!searchFocused&&editField.isEmpty()){
            if(key==GLFW_KEY_RIGHT||key==GLFW_KEY_DOWN)keyboardMove=1;
            if(key==GLFW_KEY_LEFT||key==GLFW_KEY_UP)keyboardMove=-1;
        }
    }
    public void character(int codepoint) {
        if(Character.isISOControl(codepoint))return;String value=new String(Character.toChars(codepoint));
        if(modal!=null&&modal.type.equals("text")){if(textValue.length()<4096)textValue+=value;}
        else if(modal!=null&&modal.type.equals("server")){if(editField.equals("name")&&serverName.length()<128)serverName+=value;else if(editField.equals("address")&&serverAddress.length()<255)serverAddress+=value;}
        else if(searchFocused&&query.length()<64){query+=value;resetScroll();}
    }
    private static String removeLast(String text){return text.isEmpty()?text:text.substring(0,text.offsetByCodePoints(text.length(),-1));}
    public boolean wantsTextInput(){return searchFocused||!editField.isEmpty();}
    public void openQuick(){cancelPointer();quick=true;hidden=false;modal=null;searchFocused=false;editField="";}
    public void openDetail(){navigate(lastPage);}
    public void back(){key(GLFW_KEY_ESCAPE,GLFW_PRESS,0);}
    public void cancelPointer(){panelDistancePreview=null;pressedId="";dragId="";keyActivation="";scrolling.cancel();if(modal!=null)modal.scrolling.cancel();}
    /** Read-only semantic hit boxes used by the real OpenGL interaction smoke test. */
    public List<HitTarget> hitTargets(){return List.copyOf(targets);}
    public record HitTarget(String id,float x,float y,float w,float h){}
    private static final class Modal {
        final String title,subtitle,body,confirm,type;final Runnable action;final ScrollGesture scrolling=new ScrollGesture();int palette,choicePage;float scroll;Setting setting;String imageId="";WorldEntry world;
        Modal(String title,String subtitle,String body,String confirm,Runnable action,String type){this.title=title;this.subtitle=subtitle;this.body=body;this.confirm=confirm;this.action=action;this.type=type;}
    }
    @Override public void close(){backend.close();}
}
