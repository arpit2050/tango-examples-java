/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.projecttango.examples.java.floorplan;

import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoPoseData;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import org.rajawali3d.Object3D;
import org.rajawali3d.lights.DirectionalLight;
import org.rajawali3d.materials.Material;
import org.rajawali3d.materials.methods.DiffuseMethod;
import org.rajawali3d.materials.methods.SpecularMethod;
import org.rajawali3d.materials.textures.ATexture;
import org.rajawali3d.materials.textures.StreamingTexture;
import org.rajawali3d.materials.textures.Texture;
import org.rajawali3d.math.Matrix4;
import org.rajawali3d.math.Quaternion;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.primitives.Line3D;
import org.rajawali3d.primitives.Plane;
import org.rajawali3d.primitives.ScreenQuad;
import org.rajawali3d.renderer.Renderer;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;

import javax.microedition.khronos.opengles.GL10;

/**
 * Very simple augmented reality example which displays cubes fixed in place for every
 * WallMeasurement and a continuous line for the perimeter of the floor plan.
 * Each time the user clicks on the screen, a cube is placed flush with the surface detected
 * using the point cloud data at the position clicked.
 */
public class FloorplanRenderer extends Renderer {
    private static final float CUBE_SIDE_LENGTH = 0.3f;
    private static final String TAG = FloorplanRenderer.class.getSimpleName();

