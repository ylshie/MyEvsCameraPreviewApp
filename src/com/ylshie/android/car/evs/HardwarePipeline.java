package com.ylshie.android.car.evs;

import static android.opengl.EGL14.EGL_NO_SURFACE;
import static android.opengl.GLES20.GL_COLOR_BUFFER_BIT;
import static android.opengl.GLES20.GL_SCISSOR_TEST;
import static android.opengl.GLES20.glClear;
import static android.opengl.GLES20.glClearColor;
import static android.opengl.GLES20.glEnable;
import static android.opengl.GLES20.glFlush;
import static android.opengl.GLES20.glGetUniformLocation;
import static android.opengl.GLES20.glScissor;
import static android.opengl.GLES20.glUniform1i;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
//import android.hardware.camera2.CameraCharacteristics;
import android.location.Location;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES10;
import android.opengl.GLES11;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Date;
/*
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
*/
import android.opengl.GLES20;
import android.view.TextureView;

//import androidx.core.app.ActivityCompat;

import javax.microedition.khronos.opengles.GL10;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.evs.CarEvsBufferDescriptor;

public class HardwarePipeline {
    public interface BufferCallback {
        /**
         * Requests a new {@link CarEvsBufferDescriptor} to draw.
         *
         * This method may return a {@code null} if no new frame has been prepared since the last
         * frame was drawn.
         *
         * @return {@link CarEvsBufferDescriptor} object to process.
         */
        @Nullable CarEvsBufferDescriptor requestBuffer();

        /**
         * Notifies that the buffer is processed.
         *
         * @param buffer {@link CarEvsBufferDescriptor} object we are done with.
         */
        void releaseBuffer(@NonNull CarEvsBufferDescriptor buffer);
        boolean updateTexture(HardwareBuffer buffer, int textureId);
    }
    BufferCallback mBufferCallback = null;
    public void setBufferCallback(BufferCallback callback) {
        mBufferCallback = callback;
    }
    boolean use2D = true;
    boolean useEVS = true;
    static final String TAG = "MyCamera2";
    static final boolean VERBOSE = false;
    static final String TRANSFORM_VSHADER = """
            attribute vec4 vPosition;
            uniform mat4 texMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = vPosition;
                vec4 texCoord = vec4((vPosition.xy + vec2(1.0, 1.0)) / 2.0, 0.0, 1.0);
                vTextureCoord = (texMatrix * texCoord).xy;
            }""";
    // Alpha Blending
    static final String BLEND_FSHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES texture0;
            uniform samplerExternalOES texture1;
            void main() {
                vec4 v0 = texture2D(texture0, vTextureCoord);
                vec4 v1 = texture2D(texture1, vTextureCoord);
                float a = v1[3];
                gl_FragColor = (1.0 - a) * v0 + a * v1;
            }
            """;
    static final String BLEND_FSHADER_EVS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D texture0;
            uniform sampler2D texture1;
            void main() {
                vec4 v0 = texture2D(texture0, vTextureCoord);
                vec4 v1 = texture2D(texture1, vTextureCoord);
                float a = v1[3];
                gl_FragColor = (1.0 - a) * v0 + a * v1;
            }
            """;
    static final String BLEND_FSHADER_2D = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES texture0;
            uniform sampler2D texture1;
            void main() {
                vec4 v0 = texture2D(texture0, vTextureCoord);
                vec4 v1 = texture2D(texture1, vTextureCoord);
                float a = v1[3];
                gl_FragColor = v1;
            }
            """;
    //     gl_FragColor = (1.0 - a) * v0 + a * v1;
    static final String PASSTHROUGH_FSHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
            """;

