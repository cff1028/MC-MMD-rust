package com.shiroha.mmdskin.ui.spatial.backend;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.shiroha.mmdskin.ui.spatial.backend.AvatarPortraitRenderer.*;

class AvatarPortraitRendererTest {
    @Test void skinUsesFullBodyAndKeepsTransparentMarginWithAntialiasing() {
        int[] pixels=new int[64*64];Arrays.fill(pixels,0xFFFFCC99);
        for(int y=20;y<32;y++)for(int x=20;x<28;x++)pixels[y*64+x]=0xFF00FFFF;
        for(int y=20;y<32;y++)for(int x=4;x<8;x++)pixels[y*64+x]=0xFF0000FF;
        var image=render(skin(new Texture(64,64,pixels),false,0));
        assertEquals(WIDTH,image.width());assertEquals(HEIGHT,image.height());
        int minX=WIDTH,maxX=0,minY=HEIGHT,maxY=0,soft=0;
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++){
            int a=image.rgba()[(y*WIDTH+x)*4+3]&255;if(a==0)continue;
            minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
            if(a<255)soft++;
        }
        assertTrue(minX>0&&maxX<WIDTH-1&&minY>0&&maxY<HEIGHT-1);
        assertTrue(maxY-minY>HEIGHT*.85);assertTrue(soft>100);
    }
    @Test void slimGeometryAndOuterLayersChangeTheCachedPixels() {
        int[] pixels=new int[64*64];Arrays.fill(pixels,0xFFFF0000);
        for(int y=0;y<16;y++)for(int x=32;x<64;x++)pixels[y*64+x]=0xFF00FF00;
        var texture=new Texture(64,64,pixels);
        var base=render(skin(texture,false,0));var hat=render(skin(texture,false,64));var slim=render(skin(texture,true,0));
        assertNotEquals(base.key(),hat.key());assertNotEquals(base.key(),slim.key());
        assertEquals(base.key(),render(skin(texture,false,0)).key());
    }
    @Test void hiddenDistantGeometryDoesNotShrinkVisibleModel() {
        float[] pos={-1,0,0,1,0,0,0,2,0,10000,10000,10000};float[] normals={0,0,1,0,0,1,0,0,1,0,0,1};
        var material=new Material(null,1,1,1,1);
        var first=render(new Mesh(pos,normals,new float[8],new int[]{0,1,2},List.of(new Part(0,3,material))));
        var second=render(new Mesh(Arrays.copyOf(pos,9),Arrays.copyOf(normals,9),new float[6],new int[]{0,1,2},List.of(new Part(0,3,material))));
        assertArrayEquals(first.rgba(),second.rgba());
    }
    @Test void legacySkinAndTranslucentMaterialsRemainRenderable() {
        int[] legacy=new int[64*32];Arrays.fill(legacy,0xFFB08050);
        assertNotNull(render(skin(new Texture(64,32,legacy),false,0)));
        float[] p={-1,0,0,1,0,0,0,2,0},n={0,0,1,0,0,1,0,0,1};
        var result=render(new Mesh(p,n,new float[6],new int[]{0,1,2},List.of(new Part(0,3,new Material(null,1,0,0,.5f)))));
        int maxAlpha=0;for(int i=3;i<result.rgba().length;i+=4)maxAlpha=Math.max(maxAlpha,result.rgba()[i]&255);
        assertEquals(128,maxAlpha);
        assertThrows(IllegalArgumentException.class,()->render(new Mesh(p,n,new float[6],new int[]{0,1,10},List.of(new Part(0,3,new Material(null,1,1,1,1))))));
    }
}
