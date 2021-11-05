/*
 * Copyright (C) 2013 The Android Open Source Project
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
// from: https://android.googlesource.com/platform/cts/+/lollipop-release/tests/tests/media/src/android/media/cts/TextureRender.java
// blob: 4125dcfcfed6ed7fddba5b71d657dec0d433da6a
// modified: removed unused method bodies
// modified: use GL_LINEAR for GL_TEXTURE_MIN_FILTER to improve quality.
// modified: added filters
package com.linkedin.android.litr.render;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.linkedin.android.litr.codec.Frame;
import com.linkedin.android.litr.filter.GlFilter;
import com.linkedin.android.litr.filter.GlFrameRenderFilter;
import com.linkedin.android.litr.filter.video.gl.DefaultVideoFrameRenderFilter;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A renderer that uses OpenGL to draw (and transform) decoder's output frame onto encoder's input frame. Both decoder
 * and encoder are expected to be using {@link Surface}.
 */
public class GlThumbnailRenderer implements Renderer {

    protected static final String KEY_ROTATION = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? MediaFormat.KEY_ROTATION
            : "rotation-degrees";

    private final boolean hasFilters;
    private GlFrameRenderFilter frameRenderFilter;

    private VideoRenderInputSurface inputSurface;
    private VideoRenderOutputSurface outputSurface;
    private List<GlFilter> filters;

    private float[] stMatrix = new float[16];
    private float[] mvpMatrix = new float[16];

    private boolean inputSurfaceTextureInitialized;
    private GlFramebuffer captureFBO;
    private GlTexture offscreenTexture;
    private Point outputSize;
    private ThumbnailReadyListener listener;
    private ByteBuffer pixelBuffer;

    // TODO: Come up with some method for returning finished images from here
    public interface ThumbnailReadyListener {
        void onThumbnailReady(String filePath);
        // Do we need this? IDK
        void onThumbnailBitmapReady(Bitmap bitmap);
    }

    public Bitmap saveTexture(int width, int height) {
        int capacity = width * height * 4;

        if (pixelBuffer == null || pixelBuffer.capacity() != capacity) {
            pixelBuffer = ByteBuffer.allocate(capacity);
        } else {
            pixelBuffer.rewind();
        }

        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(pixelBuffer);
        return bitmap;
    }

    /**
     * Create an instance of GlVideoRenderer. If filter list has a {@link GlFrameRenderFilter}, that filter
     * will be used to render video frames. Otherwise, default {@link DefaultVideoFrameRenderFilter}
     * will be used at lowest Z level to render video frames.
     *
     * @param filters optional list of OpenGL filters to applied to output video frames
     */
    public GlThumbnailRenderer(@Nullable List<GlFilter> filters, @NonNull ThumbnailReadyListener listener) {
        this.listener = listener;
        this.filters = new ArrayList<>();

        Matrix.setIdentityM(stMatrix, 0);

        hasFilters = filters != null && !filters.isEmpty();

        frameRenderFilter = new DefaultVideoFrameRenderFilter();
        this.filters.add(frameRenderFilter);

        // TODO: Actually support other filters (for later)
//        if (filters == null) {
//            this.filters.add(new DefaultVideoFrameRenderFilter());
//            // new Transform(new PointF(1f, 1f), new PointF(0.5f, 0.5f), 90)
//            return;
//        }
//
//        boolean hasFrameRenderFilter = false;
//        for (GlFilter filter : filters) {
//            if (filter instanceof GlFrameRenderFilter) {
//                hasFrameRenderFilter = true;
//                break;
//            }
//        }
//        if (!hasFrameRenderFilter) {
//            // if client provided filters don't have a frame render filter, insert default frame filter
//            this.filters.add(new DefaultVideoFrameRenderFilter());
//        }
//        this.filters.addAll(filters);
    }

