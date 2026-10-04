package com.shiroha.mmdskin.ui.spatial.backend;

import com.shiroha.mmdskin.ui.spatial.lumen.model.MenuBackend.ServerDetails;
import com.shiroha.mmdskin.ui.spatial.lumen.model.MenuBackend.TextSpan;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.*;

/** Native status protocol/SRV resolution/legacy fallback, without doing DNS or connecting on the UI thread. */
final class MenuServerStatus implements AutoCloseable {
    private final Minecraft mc=Minecraft.getInstance();
    private final ExecutorService workers=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"MMD-ServerStatus");t.setDaemon(true);return t;});
    private final Map<ServerData,Probe> probes=new IdentityHashMap<>();
    private final Runnable iconsChanged;
    private long nextTick;
    private boolean closed;
    private static final class Probe {
        final ServerData data;final ServerStatusPinger pinger=new ServerStatusPinger();
        volatile boolean cancelled;volatile long started;Future<?> future;
        Probe(ServerData data){this.data=data;}
        void cancel(){cancelled=true;if(future!=null)future.cancel(false);pinger.removeAll();}
    }
    MenuServerStatus(Runnable iconsChanged){this.iconsChanged=iconsChanged;}
    ServerDetails details(ServerData data) {
        if(!closed&&!probes.containsKey(data)){
            Probe probe=new Probe(data);probes.put(data,probe);
            data.setState(ServerData.State.PINGING);data.ping=-1;data.motd=Component.empty();data.status=Component.empty();
            probe.future=workers.submit(()->{
                if(probe.cancelled)return;probe.started=System.nanoTime();
                try{
                    probe.pinger.pingServer(data,()->mc.execute(()->{
                        if(!probe.cancelled){iconsChanged.run();saveIcon(data);}
                    }),()->mc.execute(()->{if(!probe.cancelled)markReady(data);}));
                }catch(Exception failure){mc.execute(()->{if(!probe.cancelled){data.setState(ServerData.State.UNREACHABLE);data.motd=Component.translatable(failure instanceof java.net.UnknownHostException?"multiplayer.status.cannot_resolve":"multiplayer.status.cannot_connect");}});}
                finally{if(probe.cancelled)probe.pinger.removeAll();}
            });
        }
        ServerData.State state=data.state();
        String message=state==ServerData.State.PINGING||state==ServerData.State.INITIAL?"正在检测服务器…":plain(data.motd);
        String population=data.players==null?plain(data.status):data.players.online()+" / "+data.players.max();
        List<String> players=data.players==null?List.of():data.players.sample().stream().limit(12).map(com.mojang.authlib.GameProfile::getName).toList();
        boolean reachable=state==ServerData.State.SUCCESSFUL||state==ServerData.State.INCOMPATIBLE;
        return new ServerDetails(state.name(),message,population,reachable?plain(data.version):"",data.ping,players,reachable?description(data.motd):List.of());
    }
    void tick(){
        long now=System.nanoTime();if(closed||now<nextTick)return;nextTick=now+50_000_000L;
        for(Probe probe:probes.values()){
            if(probe.cancelled)continue;
            probe.pinger.tick();
            if(probe.data.state()==ServerData.State.PINGING){
                // Old servers may answer through the vanilla legacy fallback without its pong callback.
                if(probe.data.ping>=0)markReady(probe.data);
                else if(probe.started!=0&&now-probe.started>10_000_000_000L){
                    probe.cancel();probe.data.setState(ServerData.State.UNREACHABLE);
                    if(probe.data.motd.getString().isBlank())probe.data.motd=Component.literal("连接超时");
                }
            }
        }
    }
    private static void markReady(ServerData data){data.setState(data.protocol==SharedConstants.getCurrentVersion().getProtocolVersion()?ServerData.State.SUCCESSFUL:ServerData.State.INCOMPATIBLE);}
    // Serialize icon persistence with menu edits on the client thread. A queued whole-record
    // background save could otherwise restore a removed/edited entry from a stale servers.dat.
    private void saveIcon(ServerData data){
        ServerList list=new ServerList(mc);list.load();
        for(int i=0;i<list.size();i++){
            ServerData saved=list.get(i);
            if(saved.name.equals(data.name)&&saved.ip.equals(data.ip)){
                saved.setIconBytes(data.getIconBytes());list.save();return;
            }
        }
    }
    private static String plain(Component text){return text==null?"":text.getString().replaceAll("§[0-9a-fk-orA-FK-OR]","");}
    private static List<TextSpan> description(Component component){
        if(component==null)return List.of();List<TextSpan> spans=new ArrayList<>();int[] count={0};
        component.getVisualOrderText().accept((index,style,cp)->{
            if(++count[0]>512)return false;
            int color=style.getColor()==null?0xFFADB5C4:0xFF000000|style.getColor().getValue();
            String character=new String(Character.toChars(cp));
            if(!spans.isEmpty()&&spans.getLast().color()==color&&spans.getLast().bold()==style.isBold()){
                var prior=spans.removeLast();spans.add(new TextSpan(prior.text()+character,color,style.isBold()));
            }else spans.add(new TextSpan(character,color,style.isBold()));return true;
        });return List.copyOf(spans);
    }
    void reset(){for(Probe probe:probes.values())probe.cancel();probes.clear();}
    @Override public void close(){closed=true;reset();workers.shutdownNow();}
}
