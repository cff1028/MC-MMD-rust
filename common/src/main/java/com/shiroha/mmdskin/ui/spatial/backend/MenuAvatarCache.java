package com.shiroha.mmdskin.ui.spatial.backend;

import com.shiroha.mmdskin.asset.catalog.ModelInfo;
import com.shiroha.mmdskin.config.*;
import com.shiroha.mmdskin.ui.spatial.lumen.model.MenuBackend.ImageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.player.PlayerModelPart;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;

/** Bounded memory/disk cache. File scanning, Rust loads, rasterization and PNG I/O all run on one worker. */
final class MenuAvatarCache implements AutoCloseable {
    private final Minecraft mc=Minecraft.getInstance();
    private final Path directory=mc.gameDirectory.toPath().resolve("cache/mmdskin/avatar-portraits-v2");
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"MMD-AvatarPortrait");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private volatile boolean closed;
    private PlayerSkin downloaded;
    private UUID account;
    private long nextSkin,nextProfile;
    private volatile SkinSource skin;
    private record SkinSource(String identity,boolean slim,int layers,Supplier<AvatarPortraitRenderer.Texture> pixels) {}
    private static final class Entry {
        volatile ImageData image;volatile String stamp="";volatile boolean active=true;long checkAt;Future<?> job;
    }
    /** Called before entering NanoVG, never from a card's drawing callback. */
    void tickSkin() {
        long now=System.nanoTime();if(closed||now<nextSkin)return;nextSkin=now+1_000_000_000L;
        var profile=mc.getGameProfile();
        if(!Objects.equals(account,profile.getId())){account=profile.getId();downloaded=null;nextProfile=0;}
        if(now>=nextProfile){
            nextProfile=now+30_000_000_000L;UUID request=account;
            mc.getSkinManager().getOrLoad(profile).thenAcceptAsync(result->{if(!closed&&Objects.equals(account,request))result.ifPresent(value->{downloaded=value;nextSkin=0;});},mc);
        }
        PlayerSkin current=mc.player!=null?mc.player.getSkin():downloaded!=null?downloaded:DefaultPlayerSkin.get(profile);
        int mask=0;for(PlayerModelPart part:PlayerModelPart.values())if(mc.options.isModelPartEnabled(part))mask|=part.getMask();
        boolean slim=current.model()==PlayerSkin.Model.SLIM;String identity=account+":"+current.texture()+":"+slim+":"+mask;
        Supplier<AvatarPortraitRenderer.Texture> pixels;
        var texture=mc.getTextureManager().getTexture(current.texture());
        if(texture instanceof DynamicTexture dynamic&&dynamic.getPixels()!=null){
            var original=dynamic.getPixels();int width=original.getWidth(),height=original.getHeight();
            if(width<64||width>2048||height<width/2||height>width){skin=null;return;}
            int w=Math.min(width,256),h=height*w/width;int[] copy=new int[w*h];
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)copy[y*w+x]=original.getPixel(x*width/w,y*height/h);
            pixels=()->new AvatarPortraitRenderer.Texture(w,h,copy);
        }else{
            var resources=mc.getResourceManager();var location=current.texture();
            pixels=()->{try(var in=resources.open(location)){BufferedImage image=ImageIO.read(in);return skinTexture(image);}catch(Exception failure){return null;}};
        }
        skin=new SkinSource(identity,slim,mask,pixels);
    }
    ImageData image(String name) {
        if(closed||name==null||name.isBlank())return null;
        Entry entry=entries.computeIfAbsent(name,ignored->new Entry());long now=System.nanoTime();
        if((entry.job==null||entry.job.isDone())&&now>=entry.checkAt){
            entry.checkAt=now+2_000_000_000L;SkinSource snapshot=skin;
            entry.job=worker.submit(()->update(entry,name,snapshot));
        }
        while(entries.size()>32){Entry old=entries.pollFirstEntry().getValue();old.active=false;if(old.job!=null)old.job.cancel(false);}
        return entry.image;
    }
    private void update(Entry entry,String name,SkinSource skin) {
        try{
            if(closed||!entry.active)return;
            AvatarPortraitRenderer.Texture pixels=null;ModelInfo model=null;ModelConfigData config=null;String stamp;
            if(name.equals(UIConstants.DEFAULT_MODEL_NAME)){
                if(skin==null)return;
                pixels=skin.pixels.get();if(pixels==null)throw new IllegalArgumentException("Skin pixels unavailable");
                byte[] bytes=new byte[pixels.argb().length*4];java.nio.ByteBuffer.wrap(bytes).asIntBuffer().put(pixels.argb());
                stamp=MenuImagePixels.contentKey((skin.identity+":"+MenuImagePixels.contentKey(bytes)).getBytes(StandardCharsets.UTF_8));
            }else{
                model=ModelInfo.findByFolderName(name);if(model==null)throw new IllegalArgumentException("Model no longer available");
                config=ModelConfigManager.getConfig(name);
                stamp=modelStamp(model,config);
            }
            if(stamp.equals(entry.stamp))return;
            entry.stamp=stamp;entry.image=null;
            Path file=directory.resolve(stamp+".png");ImageData result=readCached(file);
            if(result==null){
                var mesh=model==null?AvatarPortraitRenderer.skin(pixels,skin.slim,skin.layers):MmdPortraitMesh.load(model,config);
                result=AvatarPortraitRenderer.render(mesh);
                if(!closed&&entry.active)writeCached(file,result);
            }
            if(!closed&&entry.active)entry.image=result;
        }catch(Exception|LinkageError failure){entry.image=null;LogManager.getLogger().debug("[Spatial UI] Avatar preview unavailable: {}",name,failure);}
    }
    private static String modelStamp(ModelInfo model,ModelConfigData config)throws Exception{
        StringBuilder stamp=new StringBuilder("portrait-v1:").append(model.getModelFilePath()).append(':').append(config.hiddenMaterials);
        Path folder=Path.of(model.getFolderPath());
        try(var files=Files.walk(folder,12)){
            for(Path path:files.filter(Files::isRegularFile).sorted().limit(20000).toList())stamp.append('\n').append(folder.relativize(path)).append(':').append(Files.size(path)).append(':').append(Files.getLastModifiedTime(path));
        }
        Path idle=PathConstants.getDefaultAnimDir().toPath().resolve("idle.vmd");
        if(Files.exists(idle))stamp.append("idle:").append(Files.size(idle)).append(':').append(Files.getLastModifiedTime(idle));
        return MenuImagePixels.contentKey(stamp.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static AvatarPortraitRenderer.Texture skinTexture(BufferedImage image){
        if(image==null||image.getWidth()<64||image.getWidth()>2048||image.getWidth()%64!=0||image.getHeight()!=image.getWidth()&&image.getHeight()!=image.getWidth()/2)return null;
        return new AvatarPortraitRenderer.Texture(image.getWidth(),image.getHeight(),image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()));
    }
    private static ImageData readCached(Path file){
        try{
            if(!Files.isRegularFile(file)||Files.size(file)>4*1024*1024)return null;
            BufferedImage image=ImageIO.read(file.toFile());if(image==null||image.getWidth()!=AvatarPortraitRenderer.WIDTH||image.getHeight()!=AvatarPortraitRenderer.HEIGHT)return null;
            return MenuImagePixels.image(image.getWidth(),image.getHeight(),image::getRGB);
        }catch(Exception failure){return null;}
    }
    private void writeCached(Path file,ImageData image){
        Path temporary=null;
        try{
            Files.createDirectories(directory);BufferedImage pixels=new BufferedImage(image.width(),image.height(),BufferedImage.TYPE_INT_ARGB);byte[] b=image.rgba();
            for(int y=0;y<image.height();y++)for(int x=0;x<image.width();x++){int i=(y*image.width()+x)*4;pixels.setRGB(x,y,(b[i+3]&255)<<24|(b[i]&255)<<16|(b[i+1]&255)<<8|b[i+2]&255);}
            temporary=Files.createTempFile(directory,"portrait-",".tmp");ImageIO.write(pixels,"png",temporary.toFile());
            Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);temporary=null;
            try(var files=Files.list(directory)){
                var cached=files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.png")).sorted(Comparator.comparingLong(MenuAvatarCache::modified).reversed()).toList();
                for(int i=128;i<cached.size();i++)Files.deleteIfExists(cached.get(i));
            }
        }catch(Exception failure){LogManager.getLogger().debug("[Spatial UI] Portrait disk cache unavailable",failure);}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
    private static long modified(Path file){try{return Files.getLastModifiedTime(file).toMillis();}catch(Exception ignored){return 0;}}
    void refresh(){nextSkin=nextProfile=0;for(Entry entry:entries.values()){entry.checkAt=0;if(entry.image==null)entry.stamp="";}}
    @Override public void close(){closed=true;for(Entry entry:entries.values()){entry.active=false;if(entry.job!=null)entry.job.cancel(false);}entries.clear();worker.shutdown();skin=null;}
}