    @Override
    public void init(@Nullable Surface outputSurface, @Nullable MediaFormat sourceMediaFormat, @Nullable MediaFormat targetMediaFormat) {
        if (sourceMediaFormat == null) {
            throw new IllegalArgumentException("GlThumbnailRenderer requires a valid source media format");
        }
        if (!sourceMediaFormat.containsKey(MediaFormat.KEY_WIDTH) || !sourceMediaFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
            throw new IllegalArgumentException("GlThumbnailRenderer requires a valid source media format");
        }

        int mediaRotation = 0;
        if (sourceMediaFormat.containsKey(KEY_ROTATION)) {
            mediaRotation = sourceMediaFormat.getInteger(KEY_ROTATION);
        }

        int width;
        int height;
        if (sourceMediaFormat.containsKey(MediaFormat.KEY_WIDTH) && sourceMediaFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
            width = sourceMediaFormat.getInteger(MediaFormat.KEY_WIDTH);
            height = sourceMediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
        } else {
            throw new IllegalArgumentException("GlThumbnailRenderer requires width and height to be defined in the source or target media format");
        }

        float aspectRatio = (float) width / height;

        outputSize = (mediaRotation == 90 || mediaRotation == 270) ?
                new Point(height / 4, width / 4) :
                new Point(width / 4, height / 4);

        inputSurface = new VideoRenderInputSurface();

        GlTexture newTexture = new GlTexture();
        SurfaceTexture surfaceTexture = new SurfaceTexture(newTexture.getTexName());
        surfaceTexture.setDefaultBufferSize(outputSize.x, outputSize.y);
        Surface surface = new Surface(surfaceTexture);
        this.outputSurface = new VideoRenderOutputSurface(surface);
        surfaceTexture.release();

        initMvpMatrix(aspectRatio);

//        Matrix.rotateM(
//                mvpMatrix,
//                0,
//                mediaRotation,
//                0f,
//                0f,
//                1f
//        );

        for (GlFilter filter : filters) {
            filter.init();
            filter.setVpMatrix(mvpMatrix, 0);
        }

        offscreenTexture = new GlTexture(GLES20.GL_TEXTURE0, GLES20.GL_TEXTURE_2D, null, outputSize.x, outputSize.y);
        offscreenTexture.bind();
        captureFBO = new GlFramebuffer();
        captureFBO.bind();
        captureFBO.attachTexture(offscreenTexture.getTexName());
        captureFBO.unbind();
        offscreenTexture.unbind();
    }


    private void initMvpMatrix(float videoAspectRatio) {
        float[] projectionMatrix = new float[16];
        Matrix.setIdentityM(projectionMatrix, 0);
        Matrix.orthoM(projectionMatrix, 0, -videoAspectRatio, videoAspectRatio, -1, 1, -1, 1);

        // rotate the camera to match video frame rotation
        float[] viewMatrix = new float[16];
        Matrix.setIdentityM(viewMatrix, 0);
        Matrix.setLookAtM(viewMatrix, 0,
                0, 0, 1,
                0, 0, 0,
                0, -1, 0);

        Matrix.setIdentityM(mvpMatrix, 0);
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
    }

    @Override
    public void onMediaFormatChanged(@Nullable MediaFormat sourceMediaFormat, @Nullable MediaFormat targetMediaFormat) {
    }

    @Override
    @Nullable
    public Surface getInputSurface() {
        if (inputSurface != null) {
            return inputSurface.getSurface();
        }
        return null;
    }

    @Override
    public void renderFrame(@Nullable Frame inputFrame, long presentationTimeNs) {
        inputSurface.awaitNewImage();
        captureFBO.bind();

        drawFrame(presentationTimeNs);
        Bitmap bitmap = saveTexture(outputSize.x, outputSize.y);
        listener.onThumbnailBitmapReady(bitmap);
        captureFBO.unbind();
        extracted.add(bitmap);
        outputSurface.setPresentationTime(presentationTimeNs);
        outputSurface.swapBuffers();
    }

    private List<Bitmap> extracted = new ArrayList<>();

    @Override
    public void release() {
        for (GlFilter filter : filters) {
            filter.release();
        }

        inputSurface.release();
        outputSurface.release();
        captureFBO.delete();
    }

    @Override
    public boolean hasFilters() {
        return hasFilters;
    }

    /**
     * Draws the data from SurfaceTexture onto the current EGL surface.
     */
    private void drawFrame(long presentationTimeNs) {

        for (GlFilter filter : filters) {
            if (filter instanceof GlFrameRenderFilter) {
                inputSurface.getTransformMatrix(stMatrix);
                ((GlFrameRenderFilter) filter).initInputFrameTexture(inputSurface.getTextureId(), stMatrix);
            }
        }

        GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

        for (GlFilter filter : filters) {
            filter.apply(presentationTimeNs);
        }

        GLES20.glFinish();
    }

}
