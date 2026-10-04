package com.shiroha.mmdskin.ui.spatial.backend;

import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.asset.catalog.ModelInfo;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.config.PathConstants;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import static com.shiroha.mmdskin.ui.spatial.backend.AvatarPortraitRenderer.*;

/** Owns a private Rust model for one background thumbnail; never borrows a playing avatar's handle. */
final class MmdPortraitMesh {
    static Mesh load(ModelInfo info, ModelConfigData config) {
        NativeFunc api=NativeFunc.GetInst();
        long model=info.isVRM()?api.LoadModelVRM(info.getModelFilePath(),info.getFolderPath(),1)
                :info.isPMD()?api.LoadModelPMD(info.getModelFilePath(),info.getFolderPath(),1)
                :api.LoadModelPMX(info.getModelFilePath(),info.getFolderPath(),1);
        if(model==0)throw new IllegalArgumentException("Cannot load portrait model");
        long animation=0;
        try{
            api.SetPhysicsEnabled(model,false);api.SetAutoBlinkEnabled(model,false);api.SetEyeTrackingEnabled(model,false);
            Path idle=Path.of(info.getFolderPath(),"anims","idle.vmd");
            if(!Files.isRegularFile(idle))idle=PathConstants.getDefaultAnimDir().toPath().resolve("idle.vmd");
            if(Files.isRegularFile(idle)){
                animation=api.LoadAnimation(model,idle.toString());
                if(animation!=0){api.ChangeModelAnim(model,animation,0);api.SeekLayer(model,0,0);}
            }
            api.UpdateModel(model,0);
            int count=bounded(api.GetVertexCount(model),1,2_000_000), indexCount=bounded(api.GetIndexCount(model),3,9_000_000);
            float[] positions=floats(api.GetPoss(model),count*3), normals=floats(api.GetNormals(model),count*3), uv=floats(api.GetUVs(model),count*2);
            for(int i=1;i<uv.length;i+=2)uv[i]=1-uv[i];
            int size=(int)api.GetIndexElementSize(model);if(size!=1&&size!=2&&size!=4)throw new IllegalArgumentException("Invalid index size");
            long address=api.GetIndices(model);if(address==0)throw new IllegalArgumentException("Missing model indices");
            var data=MemoryUtil.memByteBuffer(address,indexCount*size).order(ByteOrder.nativeOrder());int[] indices=new int[indexCount];
            for(int i=0;i<indexCount;i++)indices[i]=size==1?data.get(i)&255:size==2?data.getShort(i*2)&65535:data.getInt(i*4);
            int materialCount=bounded(api.GetMaterialCount(model),1,16384),submeshes=bounded(api.GetSubMeshCount(model),1,16384);
            Material[] materials=new Material[materialCount];Map<String,Texture> textures=new HashMap<>();
            for(int i=0;i<materialCount;i++){
                if(config.hiddenMaterials!=null&&config.hiddenMaterials.contains(i)){materials[i]=new Material(null,1,1,1,0);continue;}
                float[] color=floats(api.GetMaterialDiffuse(model,i),4);
                String path=api.GetMaterialTex(model,i);
                Texture texture=null;
                if(path!=null&&!path.isBlank()){
                    if(!textures.containsKey(path)&&textures.size()>=64)throw new IllegalArgumentException("Portrait texture budget exceeded");
                    if(!textures.containsKey(path))textures.put(path,texture(api,path));
                    texture=textures.get(path);
                }
                materials[i]=new Material(texture,finite(color[0]),finite(color[1]),finite(color[2]),Math.clamp(api.GetMaterialAlpha(model,i),0,1));
            }
            List<Part> parts=new ArrayList<>();
            for(int i=0;i<submeshes;i++){
                int material=api.GetSubMeshMaterialID(model,i),start=api.GetSubMeshBeginIndex(model,i),length=api.GetSubMeshVertexCount(model,i);
                if(material<0||material>=materialCount||start<0||length<0||start>indexCount-length)throw new IllegalArgumentException("Invalid model submesh");
                if(config.hiddenMaterials!=null&&config.hiddenMaterials.contains(material))continue;
                parts.add(new Part(start,length,materials[material]));
            }
            return new Mesh(positions,normals,uv,indices,List.copyOf(parts));
        }finally{api.DeleteModel(model);if(animation!=0)api.DeleteAnimation(animation);}
    }
    private static float finite(float value){return Float.isFinite(value)?Math.clamp(value,0,2):1;}
    private static int bounded(long value,int min,int max){if(value<min||value>max)throw new IllegalArgumentException("Portrait geometry exceeds limits");return (int)value;}
    private static float[] floats(long pointer,int count){if(pointer==0)throw new IllegalArgumentException("Missing model data");float[] values=new float[count];MemoryUtil.memFloatBuffer(pointer,count).get(values);return values;}
    private static Texture texture(NativeFunc api,String path){
        long handle=api.LoadTexture(path);if(handle==0)return null;
        try{
            int width=bounded(api.GetTextureX(handle),1,16384),height=bounded(api.GetTextureY(handle),1,16384);
            int channels=api.TextureHasAlpha(handle)?4:3;
            long pointer=api.GetTextureData(handle);if(pointer==0||((long)width*height*channels)>512L*1024*1024)return null;
            var bytes=MemoryUtil.memByteBuffer(pointer,width*height*channels);
            float scale=Math.min(1,512f/Math.max(width,height));int w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));int[] pixels=new int[w*h];
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int i=((height-1-y*height/h)*width+x*width/w)*channels;
                pixels[y*w+x]=((channels==4?bytes.get(i+3)&255:255)<<24)|(bytes.get(i)&255)<<16|(bytes.get(i+1)&255)<<8|bytes.get(i+2)&255;
            }
            return new Texture(w,h,pixels);
        }finally{api.DeleteTexture(handle);}
    }
}