    private void checkGlError(String op) {
        int error = GLES30.glGetError();
        if (error != GLES30.GL_NO_ERROR) {
            String msg = op + ": glError 0x" + Integer.toHexString(error);
            Log.e(TAG, msg);
            throw new RuntimeException(msg);
        }
    }
    public void startRecording() {
        if (VERBOSE) Log.i(TAG,"==[HP] startRecording ==");
        renderHandler.startRecording();
    }
    public void stopRecording() {
        if (VERBOSE) Log.i(TAG,"==[HP] stopRecording ==");
        renderHandler.stopRecording();
    }
    public void actionDown(Surface encoderSurface) {
        renderHandler.sendMessage(renderHandler.obtainMessage(
                RenderHandler.MSG_ACTION_DOWN, 0, 0, encoderSurface));
    }
    public void activeGL() {
        renderHandler.activeGL();
    }
    public void notifyFrame() {
        renderHandler.onEVSAvailable();
    }
    private class RenderHandler extends Handler
            implements SurfaceTexture.OnFrameAvailableListener {
        int width;
        int height;
        SurfaceView viewFinder;
        Size previewSize;
        EGLConfig eglConfig;
        private EGLSurface eglRenderSurface = EGL_NO_SURFACE;
        private EGLSurface eglEncoderSurface = EGL_NO_SURFACE;
        private EGLSurface eglWindowSurface = EGL_NO_SURFACE;
        private ShaderProgram cameraToRenderShaderProgram = null;
        private ShaderProgram renderToPreviewShaderProgram = null;
        private ShaderProgram renderToEncodeShaderProgram = null;
        private float[] FULLSCREEN_QUAD = {
                -1.0f, -1.0f,  // 0 bottom left
                1.0f, -1.0f,  // 1 bottom right
                -1.0f, 1.0f,  // 2 top left
                1.0f, 1.0f,  // 3 top right
        };
        private float[] PART_QUAD = {
                -0.5f, -0.5f,  // 0 bottom left   ｜ -1, 1          1, 1
                0.5f, -0.5f,  // 1 bottom right   ｜
                -0.5f, 0.5f,  // 2 top left       ｜
                0.5f, 0.5f,  // 3 top right       ｜ -1,-1          1,-1
        };
        private int orientation = 0;
        private float[] texMatrix = new float[16];
        private int vertexShader = 0;
        static final int MSG_CREATE_RESOURCES = 0;
        static final int MSG_DESTROY_WINDOW_SURFACE = 1;
        static final int MSG_ACTION_DOWN = 2;
        static final int MSG_CLEAR_FRAME_LISTENER = 3;
        static final int MSG_CLEANUP = 4;
        static final int MSG_ON_FRAME_AVAILABLE = 5;
        static final int MSG_ON_EVS_AVAILABLE = 6;
        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;

        // OpenGL texture for the SurfaceTexture provided to the camera
        private int cameraTexId = 0;

        // The SurfaceTexture provided to the camera for capture
        private SurfaceTexture cameraTexture = null;

        // The above SurfaceTexture cast as a Surface
        private Surface cameraSurface = null;


        // OpenGL texture for the SurfaceTexture provided to the camera
        private int infoTexId = 0;

        // The SurfaceTexture provided to the camera for capture
        private SurfaceTexture infoTexture = null;

        // The above SurfaceTexture cast as a Surface
        private Surface infoSurface = null;

        /** OpenGL texture that will combine the camera output with rendering */
        private int renderTexId = 0;

        /** The SurfaceTexture we're rendering to */
        private SurfaceTexture renderTexture = null;

        /** The above SurfaceTexture cast as a Surface */
        private Surface renderSurface = null;
        protected boolean currentlyRecording = false;

        public void activeGL() {
            EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglRenderSurface, eglContext);
        }
        public RenderHandler(Looper looper, int _width, int _height, int _orientation, SurfaceView _viewFinder) {
            super(looper);
            width = _width;
            height = _height;
            viewFinder = _viewFinder;
            //orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            orientation = _orientation;
        }
        public void startRecording() {
            currentlyRecording = true;
        }
        public void stopRecording() {
            currentlyRecording = false;
        }
        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            sendMessage(obtainMessage(MSG_ON_FRAME_AVAILABLE, 0, 0, surfaceTexture));
        }
        public void onEVSAvailable() {
            sendMessage(obtainMessage(MSG_ON_EVS_AVAILABLE, 0, 0, 0));
        }

        public void setPreviewSize(Size _previewSize) {
            previewSize = _previewSize;
        }

        CarEvsBufferDescriptor bufferToReturn = null;
        private void onEVSAvailableImpl() {
            if (mBufferCallback == null) return;

            activeGL();
            CarEvsBufferDescriptor bufferToRender = mBufferCallback.requestBuffer();
            HardwareBuffer buffer = bufferToRender.getHardwareBuffer();
            boolean res = mBufferCallback.updateTexture(buffer, cameraTexId);
            //Log.d(TAG, "[Arthur] updateTexture " + res);
            onFrameAvailableImpl(null);
            if (bufferToReturn != null) {
                mBufferCallback.releaseBuffer(bufferToReturn);
            }
            bufferToReturn = bufferToRender;
        }

        private void onFrameAvailableImpl(SurfaceTexture surfaceTexture) {
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                return;
            }

            checkGlError("Empty");
            // The camera API does not update the tex image. Do so here.
            if (VERBOSE) Log.d(TAG, "[Arthur] call updateTexImage @onFrameAvailableImpl");
            if (cameraTexture != null) {
                if (! useEVS) cameraTexture.updateTexImage();
                if (VERBOSE) Log.d(TAG, "[Arthur] updateTexImage called @onFrameAvailableImpl");
            }
            checkGlError("updateTexImage");

            // Copy from the camera texture to the render texture
            if (eglRenderSurface != EGL_NO_SURFACE) {
                if (VERBOSE) Log.d(TAG, "onFrameAvailableImpl call copyCameraToRender");
                if (VERBOSE) Log.d(TAG, "[Arthur] call copyCameraToRender @onFrameAvailableImpl");
                copyCameraToRender();
            }

            // Copy from the render texture to the TextureView
            copyRenderToPreview();

            // Copy to the encoder surface if we're currently recording
            if (eglEncoderSurface != EGL_NO_SURFACE && currentlyRecording) {
                copyRenderToEncode();
            }
        }

        private void checkEglError(String op) {
            int eglError = EGL14.eglGetError();
            if (eglError != EGL14.EGL_SUCCESS) {
                String msg = op + ": eglError 0x" + Integer.toHexString(eglError);
                Log.e(TAG, msg);
                throw new RuntimeException(msg);
            }
        }

        private void initEGL() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("unable to get EGL14 display");
            }
            checkEglError("eglGetDisplay");

            int[] version = {0, 0};
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                eglDisplay = null;
                throw new RuntimeException("Unable to initialize EGL14");
            }
            checkEglError("eglInitialize");

            int eglVersion = version[0] * 10 + version[1];
            if (VERBOSE) Log.i(TAG, "eglVersion: " + eglVersion);

            int renderableType = EGL14.EGL_OPENGL_ES2_BIT;

            int rgbBits = 8;
            int alphaBits = 8;

            int[] configAttribList = {
                    EGL14.EGL_RENDERABLE_TYPE, renderableType,
                    EGL14.EGL_RED_SIZE, rgbBits,
                    EGL14.EGL_GREEN_SIZE, rgbBits,
                    EGL14.EGL_BLUE_SIZE, rgbBits,
                    EGL14.EGL_ALPHA_SIZE, alphaBits,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = {null};
            int[] numConfigs = {1};
            EGL14.eglChooseConfig(eglDisplay, configAttribList, 0, configs,
                    0, configs.length, numConfigs, 0);
            eglConfig = configs[0];
            int requestedVersion = 2;
            int[] contextAttribList = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, requestedVersion,
                    EGL14.EGL_NONE
            };
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                    contextAttribList, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw new RuntimeException("Failed to create EGL context");
            }
            int[] clientVersion = {0};
            EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION,
                    clientVersion, 0);
            if (VERBOSE) Log.v(TAG, "EGLContext created, client version " + clientVersion[0]);

            int[] tmpSurfaceAttribs = {
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE};
            EGLSurface tmpSurface = EGL14.eglCreatePbufferSurface(
                    eglDisplay, eglConfig, tmpSurfaceAttribs, /*offset*/ 0);
            EGL14.eglMakeCurrent(eglDisplay, tmpSurface, tmpSurface, eglContext);
        }

        private int createTexId() {
            IntBuffer buffer = IntBuffer.allocate(1);
            GLES30.glGenTextures(1, buffer);
            return buffer.get(0);
        }

        private int createTexture() {
            EGLDisplay eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            /* Check that EGL has been initialized. */
            if (eglDisplay == null) {
                throw new IllegalStateException("EGL not initialized before call to createTexture()");
            }

            int texId = createTexId();
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER,
                    GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER,
                    GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S,
                    GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T,
                    GLES30.GL_CLAMP_TO_EDGE);
            return texId;
        }

        private void drawBitmap2Texture(int type) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            if (useEVS) {
                //canvas.rotate(45);
                //canvas.translate(0, 0);
            } else {
                //canvas.rotate(-90);
                //canvas.translate(-height, 0);
                //canvas.drawColor(Color.GREEN);
                //checkGlError("glClear");
            }
            Date date = new Date();
            Paint mPaint = new Paint();
            mPaint.setStrokeWidth(3);
            mPaint.setTextSize(32);
            mPaint.setColor(Color.WHITE);
            mPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(date.toString(),0, 32, mPaint);
            GLUtils.texImage2D(type, 0, bitmap, 0);
            bitmap.recycle();
        }
        private int create2DTexture() {
            EGLDisplay eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            /* Check that EGL has been initialized. */
            if (eglDisplay == null) {
                throw new IllegalStateException("EGL not initialized before call to createTexture()");
            }

            int texId = createTexId();
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                    GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER,
                    GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S,
                    GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T,
                    GLES30.GL_CLAMP_TO_EDGE);
            drawBitmap2Texture(GLES30.GL_TEXTURE_2D);
            /*
            int[] frameBuffer = new int[1];
            GLES30.glGenFramebuffers(1, frameBuffer, 0);
            // 将帧缓冲对象绑定到OpenGL ES上下文的帧缓冲目标上
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameBuffer[0]);
            // 使用GLES30.GL_COLOR_ATTACHMENT0将纹理作为颜色附着点附加到帧缓冲对象上
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texId, 0);
            // 取消绑定缓冲区
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_NONE);
            */

            return texId;
        }

        private ShaderProgram createShaderProgram(int fragmentShader) {
            int shaderProgram = GLES30.glCreateProgram();
            checkGlError("glCreateProgram");

            GLES30.glAttachShader(shaderProgram, vertexShader);
            checkGlError("glAttachShader");
            GLES30.glAttachShader(shaderProgram, fragmentShader);
            checkGlError("glAttachShader");
            GLES30.glLinkProgram(shaderProgram);
            checkGlError("glLinkProgram");

            int[] linkStatus = {0}; //intArrayOf(0)
            GLES30.glGetProgramiv(shaderProgram, GLES30.GL_LINK_STATUS, linkStatus, 0);
            checkGlError("glGetProgramiv");
            if (linkStatus[0] == 0) {
                String msg = "Could not link program: " + GLES30.glGetProgramInfoLog(shaderProgram);
                GLES30.glDeleteProgram(shaderProgram);
                throw new RuntimeException(msg);
            }

            int vPositionLoc = GLES30.glGetAttribLocation(shaderProgram, "vPosition");
            checkGlError("glGetAttribLocation");
            int texMatrixLoc = glGetUniformLocation(shaderProgram, "texMatrix");
            checkGlError("glGetUniformLocation");

            return new ShaderProgram(shaderProgram, vPositionLoc, texMatrixLoc);
        }

        /** Create a shader given its type and source string */
        private int createShader(int type, String source) {
            int shader = GLES30.glCreateShader(type);
            GLES30.glShaderSource(shader, source);
            checkGlError("glShaderSource");
            GLES30.glCompileShader(shader);
            checkGlError("glCompileShader");
            int[] compiled = {0}; //intArrayOf(0)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
            checkGlError("glGetShaderiv");
            if (compiled[0] == 0) {
                String msg = "Could not compile shader " + type + ": " + GLES30.glGetShaderInfoLog(shader);
                GLES30.glDeleteShader(shader);
                throw new RuntimeException(msg);
            }
            return shader;
        }

        private void createShaderResources() {
            vertexShader = createShader(GLES30.GL_VERTEX_SHADER, TRANSFORM_VSHADER);
            int passthroughFragmentShader = createShader(GLES30.GL_FRAGMENT_SHADER, PASSTHROUGH_FSHADER);
            int blendFragmentShader = useEVS
                    ? createShader(GLES30.GL_FRAGMENT_SHADER, BLEND_FSHADER_EVS)
                    : ( use2D
                        ? createShader(GLES30.GL_FRAGMENT_SHADER, BLEND_FSHADER_2D)
                        : createShader(GLES30.GL_FRAGMENT_SHADER, BLEND_FSHADER));
            ShaderProgram passthroughShaderProgram = createShaderProgram(passthroughFragmentShader);
            ShaderProgram blendShaderProgram = createShaderProgram(blendFragmentShader);

            if (VERBOSE) Log.d(TAG, "[Arthur] cameraToRenderShaderProgram");
            //cameraToRenderShaderProgram     = passthroughShaderProgram;
            cameraToRenderShaderProgram = blendShaderProgram;
            renderToPreviewShaderProgram = passthroughShaderProgram;
            renderToEncodeShaderProgram = passthroughShaderProgram;
        }

        private void copyCameraToRender() {
            drawRect();

            EGL14.eglMakeCurrent(eglDisplay, eglRenderSurface, eglRenderSurface, eglContext);
            checkGlError("eglMakeCurrent");
            Rect area = new Rect(0, 0, width, height);
            if (VERBOSE) Log.d(TAG, "[Arthur] copyCameraToRender: copyTexture with cameraToRenderShaderProgram");
            copyBlendTexture(cameraTexId, cameraTexture, area, cameraToRenderShaderProgram, false, infoTexId, infoTexture);
            //copyTexture(cameraTexId, cameraTexture, area, cameraToRenderShaderProgram, false);
            EGL14.eglSwapBuffers(eglDisplay, eglRenderSurface);
            GLES30.glUseProgram(0);

            renderTexture.updateTexImage();
        }

        private void copyRenderToPreview() {

            HardwareBuffer hardwareBuffer = null;
            //EGLImageKHR eglImage = null;
            EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglRenderSurface, eglContext);

            float cameraAspectRatio = (float) 1.0 * width / height;
            float previewAspectRatio = (float) 1.0 * previewSize.getWidth() / previewSize.getHeight();
            int viewportWidth = previewSize.getWidth();
            int viewportHeight = previewSize.getHeight();
            int viewportX = 0;
            int viewportY = 0;

            /** The camera display is not the same size as the video. Letterbox the preview so that
             * we can see exactly how the video will turn out. */
            if (previewAspectRatio < cameraAspectRatio) {
                /** Avoid vertical stretching */
                viewportHeight = (int) (((float) 1.0 * viewportHeight / previewAspectRatio) * cameraAspectRatio);
                viewportY = (previewSize.getHeight() - viewportHeight) / 2;
            } else {
                /** Avoid horizontal stretching */
                viewportWidth = (int) (((float) 1.0 * viewportWidth / cameraAspectRatio) * previewAspectRatio);
                viewportX = (previewSize.getWidth() - viewportWidth) / 2;
            }

            Rect area = new Rect(viewportX, viewportY, viewportWidth, viewportHeight);
            //copyTexture(renderTexId, renderTexture, area, renderToPreviewShaderProgram, false);
            copyTexture(renderTexId, renderTexture, area, renderToPreviewShaderProgram, true);

            EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface);
        /*
            if (eglImage != null) {
                GLES30.glBindFramebuffer(GL_FRAMEBUFFER, 0);
                androidx.opengl.EGLExt.eglDestroyImageKHR(eglDisplay, eglImage)
            }

         */
        }

        private void copyRenderToEncode() {
            EGL14.eglMakeCurrent(eglDisplay, eglEncoderSurface, eglRenderSurface, eglContext);

            var viewportWidth = width;
            var viewportHeight = height;

            /** Swap width and height if the camera is rotated on its side. */
            if (orientation == 90 || orientation == 270) {
                viewportWidth = height;
                viewportHeight = width;
            }

            copyTexture(renderTexId, renderTexture, new Rect(0, 0, viewportWidth, viewportHeight),
                    renderToEncodeShaderProgram, false);

            encoder.frameAvailable();

            EGL14.eglSwapBuffers(eglDisplay, eglEncoderSurface);
        }

        private void copyBlendTexture(int texId, SurfaceTexture texture, Rect viewportRect,
                                      ShaderProgram shaderProgram, Boolean outputIsFramebuffer,
                                      int texOver, SurfaceTexture textureOver) {
            glClearColor(0.1f, 0.0f, 0.0f, 1.0f);
            checkGlError("glClearColor");
            glClear(GL_COLOR_BUFFER_BIT);
            checkGlError("glClear");

            shaderProgram.useProgram();
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            checkGlError("glActiveTexture");
            if (useEVS) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId);
            } else {
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            }
            glUniform1i(glGetUniformLocation(shaderProgram.id, "texture0"), 0);
            checkGlError("glBindTexture");
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            checkGlError("glActiveTexture");
            if (use2D) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texOver);
            } else {
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOver);
            }
            glUniform1i(glGetUniformLocation(shaderProgram.id, "texture1"), 1);
            checkGlError("glBindTexture");

            texture.getTransformMatrix(texMatrix);
            //textureOver.getTransformMatrix(texMatrix);

            // HardwareBuffer coordinates are flipped relative to what GLES expects
            if (outputIsFramebuffer) {
                float[] flipMatrix = {
                        1f, 0f, 0f, 0f,
                        0f, -1f, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        0f, 1f, 0f, 1f
                };
                android.opengl.Matrix.multiplyMM(texMatrix, 0, flipMatrix, 0, texMatrix.clone(), 0);
            }
            shaderProgram.setTexMatrix(texMatrix);

            shaderProgram.setVertexAttribArray(FULLSCREEN_QUAD);

            GLES30.glViewport(viewportRect.left, viewportRect.top, viewportRect.right, viewportRect.bottom);
            checkGlError("glViewport");
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            checkGlError("glDrawArrays");
        }

        private void copyTexture(int texId, SurfaceTexture texture, Rect viewportRect,
                                 ShaderProgram shaderProgram, Boolean outputIsFramebuffer) {
            glClearColor(0.1f, 0.0f, 0.0f, 1.0f);
            checkGlError("glClearColor");
            glClear(GL_COLOR_BUFFER_BIT);
            checkGlError("glClear");

            shaderProgram.useProgram();
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            checkGlError("glActiveTexture");
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            checkGlError("glBindTexture");

            texture.getTransformMatrix(texMatrix);

            // HardwareBuffer coordinates are flipped relative to what GLES expects
            if (outputIsFramebuffer) {
                float[] flipMatrix = {
                        1f, 0f, 0f, 0f,
                        0f, -1f, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        0f, 1f, 0f, 1f
                };
                android.opengl.Matrix.multiplyMM(texMatrix, 0, flipMatrix, 0, texMatrix.clone(), 0);
            }
            shaderProgram.setTexMatrix(texMatrix);

            shaderProgram.setVertexAttribArray(FULLSCREEN_QUAD);

            GLES30.glViewport(viewportRect.left, viewportRect.top, viewportRect.right,
                    viewportRect.bottom);
            checkGlError("glViewport");
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            checkGlError("glDrawArrays");
        }

        class Square {
            public void setVerticesAndDraw(Float value, byte color) {
                FloatBuffer vertexbuffer;
                ByteBuffer indicesBuffer;
                ByteBuffer mColorBuffer;

                //byte indices[] = {0, 1, 2, 0, 2, 3};
                byte indices[] = {0, 1, 2, 0};

                float vetices[] = {
                        -value, value, 0.0f,  // (0)[-v, v]---(1)[ v, v]
                        value, value, 0.0f,   //  |                    |
                        value, -value, 0.0f,  // (3)[-v,-v]---(2)[ v,-v]
                        -value, -value, 0.0f
                };

                byte colors[] = {
                        color, color, 0, color,
                        0, color, color, color,
                        0, 0, 0, color,
                        color, 0, color, color
                };

                GLES11.glColor4f(0.0f, 1.0f, 0.0f, 1.0f);
                ByteBuffer byteBuffer = ByteBuffer.allocateDirect(vetices.length * 4);
                byteBuffer.order(ByteOrder.nativeOrder());
                vertexbuffer = byteBuffer.asFloatBuffer();
                vertexbuffer.put(vetices);
                vertexbuffer.position(0);

                indicesBuffer = ByteBuffer.allocateDirect(indices.length);
                indicesBuffer.put(indices);
                indicesBuffer.position(0);

                mColorBuffer = ByteBuffer.allocateDirect(colors.length);
                mColorBuffer.put(colors);
                mColorBuffer.position(0);

                GLES11.glEnableClientState(GL10.GL_VERTEX_ARRAY);
                GLES11.glEnableClientState(GL10.GL_COLOR_ARRAY);

                GLES11.glVertexPointer(3, GL10.GL_FLOAT, 0, vertexbuffer);
                GLES11.glColorPointer(4, GL10.GL_UNSIGNED_BYTE, 0, mColorBuffer);

                //GLES11.glDrawElements(GL10.GL_TRIANGLES, indices.length, GL10.GL_UNSIGNED_BYTE, indicesBuffer);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
                GLES11.glDisableClientState(GL10.GL_VERTEX_ARRAY);
            }
        }

        Location last_location = null;
        protected void drawRect() {
            /*
            if (   ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                Task<Location> task = location.getLastLocation();
                task.addOnSuccessListener(new OnSuccessListener<Location>() {
                    @Override
                    public void onSuccess(Location _location) {
                        // Got last known location. In some rare situations this can be null.
                        if (_location != null) {
                            last_location = _location;
                        }
                    }
                });
            }
            */

            Square square = new Square();
//square.draw(gl);

            //square.setVerticesAndDraw(0.8f, (byte) 255);

            //square.setVerticesAndDraw(0.7f, (byte) 150);
            //square.setVerticesAndDraw(0.6f, (byte) 100);
            //square.setVerticesAndDraw(0.5f, (byte) 80);
            //square.setVerticesAndDraw(0.4f, (byte) 50);

            if (use2D) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
                drawBitmap2Texture(GLES30.GL_TEXTURE_2D);
            } else {
                Canvas canvas = infoSurface.lockHardwareCanvas();
                canvas.save();
                canvas.rotate(-90);
                canvas.translate(-height, 0);
                checkGlError("glClear");
                //Canvas canvas = infoSurface.lockCanvas(null);
                //canvas.drawColor(Color.GREEN);
                Date date = new Date();
                Paint mPaint = new Paint();
                mPaint.setStrokeWidth(3);
                mPaint.setTextSize(32);
                mPaint.setColor(Color.WHITE);
                mPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(date.toString(), 0, 32, mPaint);
                if (last_location != null) {
                    double lat = last_location.getLatitude();
                    double lon = last_location.getLongitude();
                    String locinfo = lat + "/" + lon;
                    canvas.drawText(locinfo, 0, 64, mPaint);
                }
                checkGlError("glClear");
                //canvas.drawRect(new Rect(0,0,100,100), mPaint);
                canvas.restore();
                infoSurface.unlockCanvasAndPost(canvas);
                checkGlError("glClear");
                //if (! use2D)
                infoTexture.updateTexImage();
                checkGlError("glClear");
            }
        }
        private void createResources(Surface surface) {
            Log.d(TAG, "[Arthur] RenderHandler@createResources");
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                initEGL();
            }
            int[] windowSurfaceAttribs = {EGL14.EGL_NONE};
            eglWindowSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface,
                    windowSurfaceAttribs, 0
            );
            if (eglWindowSurface == EGL_NO_SURFACE) {
                throw new RuntimeException("Failed to create EGL texture view surface");
            }
            cameraTexId = (useEVS)? create2DTexture(): createTexture();
            if (VERBOSE) Log.d(TAG,"[Arthur] create cameraTexture");
            cameraTexture = new SurfaceTexture(cameraTexId);
            if (VERBOSE) Log.d(TAG,"[Arthur] set cameraTexture as frame vailable listener");
            cameraTexture.setOnFrameAvailableListener(this);
            cameraTexture.setDefaultBufferSize(width, height);
            if (VERBOSE) Log.d(TAG,"[Arthur] create surface from cameraTexture");
            cameraSurface = new Surface(cameraTexture);

            infoTexId = use2D ? create2DTexture(): createTexture();
            infoTexture = new SurfaceTexture(infoTexId);
            infoTexture.setDefaultBufferSize(width, height);
            infoSurface = new Surface(infoTexture);

            renderTexId = createTexture();
            renderTexture = new SurfaceTexture(renderTexId);
            renderTexture.setDefaultBufferSize(width, height);
            renderSurface = new Surface(renderTexture);

            int[] renderSurfaceAttribs = {EGL14.EGL_NONE};
            eglRenderSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, renderSurface,
                    renderSurfaceAttribs, 0);
            if (eglRenderSurface == EGL_NO_SURFACE) {
                throw new RuntimeException("Failed to create EGL render surface");
            }

            createShaderResources();
        }
        Surface[] getTargets() {
            //cvResourcesCreated.block()

            return new Surface[]{cameraSurface};
        }
        int[] getTargetIds() {
            //cvResourcesCreated.block()

            return new int[]{cameraTexId};
        }
        private void actionDown(Surface encoderSurface) {
            int[] surfaceAttribs = {EGL14.EGL_NONE};
            eglEncoderSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig,
                    encoderSurface, surfaceAttribs, 0);
            if (eglEncoderSurface == EGL_NO_SURFACE) {
                int error = EGL14.eglGetError();
                throw new RuntimeException("Failed to create EGL encoder surface"
                        + ": eglGetError = 0x" + Integer.toHexString(error));
            }
        }
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CREATE_RESOURCES:
                    createResources((Surface) msg.obj);
                    break;
                //MSG_DESTROY_WINDOW_SURFACE -> destroyWindowSurface()
                case MSG_ACTION_DOWN:
                    actionDown((Surface) msg.obj);
                    break;
                //MSG_CLEAR_FRAME_LISTENER -> clearFrameListener()
                //MSG_CLEANUP -> cleanup()
                case MSG_ON_EVS_AVAILABLE:
                    onEVSAvailableImpl();
                    break;
                case MSG_ON_FRAME_AVAILABLE:
                    onFrameAvailableImpl((SurfaceTexture) msg.obj);
                    break;
            }
        }
    }

    int width;
    int height;
    HandlerThread renderThread;
    RenderHandler renderHandler;
    EncoderWrapper encoder;
    //FusedLocationProviderClient location;
    Context context;
    public HardwarePipeline(int _width, int _height, SurfaceView _viewFinder, int orientation, EncoderWrapper _encoder) {
        width = _width;
        height = _height;
        encoder = _encoder;
        renderThread = new HandlerThread("Camera2Video.RenderThread");
        renderThread.start();
        renderHandler = new RenderHandler(renderThread.getLooper(), width, height, orientation, _viewFinder);
    }
    public void init(Context _context) {
        context = _context;
    //    location = LocationServices.getFusedLocationProviderClient(context);
    }

    public void setPreviewSize(Size previewSize) {
        renderHandler.setPreviewSize(previewSize);
    }
    Surface[] getPreviewTargets() {
        return renderHandler.getTargets();
    }
    int[] getPreviewTargetIds() {
        return renderHandler.getTargetIds();
    }
    public void createResources(Surface surface) {
        Log.i(TAG, "[Arthur] Pipeline@createResources");
        renderHandler.sendMessage(renderHandler.obtainMessage(
                RenderHandler.MSG_CREATE_RESOURCES, 0, 0, surface));
    }

    private class ShaderProgram {
        private int id = 0;
        private int vPositionLoc = 0;
        private int texMatrixLoc = 0;
        ShaderProgram(int _id,
                      int _vPositionLoc,
                      int _texMatrixLoc) {
            id = _id;
            vPositionLoc = _vPositionLoc;
            texMatrixLoc = _texMatrixLoc;
        }

        public void setVertexAttribArray(float[] vertexCoords) {
            ByteBuffer nativeBuffer = ByteBuffer.allocateDirect(vertexCoords.length * 4);
            nativeBuffer.order(ByteOrder.nativeOrder());
            FloatBuffer vertexBuffer = nativeBuffer.asFloatBuffer();
            vertexBuffer.put(vertexCoords);
            nativeBuffer.position(0);
            vertexBuffer.position(0);

            GLES30.glEnableVertexAttribArray(vPositionLoc);
            checkGlError("glEnableVertexAttribArray");
            GLES30.glVertexAttribPointer(vPositionLoc, 2, GLES30.GL_FLOAT, false, 8, vertexBuffer);
            checkGlError("glVertexAttribPointer");
        }

        public void setTexMatrix(float[] texMatrix) {
            GLES30.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0);
            checkGlError("glUniformMatrix4fv");
        }

        public void useProgram() {
            GLES30.glUseProgram(id);
            checkGlError("glUseProgram");
        }
    }
}
