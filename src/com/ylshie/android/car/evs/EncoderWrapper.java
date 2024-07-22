package com.ylshie.android.car.evs;

import android.media.MediaCodec;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

import android.os.Handler;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.media.MediaRecorder;
import android.view.Surface;

import kotlin.jvm.Volatile;


public class EncoderWrapper {
    final String TAG = "EncoderWrapper";
    class EventVideo {
        ByteBuffer buffer;
        MediaCodec.BufferInfo info;
        long timestamp;
        int status;
        int num;
    }
    class EventList extends LinkedList<EventVideo> {
        private int count = 0;
        public EventVideo newEvent() {
            EventVideo event = new EventVideo();
            event.num = count;
            count = count + 1;
            return event;
        }
        @Override
        public boolean add(EventVideo newVideo) {
            EventVideo topVideo = peek();
            long span = (topVideo != null) ? newVideo.timestamp - topVideo.timestamp: 0;
            if (span > 2 * 1000000) poll();
            return super.add(newVideo);
        }
    }
    EventList mEventList = new EventList();
    private int mWidth;
    private int mHeight;
    private int mBitRate;
    private int mFrameRate;
    private long mDynamicRange;
    private int mOrientationHint;
    private FileGen mOutputs;
    private File mOutputFile;
    private File mEventFile;
    //private boolean mUseMediaRecorder;
    private int mVideoCodec;
    protected String mMimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
    boolean useMediaRecorder = false;
    MediaRecorder mMediaRecorder = null;
    boolean VERBOSE = true;
    final int IFRAME_INTERVAL = 1;
    //
    MediaCodec mEncoder = null;
    MediaFormat mEncodedFormat = null;
    Surface mInputSurface = null;

    protected EncoderThread createEncoderThread() {
        if (useMediaRecorder) return null;
        else return new EncoderThread(mEncoder, mOrientationHint);
    }
    private EncoderThread mEncoderThread = null; //createEncoderThread();

    protected MediaCodec createEncoder() {
        if (useMediaRecorder) {
            return null;
        } else {
            MediaCodec encoder = null;
            try {
                encoder = MediaCodec.createEncoderByType(mMimeType);
            } catch (IOException e) {
                Log.e(TAG, "create encoder fialed");
            }
            return encoder;
        }
    }
    private MediaRecorder createRecorder(Surface surface) {
        MediaRecorder recorder = new MediaRecorder();

        String path = mOutputFile.getAbsolutePath();
        Log.d(TAG, "output=" + path);
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(path);
        recorder.setVideoEncodingBitRate(mBitRate);
        if (mFrameRate > 0) recorder.setVideoFrameRate(mFrameRate);
        recorder.setVideoSize(mWidth, mHeight);
        /*
            val videoEncoder = when (mVideoCodec) {
                VideoCodecFragment.VIDEO_CODEC_ID_H264 ->
                MediaRecorder.VideoEncoder.H264
                VideoCodecFragment.VIDEO_CODEC_ID_HEVC ->
                MediaRecorder.VideoEncoder.HEVC
                VideoCodecFragment.VIDEO_CODEC_ID_AV1 ->
                MediaRecorder.VideoEncoder.AV1
                else -> throw IllegalArgumentException("Unknown video codec id")
            }

         */
        int videoEncoder = MediaRecorder.VideoEncoder.H264;
        recorder.setVideoEncoder(videoEncoder);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(16);
        recorder.setAudioSamplingRate(44100);
        recorder.setInputSurface(surface);
        recorder.setOrientationHint(mOrientationHint);

        return recorder;
    }
    protected Surface createInputSurface() {
        if (useMediaRecorder) {
            // Get a persistent Surface from MediaCodec, don't forget to release when done
            Surface surface = MediaCodec.createPersistentInputSurface();

            // Prepare and release a dummy MediaRecorder with our new surface
            // Required to allocate an appropriately sized buffer before passing the Surface as the
            //  output target to the capture session
            MediaRecorder recorder = createRecorder(surface);
            try {
                recorder.prepare();
                recorder.release();
            } catch (IOException e) {

            }

            return surface;
        } else {
            //if (mEncoder == null) mEncoder = createEncoder();
            //return mEncoder.createInputSurface();
            return null;
        }
    }
    public EncoderWrapper(int width,
                   int height,
                   int bitRate,
                   int frameRate,
                   //long dynamicRange,
                   int orientationHint,
                   FileGen outputs,
                   File outputFile,
                   File eventFile
                   //boolean useMediaRecorder,
                   //int videoCodec
    )
    {
        mWidth = width;
        mHeight = height;
        mBitRate = bitRate;
        mFrameRate = frameRate;
        //mDynamicRange = dynamicRange;
        mOrientationHint = orientationHint;
        mOutputs = outputs;
        mOutputFile = outputFile;
        mEventFile = eventFile;
        mEncoderThread = createEncoderThread(); //Need output file
        //useMediaRecorder = false;
        //mUseMediaRecorder = useMediaRecorder;
        //mVideoCodec = videoCodec;
        mInputSurface = createInputSurface();
        init(width,height,mBitRate,mFrameRate);
        if (! useMediaRecorder) {
            mInputSurface = mEncoder.createInputSurface();
        }
    }

