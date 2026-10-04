package com.shiroha.mmdskin.compat.vr.pointer;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VivecraftPointerBridgeTest {
    private static void near(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x,actual.x,1e-5);
        assertEquals(expected.y,actual.y,1e-5);
        assertEquals(expected.z,actual.z,1e-5);
    }

    @Test void renderedGuiUsesAppliedScaleAndRotatedLocalOffsetExactlyOnce() {
        Vec3 origin=new Vec3(12345.125,64,-9000.5);
        Vector3f roomPos=new Vector3f(.2f,1.6f,-1.2f), offset=new Vector3f(.15f,.32f,.07f);
        Matrix4f roomRot=new Matrix4f().rotateY(.23f).rotateX(-.7f);
        float yaw=.9f, worldScale=2.5f, guiScale=.4f;
        var plane=VivecraftPointerBridge.roomPlane("gui",roomPos,roomRot,offset,guiScale,720.0/1280,origin,yaw,worldScale);
        assertNotNull(plane);
        // Mirror GuiHandler.applyGUIModelView: T(roomToWorld(pos))*R(worldYaw)*R(room)*T(local*worldScale)*S(gui*worldScale).
        Matrix4f rotation=new Matrix4f().rotationY(yaw).mul(roomRot);
        Vec3 position=VivecraftPointerBridge.roomToWorld(roomPos,origin,yaw,worldScale);
        Vector3f translated=rotation.transformDirection(new Vector3f(offset).mul(worldScale));
        near(position.add(translated.x,translated.y,translated.z),plane.center());
        assertEquals(1.5*guiScale*worldScale,plane.width(),1e-6);
        assertEquals(plane.width()*720/1280,plane.height(),1e-6);
        assertEquals(1,plane.normal().length(),1e-6);
        assertEquals(0,plane.right().dot(plane.up()),1e-6);
    }

    @Test void radialAndFlatKeyboardUseSameVisibleQuadAspectAtAnyWorldScale() {
        for(float scale:new float[]{.1f,1,8}) {
            var plane=VivecraftPointerBridge.roomPlane("radial",new Vector3f(1,2,3),new Matrix4f(),null,
                    2f,900.0/1600,Vec3.ZERO,0,scale);
            assertNotNull(plane);
            near(new Vec3(scale,2*scale,3*scale),plane.center());
            assertEquals(3*scale,plane.width(),1e-6);
            assertEquals(plane.width()*900/1600,plane.height(),1e-6);
        }
    }

    @Test void physicalHandSelectionFollowsReverseHandsAndRenderPassesExcludeScopesAndCameras() {
        assertFalse(VivecraftPointerBridge.physicalLeft(0,false));
        assertTrue(VivecraftPointerBridge.physicalLeft(1,false));
        assertTrue(VivecraftPointerBridge.physicalLeft(0,true));
        assertFalse(VivecraftPointerBridge.physicalLeft(1,true));
        for(String pass:new String[]{"LEFT","RIGHT","CENTER"})assertTrue(VivecraftPointerBridge.isEyePass(pass));
        for(String pass:new String[]{"THIRD","CAMERA","SCOPEL","SCOPER","VANILLA"})assertFalse(VivecraftPointerBridge.isEyePass(pass));
    }

    @Test void absentOrInvalidRenderedGeometryDoesNotInventAPlane() {
        assertNull(VivecraftPointerBridge.roomPlane("gui",null,new Matrix4f(),null,1,1,Vec3.ZERO,0,1));
        assertNull(VivecraftPointerBridge.roomPlane("gui",new Vector3f(),new Matrix4f(),null,Float.NaN,1,Vec3.ZERO,0,1));
        assertNull(VivecraftPointerBridge.roomPlane("gui",new Vector3f(),new Matrix4f(),null,1,0,Vec3.ZERO,0,1));
    }
}