    private float[] textureCoords0 = new float[]{0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0.0F};
    private float[] textureCoords270 = new float[]{1.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F};
    private float[] textureCoords180 = new float[]{1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F};
    private float[] textureCoords90 = new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F};

    private List<Pose> mNewPoseList = new ArrayList<Pose>();
    private boolean mObjectPoseUpdated = false;
    private boolean mPlanUpdated = false;
    private Material mPlaneMaterial;
    private Object3D mPlanLine = null;
    private Stack<Vector3> mPlanPoints;
    private List<Object3D> mMeasurementObjectList = new ArrayList<Object3D>();

    // Augmented reality related fields
    private ATexture mTangoCameraTexture;
    private boolean mSceneCameraConfigured;

    private ScreenQuad mBackgroundQuad;

    /**
     * Small utility class to hold a position and orientation pair.
     */
    class Pose {
        public Pose(Vector3 p, Quaternion q) {
            position = p;
            orientation = q;
        }

        public Quaternion orientation;
        public Vector3 position;
    }

    public FloorplanRenderer(Context context) {
        super(context);
    }

    @Override
    protected void initScene() {
        // Create a quad covering the whole background and assign a texture to it where the
        // Tango color camera contents will be rendered.
        if (mBackgroundQuad == null) {
            mBackgroundQuad = new ScreenQuad();
            mBackgroundQuad.getGeometry().setTextureCoords(textureCoords0);
        }
        Material tangoCameraMaterial = new Material();
        tangoCameraMaterial.setColorInfluence(0);
        // We need to use Rajawali's {@code StreamingTexture} since it sets up the texture
        // for GL_TEXTURE_EXTERNAL_OES rendering
        mTangoCameraTexture =
                new StreamingTexture("camera", (StreamingTexture.ISurfaceListener) null);
        try {
            tangoCameraMaterial.addTexture(mTangoCameraTexture);
            mBackgroundQuad.setMaterial(tangoCameraMaterial);
        } catch (ATexture.TextureException e) {
            Log.e(TAG, "Exception creating texture for RGB camera contents", e);
        }
        getCurrentScene().addChildAt(mBackgroundQuad, 0);

        // Add a directional light in an arbitrary direction.
        DirectionalLight light = new DirectionalLight(1, 0.2, -1);
        light.setColor(1, 1, 1);
        light.setPower(0.8f);
        light.setPosition(3, 2, 4);
        getCurrentScene().addLight(light);

        // Set-up a material.
        mPlaneMaterial = new Material();
        mPlaneMaterial.enableLighting(true);
        mPlaneMaterial.setDiffuseMethod(new DiffuseMethod.Lambert());
        mPlaneMaterial.setSpecularMethod(new SpecularMethod.Phong());
        mPlaneMaterial.setColor(0xff009900);
        mPlaneMaterial.setColorInfluence(0.5f);
        try {
            Texture t = new Texture("wall", R.drawable.wall);
            mPlaneMaterial.addTexture(t);
        } catch (ATexture.TextureException e) {
            e.printStackTrace();
        }
    }

    /**
     * Update background texture's UV coordinates when device orientation is changed. i.e change
     * between landscape and portrait mode.
     * This must be run in the OpenGL thread.
     */
    public void updateColorCameraTextureUvGlThread(int rotation) {
        if (mBackgroundQuad == null) {
            mBackgroundQuad = new ScreenQuad();
        }

        switch (rotation) {
            case Surface.ROTATION_90:
                mBackgroundQuad.getGeometry().setTextureCoords(textureCoords90, true);
                break;
            case Surface.ROTATION_180:
                mBackgroundQuad.getGeometry().setTextureCoords(textureCoords180, true);
                break;
            case Surface.ROTATION_270:
                mBackgroundQuad.getGeometry().setTextureCoords(textureCoords270, true);
                break;
            default:
                mBackgroundQuad.getGeometry().setTextureCoords(textureCoords0, true);
                break;
        }
        mBackgroundQuad.getGeometry().reload();
    }

    @Override
    protected void onRender(long elapsedRealTime, double deltaTime) {
        // Update the AR object if necessary
        // Synchronize against concurrent access with the setter below.
        synchronized (this) {
            if (mObjectPoseUpdated) {
                Iterator<Pose> poseIterator = mNewPoseList.iterator();
                Object3D object3D;
                while (poseIterator.hasNext()) {
                    Pose pose = poseIterator.next();
                    object3D = new Plane(CUBE_SIDE_LENGTH, CUBE_SIDE_LENGTH, 2, 2);
                    object3D.setMaterial(mPlaneMaterial);
                    // Place the 3D object in the location of the detected plane.
                    object3D.setPosition(pose.position);
                    object3D.rotate(pose.orientation);

                    getCurrentScene().addChild(object3D);
                    mMeasurementObjectList.add(object3D);
                    poseIterator.remove();
                }
                mObjectPoseUpdated = false;
            }

            if (mPlanUpdated) {
                if (mPlanLine != null) {
                    // Remove the old line.
                    getCurrentScene().removeChild(mPlanLine);
                }
                if (mPlanPoints.size() > 1) {
                    // Create a line with the points of the plan perimeter.
                    mPlanLine = new Line3D(mPlanPoints, 20, Color.RED);
                    Material m = new Material();
                    m.setColor(Color.RED);
                    mPlanLine.setMaterial(m);
                    getCurrentScene().addChild(mPlanLine);
                }
                mPlanUpdated = false;
            }
        }

        super.onRender(elapsedRealTime, deltaTime);
    }

    /**
     * Update the scene camera based on the provided pose in Tango start of service frame.
     * The camera pose should match the pose of the camera color at the time the last rendered RGB
     * frame, which can be retrieved with this.getTimestamp();
     * <p/>
     * NOTE: This must be called from the OpenGL render thread - it is not thread safe.
     */
    public void updateRenderCameraPose(TangoPoseData cameraPose) {
        float[] rotation = cameraPose.getRotationAsFloats();
        float[] translation = cameraPose.getTranslationAsFloats();
        Quaternion quaternion = new Quaternion(rotation[3], rotation[0], rotation[1], rotation[2]);
        // Conjugating the Quaternion is need because Rajawali uses left handed convention for
        // quaternions.
        getCurrentCamera().setRotation(quaternion.conjugate());
        getCurrentCamera().setPosition(translation[0], translation[1], translation[2]);
    }

    /**
     * It returns the ID currently assigned to the texture where the Tango color camera contents
     * should be rendered.
     * NOTE: This must be called from the OpenGL render thread - it is not thread safe.
     */
    public int getTextureId() {
        return mTangoCameraTexture == null ? -1 : mTangoCameraTexture.getTextureId();
    }

    /**
     * We need to override this method to mark the camera for re-configuration (set proper
     * projection matrix) since it will be reset by Rajawali on surface changes.
     */
    @Override
    public void onRenderSurfaceSizeChanged(GL10 gl, int width, int height) {
        super.onRenderSurfaceSizeChanged(gl, width, height);
        mSceneCameraConfigured = false;
    }

    public boolean isSceneCameraConfigured() {
        return mSceneCameraConfigured;
    }

    /**
     * Sets the projection matrix for the scene camera to match the parameters of the color camera,
     * provided by the {@code TangoCameraIntrinsics}.
     */
    public void setProjectionMatrix(float[] matrix) {
        getCurrentCamera().setProjectionMatrix(new Matrix4(matrix));
    }

    @Override
    public void onOffsetsChanged(float xOffset, float yOffset,
                                 float xOffsetStep, float yOffsetStep,
                                 int xPixelOffset, int yPixelOffset) {
    }

    @Override
    public void onTouchEvent(MotionEvent event) {

    }

    /**
     * Add a new WallMeasurement.
     * A new cube will be added at the plane position and orientation to represent the measurement.
     */
    public synchronized void addWallMeasurement(WallMeasurement wallMeasurement) {
        float[] openGlTWall = wallMeasurement.getPlaneTransform();
        Matrix4 openGlTWallMatrix = new Matrix4(openGlTWall);
        mNewPoseList.add(new Pose(openGlTWallMatrix.getTranslation(),
                new Quaternion().fromMatrix(openGlTWallMatrix)));
        mObjectPoseUpdated = true;
    }

    /**
     * Update the perimeter line with the new floor plan.
     */
    public synchronized void updatePlan(Floorplan plan) {
        Stack<Vector3> points = new Stack<Vector3>();
        for (float[] point : plan.getPlanPoints()) {
            points.add(new Vector3(point[0], 0, point[2]));
        }
        mPlanPoints = points;
        mPlanUpdated = true;
    }

    /**
     * Remove all the measurements from the Scene.
     */
    public synchronized void removeMeasurements() {
        for (Object3D object3D : mMeasurementObjectList) {
            getCurrentScene().removeChild(object3D);
        }
    }
}
