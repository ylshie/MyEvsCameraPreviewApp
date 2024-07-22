/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.ylshie.android.car.evs;

import static android.car.evs.CarEvsManager.ERROR_NONE;
//import static android.hardware.display.DisplayManager.DisplayListener;

import android.app.Activity;
import android.car.Car;
//import android.car.Car.CarServiceLifecycleListener;
//import android.car.CarNotConnectedException;
//import android.car.evs.CarEvsBufferDescriptor;
import android.car.evs.CarEvsManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
//import android.hardware.display.DisplayManager;
import android.os.Bundle;

import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.annotation.GuardedBy;

import com.android.car.internal.evs.CarEvsGLSurfaceView;
import com.android.car.internal.evs.GLES20CarEvsBufferRenderer;
import android.opengl.GLSurfaceView;

import java.util.ArrayList;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.hardware.HardwareBuffer;

import android.opengl.GLES20;
import android.util.Size;

public class CarEvsCameraPreviewActivity extends EvsBaseActivity {

    private static final String TAG = CarEvsCameraPreviewActivity.class.getSimpleName();
    /**
     * ActivityManagerService encodes the reason for a request to close system dialogs with this
     * key.
     */
    private final static String EXTRA_DIALOG_CLOSE_REASON = "reason";
    /** This string literal is from com.android.systemui.car.systembar.CarSystemBarButton class. */
    private final static String DIALOG_CLOSE_REASON_CAR_SYSTEMBAR_BUTTON = "carsystembarbutton";
    /** This string literal is from com.android.server.policy.PhoneWindowManager class. */
    private final static String DIALOG_CLOSE_REASON_HOME_KEY = "homekey";


    /** GL backed surface view to render the camera preview */
    //private CarEvsGLSurfaceView mEvsView;
    private GLSurfaceView mEvsView;
    private ViewGroup mRootView;
    private LinearLayout mPreviewContainer;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                Bundle extras = intent.getExtras();
                if (extras != null) {
                    String reason = extras.getString(EXTRA_DIALOG_CLOSE_REASON);
                    if (!DIALOG_CLOSE_REASON_CAR_SYSTEMBAR_BUTTON.equals(reason) &&
                        !DIALOG_CLOSE_REASON_HOME_KEY.equals(reason)) {
                        Log.i(TAG, "[Arthur] Ignore a request to close the system dialog with a reason = " +
                                   reason);
                        return;
                    }
                    Log.d(TAG, "[Arthur] Requested to close the dialog, reason = " + reason);
                }
                finish();
            } else {
                Log.e(TAG, "[Arthur] Unexpected intent " + intent);
            }
        }
    };

    // To close the PreviewActiivty when Home button is clicked.
    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        // Need to register the receiver for all users, because we want to receive the Intent after
        // the user is changed.
        registerReceiverForAllUsers(mBroadcastReceiver, filter, /* broadcastPermission= */ null,
                /* scheduler= */ null, Context.RECEIVER_EXPORTED);
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "[Arthur] onCreate");
        super.onCreate(savedInstanceState);

        registerBroadcastReceiver();
        parseExtra(getIntent());

        setShowWhenLocked(true);
        
        // Packaging parameters to create CarEvsGLSurfaceView.
        //Arthur
        //mEvsView = CarEvsGLSurfaceView.create(getApplication(), callbacks, getApplicationContext()
        //        .getResources().getInteger(R.integer.config_evsRearviewCameraInPlaneRotationAngle),
        //        DEFAULT_1X1_POSITION);
        //mEvsView = new GLSurfaceView(this);
        mRootView = (ViewGroup) LayoutInflater.from(this).inflate(
                R.layout.evs_preview_activity, /* root= */ null);
        mPreviewContainer = mRootView.findViewById(R.id.evs_preview_container);
        LinearLayout.LayoutParams viewParam = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.0f
        );
        Log.d(TAG, "[Arthur] find view_finder");

        SurfaceView viewFinder = mPreviewContainer.findViewById(R.id.view_finder);
        Log.d(TAG, "[Arthur] createPipeline");
        hookView(viewFinder);
        //Arthur
        if (mEvsView != null) {
            mEvsView.setLayoutParams(viewParam);
            mPreviewContainer.addView(mEvsView, 0);
        }
        View closeButton = mRootView.findViewById(R.id.close_button);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> handleCloseButtonTriggered());
        }

        int width = WindowManager.LayoutParams.MATCH_PARENT;
        int height = WindowManager.LayoutParams.MATCH_PARENT;
        if (mUseSystemWindow) {
            width = getResources().getDimensionPixelOffset(R.dimen.camera_preview_width);
            height = getResources().getDimensionPixelOffset(R.dimen.camera_preview_height);
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                2020 /* WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY */,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        params.dimAmount = getResources().getFloat(R.dimen.config_cameraBackgroundScrim);

        if (mUseSystemWindow) {
            WindowManager wm = getSystemService(WindowManager.class);
            wm.addView(mRootView, params);
        } else {
            setContentView(mRootView, params);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        parseExtra(intent);
    }

    private void parseExtra(Intent intent) {
        Bundle extras = intent.getExtras();
        extras = null;
        if (extras == null) {
            Log.d(TAG, "[Arthur] Normal Window");
            mSessionToken = null;
            mServiceType = CarEvsManager.SERVICE_TYPE_REARVIEW;
            mUseSystemWindow = false;
            return;
        }

        Log.d(TAG, "[Arthur] System Window");
        mSessionToken = extras.getBinder(CarEvsManager.EXTRA_SESSION_TOKEN);
        mUseSystemWindow = mSessionToken != null;
        mServiceType = extras.getShort(Integer.toString(CarEvsManager.SERVICE_TYPE_REARVIEW));
    }

    @Override
    protected void onRestart() {
        Log.d(TAG, "onRestart");
        super.onRestart();
        synchronized (mLock) {
            // When we come back to the top task, we start rendering the view.
            handleVideoStreamLocked(STREAM_STATE_VISIBLE);
        }
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        try {
            if (mUseSystemWindow && mEvsView.getWindowVisibility() == View.VISIBLE) {
                // When a new activity is launched, this activity will become the background
                // activity and, however, likely still visible to the users if it is using the
                // system window.  Therefore, we should not transition to the STOPPED state.
                //
                // Similarly, this activity continues previewing the camera when the user triggers
                // the home button.  If the users want to manually close the preview window, they
                // can trigger the close button at the bottom of the window.
                return;
            }

            synchronized (mLock) {
                handleVideoStreamLocked(STREAM_STATE_STOPPED);
            }
        } finally {
            super.onStop();
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        try {
            if (mUseSystemWindow) {
                WindowManager wm = getSystemService(WindowManager.class);
                wm.removeViewImmediate(mRootView);
            }

            unregisterReceiver(mBroadcastReceiver);
        } finally {
            super.onDestroy();
        }
    }

    private void handleCloseButtonTriggered() {
        // It is possible that we've been stopped but a video stream is still active.
        synchronized (mLock) {
            handleVideoStreamLocked(STREAM_STATE_STOPPED);
        }
        finish();
    }
}
