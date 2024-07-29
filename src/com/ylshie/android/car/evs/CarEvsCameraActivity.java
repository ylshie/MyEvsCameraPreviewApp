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

import android.app.Activity;
import android.car.Car;
import android.car.Car.CarServiceLifecycleListener;
import android.car.evs.CarEvsManager;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.hardware.HardwareBuffer;
import android.widget.Button;
import java.io.File;
import android.net.Uri;

public class CarEvsCameraActivity extends EvsBaseActivity {
    private static final String TAG = CarEvsCameraActivity.class.getSimpleName();
    private static final int CAR_WAIT_TIMEOUT_MS = 3_000;

    //private static final boolean test = false;
    /** CarService status listener  */
    private final CarServiceLifecycleListener mCarServiceLifecycleListener = (car, ready) -> {
        if (!ready) {
            return;
        }

        try {
            CarEvsManager evsManager = (CarEvsManager) car.getCarManager(
                    Car.CAR_EVS_SERVICE);
            String config = getApplicationContext().getResources()
                    .getString(R.string.config_evsCameraType);
            int type = config == null ?
                    CarEvsManager.SERVICE_TYPE_REARVIEW : getServiceType(config);
            if (evsManager.startActivity(type) != ERROR_NONE) {
                Log.e(TAG, "Failed to start a camera preview activity");
            }
        } finally {
            mCar = car;
            finish();
        }
    };

    private Car mCar;

    static int getServiceType(String rawString) {
        switch (rawString) {
            case "REARVIEW": return CarEvsManager.SERVICE_TYPE_REARVIEW;
            case "SURROUNDVIEW": return CarEvsManager.SERVICE_TYPE_SURROUNDVIEW;
            case "FRONTVIEW": return CarEvsManager.SERVICE_TYPE_FRONTVIEW;
            case "LEFTVIEW": return CarEvsManager.SERVICE_TYPE_LEFTVIEW;
            case "RIGHTVIEW": return CarEvsManager.SERVICE_TYPE_RIGHTVIEW;
            case "DRIVERVIEW": return CarEvsManager.SERVICE_TYPE_DRIVERVIEW;
            case "FRONT_PASSENGERSVIEW": return CarEvsManager.SERVICE_TYPE_FRONT_PASSENGERSVIEW;
            case "REAR_PASSENGERSVIEW": return CarEvsManager.SERVICE_TYPE_REAR_PASSENGERSVIEW;
            case "USER_DEFINEDVIEW": return CarEvsManager.SERVICE_TYPE_USER_DEFINED;
            default:
                Log.w(TAG, "Unknown service type: " + rawString);
                return CarEvsManager.SERVICE_TYPE_REARVIEW;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
        Car.createCar(getApplicationContext(), null, CAR_WAIT_TIMEOUT_MS, mCarServiceLifecycleListener);
        */
        setContentView(R.layout.evs_preview_activity);
        SurfaceView finder = findViewById(R.id.view_finder);
        Button event = findViewById(R.id.event_button);
        Button upload = findViewById(R.id.upload_button);
        hookView(finder);
        //View closeButton = finder.findViewById(R.id.close_button);
        if (finder != null) {
            finder.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {
                    switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                        startEncoding();
                        Log.d(TAG, "Action Down");
                        break;
                    case MotionEvent.ACTION_UP:
                        stopEncoding();
                        Log.d(TAG,"Action Up");
                        ////preback();
                        ////Playback(eventFile);
                        break;
                    }
                    return false;
                }
            });
            //finder.setOnClickListener()
        }
        if (event != null) {
            event.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick (View v) {
                    Log.d(TAG,"[Arthur] Event:OnClick");
                    enableAEB();
                }
            });
        }
        if (upload != null) {
            upload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick (View v) {
                    Log.d(TAG,"[Arthur] Click Upload");
                    uploadTest();
                }
            });
        }
    }
    protected void uploadTest() {
        String url = "https://transpal-dev.pixseecare.com/client_service/api/v1/events";
        String id = "TJ1W9OQVO0";
        String secret = "PWfcvMY7PN";
        String file = getUploadFile();
        Uri uri = (file != null)? Uri.fromFile(new File(file)): null;
        UploadTask task = new UploadTask(url, id, secret, uri);
        task.execute();
    }
    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        if (mCar != null) {
            // Explicitly stops monitoring the car service's status
            mCar.disconnect();
        }

        super.onDestroy();
    }
}
