package com.ylshie.android.car.evs;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceView;

public class AutoFitSurfaceView extends SurfaceView {
    private String TAG = "AutoFitSurfaceView";

    public AutoFitSurfaceView(Context context) {
        super(context, null, 0);
    }
    public AutoFitSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
    }
    public AutoFitSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    private float aspectRatio = 0f;

    /**
     * Sets the aspect ratio for this view. The size of the view will be
     * measured based on the ratio calculated from the parameters.
     *
     * @param width  Camera resolution horizontal size
     * @param height Camera resolution vertical size
     */
    public void setAspectRatio(int width, int height) {
        //require(width > 0 && height > 0) { "Size cannot be negative" }
        if (width <= 0) return;
        if (height <= 0) return;

        aspectRatio = (float) width / height;

        getHolder().setFixedSize(width, height);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        //if (aspectRatio <= 0) return;

        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (aspectRatio == 0f) {
            setMeasuredDimension(width, height);
        } else {
            // Performs center-crop transformation of the camera frames
            int newWidth;
            int newHeight;
            float actualRatio = (width > height) ? aspectRatio : 1f / aspectRatio;
            if (width < height * actualRatio) {
                newHeight = height;
                newWidth = (int) (height * actualRatio);
            } else {
                newWidth = width;
                newHeight = (int) (width / actualRatio);
            }

            Log.d(TAG, "Measured dimensions set: $newWidth x $newHeight");
            setMeasuredDimension(newWidth, newHeight);
        }
    }
}
