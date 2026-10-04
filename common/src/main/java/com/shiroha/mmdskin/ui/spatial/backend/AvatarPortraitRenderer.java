package com.shiroha.mmdskin.ui.spatial.backend;

import com.shiroha.mmdskin.ui.spatial.lumen.model.MenuBackend.ImageData;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;

/** Worker-only orthographic thumbnail rasterizer. No game entities, live poses or GL context are touched. */
final class AvatarPortraitRenderer {
    static final int WIDTH = 320, HEIGHT = 448, SCALE = 2;
    record Texture(int width, int height, int[] argb) {
        int sample(float u, float v) {
            int x = Math.clamp((int)((u - Math.floor(u)) * width), 0, width - 1);
            int y = Math.clamp((int)((v - Math.floor(v)) * height), 0, height - 1);
            return argb[y * width + x];
        }
    }
    record Material(Texture texture, float r, float g, float b, float alpha) {}
    record Part(int start, int count, Material material) {}
    record Mesh(float[] positions, float[] normals, float[] uv, int[] indices, List<Part> parts) {}
    private AvatarPortraitRenderer() {}

    static ImageData render(Mesh mesh) {
        int w = WIDTH * SCALE, h = HEIGHT * SCALE, count = mesh.positions.length / 3;
        float[] p = new float[count * 3], light = new float[count];
        boolean[] used=new boolean[count];
        for(Part part:mesh.parts)if(part.material.alpha>.001f)for(int i=part.start;i<part.start+part.count;i++){
            int vertex=mesh.indices[i];if(vertex<0||vertex>=count)throw new IllegalArgumentException("Invalid portrait index");used[vertex]=true;
        }
        Matrix4f angle = new Matrix4f().rotateX((float)Math.toRadians(14)).rotateY((float)Math.toRadians(-28));
        Vector3f v = new Vector3f();
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        for (int i = 0; i < count; i++) {
            angle.transformPosition(v.set(mesh.positions[i*3], mesh.positions[i*3+1], mesh.positions[i*3+2]));
            if (!v.isFinite()) throw new IllegalArgumentException("Invalid portrait geometry");
            p[i*3] = v.x; p[i*3+1] = v.y; p[i*3+2] = v.z;
            if(used[i]){minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x); minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);}
            angle.transformDirection(v.set(mesh.normals[i*3], mesh.normals[i*3+1], mesh.normals[i*3+2]));
            if (v.isFinite() && v.lengthSquared() > .000001f) v.normalize(); else v.set(0, 1, 0);
            light[i] = .70f + .30f * Math.max(0, v.dot(-.4f, .7f, .59f));
        }
        if (maxY - minY < .0001f || maxX - minX < .0001f) throw new IllegalArgumentException("Empty portrait geometry");
        float scale = Math.min(w * .86f / (maxX-minX), h * .91f / (maxY-minY));
        for (int i = 0; i < count; i++) { p[i*3] = w*.5f + (p[i*3]-(minX+maxX)*.5f)*scale; p[i*3+1] = h*.5f - (p[i*3+1]-(minY+maxY)*.5f)*scale; }
        float[] depth = new float[w*h], rgb = new float[w*h*3], accum = new float[w*h*4], reveal = new float[w*h];
        Arrays.fill(depth, -Float.MAX_VALUE); Arrays.fill(reveal, 1);
        // Alpha-cutout surfaces populate depth first. Translucent layers use weighted accumulation.
        for (int pass = 0; pass < 2; pass++) for (Part part : mesh.parts) {
            Material m = part.material;
            if (m.alpha <= .001f) continue;
            for (int at = part.start; at + 2 < part.start + part.count; at += 3) {
                if ((at & 1023) == 0 && Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                int a = mesh.indices[at], b = mesh.indices[at+1], c = mesh.indices[at+2];
                if (a<0 || b<0 || c<0 || a>=count || b>=count || c>=count) throw new IllegalArgumentException("Invalid portrait index");
                float ax=p[a*3], ay=p[a*3+1], bx=p[b*3], by=p[b*3+1], cx=p[c*3], cy=p[c*3+1];
                float area=(bx-ax)*(cy-ay)-(by-ay)*(cx-ax);
                if (Math.abs(area)<.0001f) continue;
                int x0=Math.clamp((int)Math.floor(Math.min(ax,Math.min(bx,cx))),0,w-1), x1=Math.clamp((int)Math.ceil(Math.max(ax,Math.max(bx,cx))),0,w-1);
                int y0=Math.clamp((int)Math.floor(Math.min(ay,Math.min(by,cy))),0,h-1), y1=Math.clamp((int)Math.ceil(Math.max(ay,Math.max(by,cy))),0,h-1);
                for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++) {
                    float u=((bx-x-.5f)*(cy-y-.5f)-(by-y-.5f)*(cx-x-.5f))/area;
                    float v0=((cx-x-.5f)*(ay-y-.5f)-(cy-y-.5f)*(ax-x-.5f))/area, q=1-u-v0;
                    if(u<0||v0<0||q<0)continue;
                    int pixel=y*w+x;float z=u*p[a*3+2]+v0*p[b*3+2]+q*p[c*3+2];
                    if(z<depth[pixel]-.00001f)continue;
                    int tex=m.texture==null?0xFFFFFFFF:m.texture.sample(u*mesh.uv[a*2]+v0*mesh.uv[b*2]+q*mesh.uv[c*2],u*mesh.uv[a*2+1]+v0*mesh.uv[b*2+1]+q*mesh.uv[c*2+1]);
                    float alpha=(tex>>>24)/255f*m.alpha;
                    if(alpha<.01f || (pass==0)!=(alpha>=.98f))continue;
                    float l=u*light[a]+v0*light[b]+q*light[c];
                    float r=(tex>>>16&255)/255f*m.r*l,g=(tex>>>8&255)/255f*m.g*l,bl=(tex&255)/255f*m.b*l;
                    if(pass==0){depth[pixel]=z;rgb[pixel*3]=r;rgb[pixel*3+1]=g;rgb[pixel*3+2]=bl;}
                    else{accum[pixel*4]+=r*alpha;accum[pixel*4+1]+=g*alpha;accum[pixel*4+2]+=bl*alpha;accum[pixel*4+3]+=alpha;reveal[pixel]*=1-alpha;}
                }
            }
        }
        byte[] output=new byte[WIDTH*HEIGHT*4];int visible=0;
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
            float r=0,g=0,b=0,alpha=0;
            for(int sy=0;sy<SCALE;sy++)for(int sx=0;sx<SCALE;sx++){
                int i=(y*SCALE+sy)*w+x*SCALE+sx;float opaque=depth[i]>-Float.MAX_VALUE?1:0,trans=1-reveal[i],weight=accum[i*4+3];
                alpha+=trans+opaque*reveal[i];
                r+=rgb[i*3]*opaque*reveal[i]+(weight>0?accum[i*4]/weight*trans:0);
                g+=rgb[i*3+1]*opaque*reveal[i]+(weight>0?accum[i*4+1]/weight*trans:0);
                b+=rgb[i*3+2]*opaque*reveal[i]+(weight>0?accum[i*4+2]/weight*trans:0);
            }
            int i=(y*WIDTH+x)*4;
            if(alpha>0){visible++;output[i]=(byte)Math.clamp(Math.round(r/alpha*255),0,255);output[i+1]=(byte)Math.clamp(Math.round(g/alpha*255),0,255);output[i+2]=(byte)Math.clamp(Math.round(b/alpha*255),0,255);}
            output[i+3]=(byte)Math.clamp(Math.round(alpha/(SCALE*SCALE)*255),0,255);
        }
        if(visible<32)throw new IllegalArgumentException("No visible portrait surfaces");
        return new ImageData("portrait:"+MenuImagePixels.contentKey(output),WIDTH,HEIGHT,output);
    }

    /** Classic/slim, modern/legacy UVs and independently enabled second-layer skin parts. */
    static Mesh skin(Texture skin, boolean slim, int layers) {
        Builder b=new Builder(new Material(skin,1,1,1,1));boolean legacy=skin.height==skin.width/2;
        b.box(0,24,0,8,8,8,0,0,0,0,false);
        b.box(0,12,0,8,12,4,16,16,0,0,false);
        b.box(-2,12,0,4,12,4,0,16,-.28f,0,false,true);
        b.box(2,12,0,4,12,4,legacy?0:16,legacy?16:48,.28f,0,legacy,true);
        int arm=slim?3:4;
        b.box(-4-arm*.5f,24,0,arm,12,4,40,16,.20f,0,false,true);
        b.box(4+arm*.5f,24,0,arm,12,4,legacy?40:32,legacy?16:48,-.20f,0,legacy,true);
        if((layers&64)!=0)b.box(0,24,0,8,8,8,32,0,0,.5f,false);
        if(!legacy){
            if((layers&2)!=0)b.box(0,12,0,8,12,4,16,32,0,.25f,false);
            if((layers&16)!=0)b.box(2,12,0,4,12,4,0,48,.28f,.25f,false,true);
            if((layers&32)!=0)b.box(-2,12,0,4,12,4,0,32,-.28f,.25f,false,true);
            if((layers&4)!=0)b.box(4+arm*.5f,24,0,arm,12,4,48,48,-.20f,.25f,false,true);
            if((layers&8)!=0)b.box(-4-arm*.5f,24,0,arm,12,4,40,32,.20f,.25f,false,true);
        }
        return b.mesh();
    }
    private static final class Builder {
        final List<Float> p=new ArrayList<>(),n=new ArrayList<>(),uv=new ArrayList<>();final List<Integer> indices=new ArrayList<>();final Material material;
        Builder(Material material){this.material=material;}
        void box(float x,float y,float z,int w,int h,int d,int u,int v,float swing,float inflate,boolean mirror){box(x,y,z,w,h,d,u,v,swing,inflate,mirror,false);}
        void box(float x,float y,float z,int w,int h,int d,int u,int v,float swing,float inflate,boolean mirror,boolean hanging){
            Matrix4f transform=new Matrix4f().translation(x,y,z).rotateX(swing);
            float x0=-w*.5f-inflate,x1=w*.5f+inflate,y0=(hanging?-h:0)-inflate,y1=(hanging?0:h)+inflate,z0=-d*.5f-inflate,z1=d*.5f+inflate;
            face(transform,new float[]{x0,y1,z1,x1,y1,z1,x1,y0,z1,x0,y0,z1},0,0,1,u+d,v+d,w,h,mirror);
            face(transform,new float[]{x1,y1,z0,x0,y1,z0,x0,y0,z0,x1,y0,z0},0,0,-1,u+d+w+d,v+d,w,h,mirror);
            face(transform,new float[]{x0,y1,z0,x0,y1,z1,x0,y0,z1,x0,y0,z0},-1,0,0,u,v+d,d,h,mirror);
            face(transform,new float[]{x1,y1,z1,x1,y1,z0,x1,y0,z0,x1,y0,z1},1,0,0,u+d+w,v+d,d,h,mirror);
            face(transform,new float[]{x0,y1,z0,x1,y1,z0,x1,y1,z1,x0,y1,z1},0,1,0,u+d,v,w,d,mirror);
            face(transform,new float[]{x0,y0,z1,x1,y0,z1,x1,y0,z0,x0,y0,z0},0,-1,0,u+d+w,v,w,d,mirror);
        }
        void face(Matrix4f m,float[] vertices,float nx,float ny,float nz,int u,int v,int w,int h,boolean mirror){
            int first=p.size()/3;Vector3f normal=m.transformDirection(nx,ny,nz,new Vector3f());
            float denominatorY=material.texture.height==material.texture.width/2?32:64;
            for(int i=0;i<4;i++){
                Vector3f pos=m.transformPosition(vertices[i*3],vertices[i*3+1],vertices[i*3+2],new Vector3f());
                p.add(pos.x);p.add(pos.y);p.add(pos.z);n.add(normal.x);n.add(normal.y);n.add(normal.z);
                boolean right=i==1||i==2;uv.add((u+((right^mirror)?w:0)+((right^mirror)?-.001f:.001f))/64f);
                uv.add((v+(i>=2?h:0)+(i>=2?-.001f:.001f))/denominatorY);
            }
            for(int i:new int[]{0,2,1,0,3,2})indices.add(first+i);
        }
        Mesh mesh(){return new Mesh(floats(p),floats(n),floats(uv),indices.stream().mapToInt(Integer::intValue).toArray(),List.of(new Part(0,indices.size(),material)));}
        float[] floats(List<Float> values){float[] result=new float[values.size()];for(int i=0;i<result.length;i++)result[i]=values.get(i);return result;}
    }
}