    protected void init(int width, int height, int bitRate, int frameRate) {
        Log.i(TAG,"==[EW] init: createRecorder from InputSurface");
        if (useMediaRecorder) {
            mMediaRecorder = createRecorder(mInputSurface);
        } else {
            if (mEncoder == null) mEncoder = createEncoder();
            /*
            val codecProfile = when (mVideoCodec) {
                VideoCodecFragment.VIDEO_CODEC_ID_HEVC -> when {
                    dynamicRange == DynamicRangeProfiles.HLG10 ->
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    dynamicRange == DynamicRangeProfiles.HDR10 ->
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    dynamicRange == DynamicRangeProfiles.HDR10_PLUS ->
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    else -> -1
                }
                VideoCodecFragment.VIDEO_CODEC_ID_AV1 -> when {
                    dynamicRange == DynamicRangeProfiles.HLG10 ->
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                    dynamicRange == DynamicRangeProfiles.HDR10 ->
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10
                    dynamicRange == DynamicRangeProfiles.HDR10_PLUS ->
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                    else -> -1
                }
                else -> -1
            }
             */
            int codecProfile = -1;

            MediaFormat format = MediaFormat.createVideoFormat(mMimeType, width, height);

            // Set some properties.  Failing to specify some of these can cause the MediaCodec
            // configure() call to throw an unhelpful exception.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

            if (codecProfile != -1) {
                format.setInteger(MediaFormat.KEY_PROFILE, codecProfile);
                format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, getTransferFunction());
                format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing, true);
            }

            if (VERBOSE) Log.d(TAG, "format: " + format);

            // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
            // we can use for input and wrap it with a class that handles the EGL work.
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
    }
    private int getTransferFunction() {
        return MediaFormat.COLOR_TRANSFER_SDR_VIDEO;
        /*
        when (mDynamicRange) {
        DynamicRangeProfiles.HLG10 -> MediaFormat.COLOR_TRANSFER_HLG
        DynamicRangeProfiles.HDR10 -> MediaFormat.COLOR_TRANSFER_ST2084
        DynamicRangeProfiles.HDR10_PLUS -> MediaFormat.COLOR_TRANSFER_ST2084
        else -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        */
    }

    public Surface getInputSurface() {
        return mInputSurface;
    }

    public void start() {
        if (useMediaRecorder) {
            try {
                mMediaRecorder.prepare();
                mMediaRecorder.start();
            } catch(IOException e) {
            }
        } else {
            mEncoder.start();

            // Start the encoder thread last.  That way we're sure it can see all of the state
            // we've initialized.
            mEncoderThread.start();
            mEncoderThread.waitUntilReady();
        }
    }

    /**
     * Shuts down the encoder thread, and releases encoder resources.
     * <p>
     * Does not return until the encoder thread has stopped.
     */
    public boolean shutdown() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");

        if (useMediaRecorder) {
            try {
                mMediaRecorder.stop();
            } catch (RuntimeException e) {
                // https://developer.android.com/reference/android/media/MediaRecorder.html#stop()
                // RuntimeException will be thrown if no valid audio/video data has been received
                // when stop() is called, it usually happens when stop() is called immediately after
                // start(). In this case the output file is not properly constructed ans should be
                // deleted.
                Log.d(TAG, "RuntimeException: stop() is called immediately after start()");
                //noinspection ResultOfMethodCallIgnored
                mOutputFile.delete();
                return false;
            }
        } else {
            Handler handler = mEncoderThread.getHandler();
            handler.sendMessage(handler.obtainMessage(EncoderThread.EncoderHandler.MSG_SHUTDOWN));
            try {
                mEncoderThread.join();
            } catch (InterruptedException ie) {
                Log.w(TAG, "Encoder thread join() was interrupted", ie);
            }

            mEncoder.stop();
            mEncoder.release();
        }
        return true;
    }
    /**
     * Notifies the encoder thread that a new frame is available to the encoder.
     */
    public void frameAvailable() {
        if (!useMediaRecorder) {
            Handler handler = mEncoderThread.getHandler();
            handler.sendMessage(handler.obtainMessage(
                    EncoderThread.EncoderHandler.MSG_FRAME_AVAILABLE));
        }
    }

    MediaMuxer createMuxer(File file) {
        MediaMuxer muxer = null;
        try {
            muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return muxer;
    }

    MediaMuxer mEuxer;
    public void saveEventFile(File file) {
        Handler handler = mEncoderThread.getHandler();
        handler.sendMessage(handler.obtainMessage(EncoderThread.EncoderHandler.MSG_SAVEEVENT));
        /*
        while (mEventList.peek() != null) {
            EventVideo event = mEventList.poll();
            byte[] buffer = new byte[10];
            event.buffer.get(buffer, 0, 10);

            Log.d(TAG, "[qq]-----write-" + event.num + buffer.toString());
            mEuxer.writeSampleData(mVideoTrack2, event.buffer, event.info);
        }
        mEuxer.stop();
        mEuxer.release();
        */
    }

    public void waitForFirstFrame() {
        if (!useMediaRecorder) {
            mEncoderThread.waitForFirstFrame();
        }
    }

    int mVideoTrack = -1;
    int mVideoTrack2 = -1;
    private class EncoderThread extends Thread {
        EncoderThread(MediaCodec mediaCodec, int orientationHint) {
            super();
            mEncoder = mediaCodec;
            mOrientationHint = orientationHint;
            mEuxer = createMuxer(mEventFile);
        };
        //MediaCodec mEncoder;
        MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
        MediaMuxer mMuxer = null;   //mMuxer1;
        long timeFile = -1;
        int mOrientationHint;

        EncoderHandler mHandler = null;
        int mFrameNum = 0;

        Object mLock = new Object();

        //@Volatile
        boolean mReady = false;
        /**
         * Thread entry point.
         * <p>
         * Prepares the Looper, Handler, and signals anybody watching that we're ready to go.
         */
        public void run() {
            Looper.prepare();
            mHandler = new EncoderHandler(this);   // must create on encoder thread
            Log.d(TAG, "encoder thread ready");
            synchronized (mLock) {
                mReady = true;
                mLock.notify();   // signal waitUntilReady()
            }

            Looper.loop();

            synchronized (mLock) {
                mReady = false;
                mHandler = null;
            }
            Log.d(TAG, "looper quit");
        }

        /**
         * Waits until the encoder thread is ready to receive messages.
         * <p>
         * Call from non-encoder thread.
         */
        public void waitUntilReady() {
            synchronized (mLock) {
                while (!mReady) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /**
         * Waits until the encoder has processed a single frame.
         * <p>
         * Call from non-encoder thread.
         */
        public void waitForFirstFrame() {
            synchronized (mLock) {
                while (mFrameNum < 1) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            }
            Log.d(TAG, "Waited for first frame");
        }

        /**
         * Returns the Handler used to send messages to the encoder thread.
         */
        public EncoderHandler getHandler() {
            synchronized (mLock) {
                // Confirm ready state.
                if (!mReady) {
                    throw new RuntimeException("not ready");
                }
            }
            return mHandler;
        }

        public static ByteBuffer clone(ByteBuffer original) {
            ByteBuffer clone = ByteBuffer.allocate(original.capacity());
            original.rewind();//copy from the beginning
            clone.put(original);
            original.rewind();
            clone.flip();
            return clone;
        }
        /**
         * Drains all pending output from the encoder, and adds it to the circular buffer.
         */
        public boolean drainEncoder() {
            long TIMEOUT_USEC = 0;     // no timeout -- check for buffers, bail if none
            boolean encodedFrame = false;

            while (true) {
                int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    break;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Should happen before receiving buffers, and should only happen once.
                    // The MediaFormat contains the csd-0 and csd-1 keys, which we'll need
                    // for MediaMuxer.  It's unclear what else MediaMuxer might want, so
                    // rather than extract the codec-specific data and reconstruct a new
                    // MediaFormat later, we just grab it here and keep it around.
                    mEncodedFormat = mEncoder.getOutputFormat();
                    Log.d(TAG, "encoder output format changed: " + mEncodedFormat);
                } else if (encoderStatus < 0) {
                    Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                            encoderStatus);
                    // let's ignore it
                } else {
                    ByteBuffer encodedData = mEncoder.getOutputBuffer(encoderStatus);
                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                                " was null");
                    }

                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // The codec config data was pulled out when we got the
                        // INFO_OUTPUT_FORMAT_CHANGED status.  The MediaMuxer won't accept
                        // a single big blob -- it wants separate csd-0/csd-1 chunks --
                        // so simply saving this off won't work.
                        if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                        mBufferInfo.size = 0;
                    }

                    if (mBufferInfo.size != 0) {
                        // adjust the ByteBuffer values to match BufferInfo (not needed?)
                        encodedData.position(mBufferInfo.offset);
                        encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                        boolean bNew = (mMuxer == null);
                        MediaMuxer PreMux = null;
                        long gap = (timeFile == -1)? 0: mBufferInfo.presentationTimeUs - timeFile;
                        if (mMuxer != null && gap > 1 * 60 * 1000000) {
                            bNew = true;
                            PreMux = mMuxer;
                        }
                        if (bNew) {
                            mOutputFile = mOutputs.create();
                            timeFile = mBufferInfo.presentationTimeUs;
                            mMuxer = createMuxer(mOutputFile);
                            mVideoTrack = mMuxer.addTrack(mEncodedFormat);
                            mMuxer.setOrientationHint(mOrientationHint);
                            mMuxer.start();
                            Log.d(TAG, "Started media muxer");
                        }
                        if (PreMux != null) {
                            PreMux.stop();
                            PreMux.release();
                        }
                        if (mVideoTrack2 == -1) {
                            mVideoTrack2 = mEuxer.addTrack(mEncodedFormat);
                            mEuxer.setOrientationHint(mOrientationHint);
                            mEuxer.start();
                            Log.d(TAG, "Started media muxer");
                        }

                        // mEncBuffer.add(encodedData, mBufferInfo.flags,
                        //         mBufferInfo.presentationTimeUs)
                        mMuxer.writeSampleData(mVideoTrack, encodedData, mBufferInfo);
                        //mEuxer.writeSampleData(mVideoTrack2, encodedData, mBufferInfo);
                        encodedFrame = true;

                        if (VERBOSE) {
                            Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                                    mBufferInfo.presentationTimeUs + " offset=" + mBufferInfo.offset);
                        }
                        //EventVideo event = new EventVideo();
                        EventVideo event = mEventList.newEvent();
                        //byte[] buffer = new byte[10];
                        //encodedData.get(buffer, 0, 10);
                        //Log.d(TAG, "[qq]----add " + event.num + buffer.toString());
                        //event.buffer = encodedData.slice();
                        //event.buffer = encodedData.duplicate();
                        event.buffer = clone(encodedData);
                        event.info = new MediaCodec.BufferInfo();
                        event.info.flags    = mBufferInfo.flags;
                        event.info.offset   = mBufferInfo.offset;
                        event.info.size     = mBufferInfo.size;
                        event.info.presentationTimeUs = mBufferInfo.presentationTimeUs;
                        event.timestamp = mBufferInfo.presentationTimeUs;
                        event.status = encoderStatus;
                        mEventList.add(event);
                    }

                    mEncoder.releaseOutputBuffer(encoderStatus, false);

                    if (mBufferInfo.size != 0) {
                        //EventVideo event = mEventList.poll();
                        // mEuxer.writeSampleData(mVideoTrack2, event.buffer, event.info);
                        //    mEuxer.writeSampleData(mVideoTrack2, encodedData, mBufferInfo);
                    }
                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                        break;      // out of while
                    }
                }
            }

            return encodedFrame;
        }

        /**
         * Drains the encoder output.
         * <p>
         * See notes for {@link EncoderWrapper#frameAvailable()}.
         */
        void frameAvailable() {
            if (VERBOSE) Log.d(TAG, "frameAvailable");
            if (drainEncoder()) {
                synchronized (mLock) {
                    mFrameNum++;
                    mLock.notify();
                }
            }
        }
        public void saveEventFile() {
            while (mEventList.peek() != null) {
                EventVideo event = mEventList.poll();
                //byte[] buffer = new byte[10];
                //event.buffer.get(buffer, 0, 10);

                //Log.d(TAG, "[qq]-----write-" + event.num + buffer.toString());
                mEuxer.writeSampleData(mVideoTrack2, event.buffer, event.info);
            }
            mEuxer.stop();
            mEuxer.release();
        }

        /**
         * Tells the Looper to quit.
         */
        void shutdown() {
            if (VERBOSE) Log.d(TAG, "shutdown");
            Looper.myLooper().quit();
            mMuxer.stop();
            mMuxer.release();
        }

        /**
         * Handler for EncoderThread.  Used for messages sent from the UI thread (or whatever
         * is driving the encoder) to the encoder thread.
         * <p>
         * The object is created on the encoder thread.
         */
        public class EncoderHandler extends Handler {
            static final int MSG_FRAME_AVAILABLE = 0;
            static final int MSG_SHUTDOWN = 1;
            static final int MSG_SAVEEVENT = 2;
            EncoderHandler(EncoderThread et) {
                super();
                mWeakEncoderThread = et;
            }
            // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
            // but no real harm in it.
            private EncoderThread mWeakEncoderThread = null; //WeakReference<EncoderThread>(et);

            // runs on encoder thread
            public void handleMessage(Message msg) {
                int what = msg.what;
                if (VERBOSE) {
                    Log.v(TAG, "EncoderHandler: what=" + what);
                }

                EncoderThread encoderThread = mWeakEncoderThread; //.get();
                if (encoderThread == null) {
                    Log.w(TAG, "EncoderHandler.handleMessage: weak ref is null");
                    return;
                }

                if (what == MSG_FRAME_AVAILABLE) {
                     encoderThread.frameAvailable();
                } else if (what == MSG_SHUTDOWN) {
                     encoderThread.shutdown();
                }  else if (what == MSG_SAVEEVENT) {
                    encoderThread.saveEventFile();
                }else {
                    throw new RuntimeException("unknown message " + what);
                }
            }
        }
    }
}
