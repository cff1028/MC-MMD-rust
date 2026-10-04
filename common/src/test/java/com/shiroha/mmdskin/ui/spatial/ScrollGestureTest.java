package com.shiroha.mmdskin.ui.spatial;

import com.shiroha.mmdskin.ui.spatial.lumen.input.InputState;
import com.shiroha.mmdskin.ui.spatial.lumen.input.ScrollGesture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScrollGestureTest {
    private final ScrollGesture gesture = new ScrollGesture();
    private final InputState input = new InputState();
    private void frame(float dt) { gesture.update(input, 400, 100, 400, 300, 1500, 814, dt, true, false, false); input.endFrame(); }
    private void press(float x, float y) { input.x=x; input.y=y; input.pressed=input.down=true; frame(1f/90); }
    private void release() { input.down=false; input.released=true; frame(1f/90); }

    @Test void scrollbarHasUsableHitAreaOutsideContentAndCapturesBeyondTrack() {
        press(823, 125);
        assertTrue(gesture.consumesPointer());
        input.y=500; frame(1f/90);
        assertEquals(1200,gesture.offset(),.001);
        input.y=-50; frame(1f/90);
        assertEquals(0,gesture.offset(),.001);
        release(); assertTrue(gesture.consumesPointer());
        frame(1f/90); assertFalse(gesture.consumesPointer());
    }
    @Test void trackClickJumpsButGrabbingThumbDoesNotJump() {
        press(814,300); float after=gesture.offset();
        assertTrue(after>600); release();
        press(814,100+gesture.thumbTop()+5);
        assertEquals(after,gesture.offset(),.01);
    }
    @Test void shortTapRemainsAButtonClickAndPageDragConsumesItsRelease() {
        press(450,220); input.y=224; frame(1f/90); release();
        assertFalse(gesture.consumesPointer()); assertEquals(0,gesture.offset());
        press(450,220); input.y=170; frame(1f/90);
        assertTrue(gesture.consumesPointer()); assertTrue(gesture.offset()>0);
        release(); assertTrue(gesture.consumesPointer());
    }
    @Test void existingSliderKeepsControlOfItsDrag() {
        press(450,220); gesture.captureControl(); input.x=700; input.y=190;
        gesture.update(input,400,100,400,300,1500,814,1f/90,true,true,false);
        assertEquals(0,gesture.offset()); assertFalse(gesture.consumesPointer());
    }
    @Test void flickKeepsMovingAndCanBeInterruptedByANewPress() {
        press(450,300);
        for(int i=0;i<7;i++){input.y-=15;frame(1f/90);}
        release(); float end=gesture.offset();
        for(int i=0;i<8;i++)frame(1f/90);
        assertTrue(gesture.offset()>end+15);
        press(450,200); float grabbed=gesture.offset();
        for(int i=0;i<20;i++)frame(1f/90);
        assertEquals(grabbed,gesture.offset(),.001);
    }
    @Test void overscrollIsBoundedAndSpringsBackWithoutLeavingInvalidOffsets() {
        press(450,150); input.y=1000; frame(1f/90);
        assertTrue(gesture.offset()<0 && gesture.offset()>-88);
        release(); for(int i=0;i<270;i++)frame(1f/90);
        assertEquals(0,gesture.offset(),.1);
    }
    @Test void reducedMotionDisablesElasticityAndInertia() {
        press(450,150); input.y=350;
        gesture.update(input,400,100,400,300,1500,814,1f/90,true,false,true);
        assertEquals(0,gesture.offset());
        input.down=false; input.released=true;
        gesture.update(input,400,100,400,300,1500,814,1f/90,true,false,true);
        assertEquals(0,gesture.offset());
    }
    @Test void missingRayCannotCreateHugeFlingAndResetClearsOldPageMotion() {
        press(450,300); input.y=200; frame(1f/90);
        input.x=input.y=-10000; frame(1f/90); float stopped=gesture.offset();
        input.down=false; for(int i=0;i<90;i++)frame(1f/90);
        assertEquals(stopped,gesture.offset(),.001);
        gesture.reset(); assertEquals(0,gesture.offset()); assertFalse(gesture.consumesPointer());
    }
    @Test void inertiaAndSpringAgreeAcrossRefreshRates() {
        assertEquals(simulate(72),simulate(144),1.5);
    }
    private static float simulate(int hz) {
        var state=new ScrollGesture(); var in=new InputState(); in.x=450; in.y=340; in.pressed=in.down=true;
        state.update(in,400,100,400,300,1500,814,0,true,false,false); in.endFrame();
        int count=hz/4;
        for(int i=0;i<count;i++){in.y-=600f/hz;state.update(in,400,100,400,300,1500,814,1f/hz,true,false,false);}
        in.down=false; in.released=true; state.update(in,400,100,400,300,1500,814,0,true,false,false); in.endFrame();
        for(int i=0;i<hz;i++)state.update(in,400,100,400,300,1500,814,1f/hz,true,false,false);
        return state.offset();
    }
}
