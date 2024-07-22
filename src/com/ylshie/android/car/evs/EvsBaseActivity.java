package com.ylshie.android.car.evs;

import java.util.ArrayList;
import android.car.Car;
import android.car.Car.CarServiceLifecycleListener;
import android.car.CarNotConnectedException;
import android.car.evs.CarEvsBufferDescriptor;
import android.car.evs.CarEvsManager;
import android.app.Activity;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.hardware.HardwareBuffer;
import androidx.annotation.GuardedBy;
import static android.car.evs.CarEvsManager.ERROR_NONE;

import com.android.car.internal.evs.CarEvsGLSurfaceView;
import com.android.car.internal.evs.GLES20CarEvsBufferRenderer;

import android.opengl.GLES20;
import android.util.Size;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.content.Context;
import android.view.Display;

import static android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.display.DisplayManager;
import android.view.WindowManager;
import android.view.View;

public class EvsBaseActivity extends Activity         
        implements CarEvsGLSurfaceView.BufferCallback, HardwarePipeline.BufferCallback {
    @GuardedBy("mLock")
    protected IBinder mSessionToken;

    protected boolean mUseSystemWindow;
    protected int mServiceType;

    /** Callback executors */
    private final ExecutorService mCallbackExecutor = Executors.newFixedThreadPool(1);

    private static final String TAG = EvsBaseActivity.class.getSimpleName();
    /**
     * Defines internal states.
     */
    protected final static int STREAM_STATE_STOPPED = 0;
    protected final static int STREAM_STATE_VISIBLE = 1;
    protected final static int STREAM_STATE_INVISIBLE = 2;
    protected final static int STREAM_STATE_LOST = 3;

    protected final static float DEFAULT_1X1_POSITION[][] = {
        {
            -1.0f,  1.0f, 0.0f,
             1.0f,  1.0f, 0.0f,
            -1.0f, -1.0f, 0.0f,
             1.0f, -1.0f, 0.0f,
        },
    };

    /** Buffer queue to store references of received frames */
    @GuardedBy("mLock")
    protected final ArrayList<CarEvsBufferDescriptor> mBufferQueue = new ArrayList<>();

        /** Tells whether or not a video stream is running */
    @GuardedBy("mLock")
    protected int mStreamState = STREAM_STATE_STOPPED;

    @GuardedBy("mLock")
    protected Car mCar;

    @GuardedBy("mLock")
    protected CarEvsManager mEvsManager;

    protected final Object mLock = new Object();
    protected HardwarePipeline pipeline = null;
    protected EncoderWrapper encoder = null;
    protected Surface encoderSurface = null;
    protected GLES20CarEvsBufferRenderer render = null;

    protected static String streamStateToString(int state) {
        switch (state) {
            case STREAM_STATE_STOPPED:
                return "STOPPED";

            case STREAM_STATE_VISIBLE:
                return "VISIBLE";

            case STREAM_STATE_INVISIBLE:
                return "INVISIBLE";

            case STREAM_STATE_LOST:
                return "LOST";

            default:
                return "UNKNOWN: " + state;
        }
    }

    /** Display manager to monitor the display's state */
    private DisplayManager mDisplayManager;

    /** Current display state */
    private int mDisplayState = Display.STATE_OFF;

    /**
     * The Activity with showWhenLocked doesn't go to sleep even if the display sleeps.
     * So we'd like to monitor the display state and react on it manually.
     */
    private final DisplayListener mDisplayListener = new DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                return;
            }
            int state = decideViewVisibility();
            synchronized (mLock) {
                mDisplayState = state;
                int visible = state == Display.STATE_ON ?
                        STREAM_STATE_VISIBLE : STREAM_STATE_INVISIBLE;
                Log.d(TAG, "[Arthur] onDisplayChanged visible=" + visible);
                handleVideoStreamLocked(visible);
            }
        }
    };

    /** Callback to listen to EVS stream */
    protected final CarEvsManager.CarEvsStreamCallback mStreamHandler =
            new CarEvsManager.CarEvsStreamCallback() {

        protected String eventName(int event) {
            return "Unknown";
        }

        @Override
        public void onStreamEvent(int event) {
            // This reference implementation only monitors a stream event without any action.
            Log.i(TAG, "[Arthur] onStreamEvent: " + event);
            if (event == CarEvsManager.STREAM_EVENT_STREAM_STOPPED ||
                event == CarEvsManager.STREAM_EVENT_TIMEOUT) {
                Log.i(TAG, "[Arthur] Stream Stop");
                finish();
            }
        }
        private void checkGlError(String op) {
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                String msg = op + ": glError 0x" + Integer.toHexString(error);
                Log.e(TAG, msg);
            //    throw new RuntimeException(msg);
            }
        }
        @Override
        public void onNewFrame(CarEvsBufferDescriptor buffer) {
            synchronized (mLock) {
            	Log.i(TAG, "[Arthur] onNewFrame: ");
                if (mStreamState == STREAM_STATE_INVISIBLE) {
                    // When the activity becomes invisible (e.g. goes background), we immediately
                    // returns received frame buffers instead of stopping a video stream.
                    doneWithBufferLocked(buffer);
                } else {
                    // Enqueues a new frame and posts a rendering job
                    mBufferQueue.add(buffer);
                    if (render != null && pipeline != null) {
                        /*
                        //pipeline.activeGL();
                        int[] TextureIds = pipeline.getPreviewTargetIds();
                        //int[] TextureIds = mTextureId;
                        HardwareBuffer hardwarebuffer = buffer.getHardwareBuffer();
                        boolean res = render.nUpdateTexture(hardwarebuffer, TextureIds[0]);
                        checkGlError("nUpdateTexture");
                        //checkEglError("nUpdateTexture");
                        Log.i(TAG, "[Arthur] nUpdateTexture: " + TextureIds[0] + " res=" + res);
                        //doneWithBufferLocked(buffer);
                        */
                        pipeline.notifyFrame();
                    }
                }
            }
        }
    };

    /** CarService status listener  */
    private final CarServiceLifecycleListener mCarServiceLifecycleListener = (car, ready) -> {
        try {
            synchronized (mLock) {
                mCar = ready ? car : null;
                mEvsManager = ready ? (CarEvsManager) car.getCarManager(Car.CAR_EVS_SERVICE) : null;
                if (!ready) {
                    if (!mUseSystemWindow) {
                        // If we were launched by the user manually, we enter the LOST state and
                        // wait for the car service's restoration.
                        handleVideoStreamLocked(STREAM_STATE_LOST);
                    } else {
                        // If we were launched by the system,we will clean up the states and
                        // then finish; the car service will request a new instance when it comes
                        // back from the incident while the system still requires the rearview.
                        handleVideoStreamLocked(STREAM_STATE_STOPPED);
                        finish();
                    }
                } else {
                    // We request to start a video stream if we get connected to the car service.
                    handleVideoStreamLocked(STREAM_STATE_VISIBLE);
                }
            }
        } catch (CarNotConnectedException err) {
            Log.e(TAG, "Failed to connect to the Car Service");
        }
    };

    @GuardedBy("mLock")
    protected void handleVideoStreamLocked(int newState) {
        Log.d(TAG, "[Arthur] Requested: " + streamStateToString(mStreamState) + " -> " +
                streamStateToString(newState));
        if (newState == mStreamState) {
            // Safely ignore a request of transitioning to the current state.
            return;
        }

        boolean needToUpdateState = false;
        switch (newState) {
            case STREAM_STATE_STOPPED:
                if (mEvsManager != null) {
                    mEvsManager.stopVideoStream();
                    mBufferQueue.clear();
                    needToUpdateState = true;
                } else {
                    Log.w(TAG, "EvsManager is not available");
                }
                break;

            case STREAM_STATE_VISIBLE:
                // Starts a video stream
                if (mEvsManager != null) {
                    int result = mEvsManager.startVideoStream(mServiceType, mSessionToken,
                            mCallbackExecutor, mStreamHandler);
                    if (result != ERROR_NONE) {
                        Log.e(TAG, "Failed to start a video stream, error = " + result);
                    } else {
                        needToUpdateState = true;
                    }
                } else {
                    Log.w(TAG, "EvsManager is not available");
                }
                break;

            case STREAM_STATE_INVISIBLE:
                needToUpdateState = true;
                break;

            case STREAM_STATE_LOST:
                needToUpdateState = true;
                break;

            default:
                throw new IllegalArgumentException();
        }

        if (needToUpdateState) {
            mStreamState = newState;
            Log.d(TAG, "Completed: " + streamStateToString(mStreamState));
        }
    }

    @Override
    public CarEvsBufferDescriptor onBufferRequested() {
        synchronized (mLock) {
            if (mBufferQueue.isEmpty()) {
                return null;
            }

            // The renderer refreshes faster than 30fps so it's okay to fetch the frame from the
            // front of the buffer queue always.
            CarEvsBufferDescriptor newFrame = mBufferQueue.get(0);
            mBufferQueue.remove(0);

            return newFrame;
        }
    }

    @Override
    public void onBufferProcessed(CarEvsBufferDescriptor buffer) {
        synchronized (mLock) {
            doneWithBufferLocked(buffer);
        }
    }
    public CarEvsBufferDescriptor requestBuffer() {
        return onBufferRequested();
    }
    public void releaseBuffer(CarEvsBufferDescriptor buffer) {
        onBufferProcessed(buffer);
    }

    @GuardedBy("mLock")
    private void doneWithBufferLocked(CarEvsBufferDescriptor buffer) {
        try {
            mEvsManager.returnFrameBuffer(buffer);
        } catch (Exception e) {
            Log.w(TAG, "CarEvsService is not available.");
        }
    }

    public boolean updateTexture(HardwareBuffer buffer, int textureId) {
        if (render == null) return false;
        boolean res = render.nUpdateTexture(buffer, textureId);
        return res;
    }

    private File createFile(Context context, String filename) {
        File folderPath = context.getExternalFilesDir(null);
        return new File(folderPath, filename); //"VID_${sdf.format(Date())}.$extension");
    }
    private String genUniqueName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US);
        String filename = "VID_" + sdf.format(new Date());
        return filename;
    }
    private final int RECORDER_VIDEO_BITRATE = 10_000_000;

    protected void hookView(SurfaceView viewFinder) {
        if (viewFinder != null) {
            createPipeline(viewFinder);
            SurfaceHolder holder = viewFinder.getHolder();
            //Context context = this;
            Log.d(TAG, "[Arthur] addCallback");
            holder.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder _holder) {
                    Log.d(TAG, "[Arthur] surfaceCreated");
                    //createTexture();
                    viewFinder.post(new Runnable() {
                        @Override
                        public void run() {
                            Log.d(TAG, "[Arthur] Runnable");
                            pipeline.createResources(_holder.getSurface());
                        }
                    });
                }
                @Override
                public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
                    Log.d(TAG,"surfaceChanged");
                }
                @Override
                public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
                    Log.d(TAG,"surfaceDestroyed");
                }
            });
        }
    }

    private void createPipeline(SurfaceView viewFinder) {
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
        int state = decideViewVisibility();
        synchronized (mLock) {
            mDisplayState = state;
        }

        Log.d(TAG, "[Arthur] createCar");
        Car.createCar(getApplicationContext(), /* handler = */ null,
        Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mCarServiceLifecycleListener);

        ArrayList callbacks = new ArrayList<>(1);
        callbacks.add(CarEvsManager.SERVICE_TYPE_REARVIEW, this);
        int angleInDegree = getApplicationContext()
                .getResources().getInteger(R.integer.config_evsRearviewCameraInPlaneRotationAngle);
        Log.d(TAG, "[Arthur] new GLES20CarEvsBufferRenderer");
        render = new GLES20CarEvsBufferRenderer(callbacks, angleInDegree, DEFAULT_1X1_POSITION);
        int orientation = 0;
        String basename = genUniqueName();
        File outputFile = createFile(this, basename + ".mp4");
        File eventFile = createFile(this, basename + "_ev.mp4");
        Context context = this;
        FileGen gen = new FileGen() {
            @Override
            public File create() {
                String base = genUniqueName();
                String name = base + ".mp4";
                File file = createFile(context, name);
                return file;
            }
        };
        encoder = new EncoderWrapper(640, 480, RECORDER_VIDEO_BITRATE,30, 0, gen, outputFile, eventFile);
        encoderSurface = encoder.getInputSurface();
        pipeline = new HardwarePipeline(640, 480, viewFinder, orientation, encoder);
        pipeline.setBufferCallback(this);
        pipeline.setPreviewSize(new Size(640,480));
    }

    // Hides the view when the display is off to save the system resource, since this has
    // 'showWhenLocked' attribute, this will not go to PAUSED state even if the display turns off.
    private int decideViewVisibility() {
        Display defaultDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        int state = defaultDisplay.getState();
        Log.d(TAG, "decideShowWhenLocked: displayState=" + state);
        if (state == Display.STATE_ON) {
            getWindow().getDecorView().setVisibility(View.VISIBLE);
        } else {
            getWindow().getDecorView().setVisibility(View.INVISIBLE);
        }

        return state;
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        try {
            // Request to stop current service and unregister a status listener
            synchronized (mLock) {
                if (mEvsManager != null) {
                    handleVideoStreamLocked(STREAM_STATE_STOPPED);
                    mEvsManager.clearStatusListener();
                }
                if (mCar != null) {
                    mCar.disconnect();
                }
            }

            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        /*
            if (mUseSystemWindow) {
                WindowManager wm = getSystemService(WindowManager.class);
                wm.removeViewImmediate(mRootView);
            }
        
            unregisterReceiver(mBroadcastReceiver);
        */
        } finally {
            super.onDestroy();
        }
    }
}