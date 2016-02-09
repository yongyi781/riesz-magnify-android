package com.yongyi.rieszmagnifyandroid;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A placeholder fragment containing a simple view.
 */
public class CameraFragment extends Fragment {
    /**
     * The permission code for requesting camera permission.
     */
    public static final int REQUEST_CAMERA_PERMISSION = 1;

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "CameraFragment";
    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final String FRAGMENT_DIALOG = "dialog";

    private static final Size DESIRED_IMAGE_READER_SIZE = new Size(640, 480);
    private static final int IMAGE_READER_BUFFER_SIZE = 16;
    private static final int MAX_TRANSLATION_MAP_SIZE = 100;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;
    /**
     * Whether or not the camera is rotated 180 relative to the display.
     */
    //    private boolean mCameraRotated;
    /**
     * An {@link SurfaceView} and its associated {@link Surface} for camera preview.
     */
    private AutoFitSurfaceView mSurfaceView;
    private Surface mSurface;
    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;
    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;
    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;
    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;
    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;
    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;
    /**
     * Toggled by the button: whether we want to use the edge detector.
     */
    private boolean mUseProcessing = false;
    /**
     * The offset from image timestamp to sensor timestamp. The sensor timestamp is nanoseconds since
     * uptime while the image timestamp is the nanoseconds on the system clock. An offset is needed
     * to calibrate the two different types of timestamps.
     */
    private long mSensorImageTimestampOffset = 0;
    /**
     * The x- and y- amounts that the camera image was translated by due to device rotation, as
     * detected by the gyroscope.
     */
    private TreeMap<Long, Vector3> mCameraMovements = new TreeMap<>();
    private long mLastImageAvailableTimestamp;
    private static final int NUM_FRAMES_TO_AVG = 16;
    private double[] fpsCircularBuffer = new double[NUM_FRAMES_TO_AVG];
    private int frameCounter = 0;
    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(final ImageReader reader) {
            if (mCameraDevice == null || mSurface == null || !mSurface.isValid()) {
                Log.d(TAG, "onImageAvailable: mCameraDevice or mSurface was null or mSurface was not valid.");
                return;
            }

            try (Image image = reader.acquireLatestImage()) {
                if (image == null)
                    return;

                NativeSurfaceHandle dstSurface = NativeSurfaceHandle.lockSurface(mSurface);
                if (dstSurface == null)
                    return;

                HalideYuvBufferT srcYuv = HalideYuvBufferT.fromImage(image);
                if (srcYuv == null)
                    return;

                HalideYuvBufferT dstYuv = dstSurface.allocNativeYuvBufferT();

                if (mUseProcessing) {
                    Vector3 v = getBooleanPreference("enable_stabilization", false) ?
                            getCameraMovementAtTimestamp(image.getTimestamp()) : Vector3.ZERO;
                    HalideFilters.magnify(srcYuv, dstYuv, v.x, v.y, v.z);
                } else {
                    HalideFilters.copy(srcYuv, dstYuv);
                }

                dstYuv.close();
                srcYuv.close();
                dstSurface.close();
            }

            long elapsed = System.nanoTime() - mLastImageAvailableTimestamp;
            double fps = 1000000000.0 / elapsed;
            fpsCircularBuffer[frameCounter % fpsCircularBuffer.length] = fps;
            mLastImageAvailableTimestamp = System.nanoTime();

            ++frameCounter;
            if (frameCounter >= 16 && frameCounter % 16 == 0) {
                double averageFps = 0.0;
                for (double val : fpsCircularBuffer)
                    averageFps += val;
                averageFps /= fpsCircularBuffer.length;
                Log.d(TAG, "onImageAvailable: average fps = " + averageFps);
                HalideFilters.setMeasuredFps(averageFps);
            }
        }
    };
    private CaptureRequest mPreviewRequest;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    /**
     * A {@link CameraCaptureSession.CaptureCallback} that receives metadata about the ongoing capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
    };
    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mCameraCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraOpenCloseLock.release();
            mCameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera disconnected.");
            mCameraOpenCloseLock.release();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera callback onError called with error " + error + "!");
            mCameraOpenCloseLock.release();
        }
    };
    private final SurfaceHolder.Callback mSurfaceCallback
            = new SurfaceHolder.Callback() {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            mSurface = holder.getSurface();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            mSurface = holder.getSurface();
            openCamera();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            mSurface = null;
        }

    };
    private SensorManager mSensorManager;
    private Sensor mGyroscope;
    private Switch mSwitch;
    private TextView mTextView;
    private long lastTimestamp;
    private SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            long currentTimeOffset = System.nanoTime() - SystemClock.elapsedRealtimeNanos();
            if (Math.abs(currentTimeOffset - mSensorImageTimestampOffset) > 10_000_000) {
                Log.d(TAG, "onSensorChanged: Time offset changed significantly, from " + mSensorImageTimestampOffset +
                        " to " + currentTimeOffset);
                // TODO: (Re-)measure the offset using more than one sample?
                mSensorImageTimestampOffset = currentTimeOffset;
            }

            Sensor sensor = event.sensor;
            if (sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                float[] values = event.values;
                float elapsed = (float) (event.timestamp - lastTimestamp) / 1000000000;
                Vector3 lastTranslation = Vector3.ZERO;
                Map.Entry<Long, Vector3> lastEntry = mCameraMovements.lastEntry();
                if (lastEntry != null)
                    lastTranslation = lastEntry.getValue();

                float horizontalViewAngle = getFloatPreference("horizontal_view_angle", 1.0f);
                float verticalViewAngle = getFloatPreference("vertical_view_angle", 0.625f);
                float driftCorrectionX = getFloatPreference("drift_correction_x", 0);
                float driftCorrectionY = getFloatPreference("drift_correction_y", 0);

                Vector3 newTranslation = new Vector3(
                        (lastTranslation.x - driftCorrectionX) - values[0] * elapsed * mPreviewSize.getWidth() / horizontalViewAngle,
                        (lastTranslation.y - driftCorrectionY) + values[1] * elapsed * mPreviewSize.getHeight() / verticalViewAngle,
                        lastTranslation.z - values[2] * elapsed);

                    if (Math.hypot(newTranslation.x, newTranslation.y) > mPreviewSize.getWidth() / 20) {
                        mCameraMovements.clear();
                } else {
                    // Put a new translation
                    mCameraMovements.put(event.timestamp + mSensorImageTimestampOffset, newTranslation);

                    // If translation gets too big, pop off the last one.
                    if (mCameraMovements.size() > MAX_TRANSLATION_MAP_SIZE)
                        mCameraMovements.pollFirstEntry();
                }

                // TODO: Remove in production
                mTextView.setText(String.format(Locale.getDefault(),
                        "timestamp = %d\nx = %.4f\ny = %.4f\nz = %.4f\n",
                        event.timestamp, values[0], values[1], values[2]));
                lastTimestamp = event.timestamp;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            Log.d(TAG, "Sensor accuracy changed.");
        }
    };

    private Vector3 getCameraMovementAtTimestamp(long timestamp) {
        Map.Entry<Long, Vector3> floorMovements = mCameraMovements.floorEntry(timestamp);
        Map.Entry<Long, Vector3> ceilingMovements = mCameraMovements.ceilingEntry(timestamp);

        if (floorMovements == null && ceilingMovements == null)
            return Vector3.ZERO;
        else if (floorMovements == null)
            return ceilingMovements.getValue();
        else if (ceilingMovements == null)
            return floorMovements.getValue();

        // Interpolate
        float t = (timestamp - floorMovements.getKey()) /
                (ceilingMovements.getKey() - floorMovements.getKey());
        Vector3 floorValue = floorMovements.getValue();
        Vector3 ceilingValue = ceilingMovements.getValue();
        return new Vector3(
                (1 - t) * floorValue.x + t * ceilingValue.x,
                (1 - t) * floorValue.y + t * ceilingValue.y,
                (1 - t) * floorValue.z + t * ceilingValue.z);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(getFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        Log.d(TAG, "onViewCreated");

        final Activity activity = getActivity();

        mSwitch = (Switch) view.findViewById(R.id.processingSwitch);
        mSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mUseProcessing = isChecked;
                resetImageTranslation();
            }
        });

        mSurfaceView = (AutoFitSurfaceView) view.findViewById(R.id.surfaceView);
        mSurfaceView.getHolder().addCallback(mSurfaceCallback);

        mTextView = (TextView) view.findViewById(R.id.text);

        view.findViewById(R.id.clearHistoryButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HalideFilters.resetFilterHistory();
            }
        });

        view.findViewById(R.id.settingsButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(activity, SettingsActivity.class));
            }
        });

        mSensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    private void resetImageTranslation() {
        mCameraMovements.clear();
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        startBackgroundThread();
        setUpCameraOutputs();

        mSensorImageTimestampOffset = System.nanoTime() - SystemClock.elapsedRealtimeNanos();
        mSensorManager.registerListener(mSensorEventListener, mGyroscope, SensorManager.SENSOR_DELAY_FASTEST);

        // Make the SurfaceView VISIBLE so that on resume, surfaceCreated() is called,
        // and on pause, surfaceDestroyed() is called.
        mSurfaceView.getHolder().setFormat(ImageFormat.YV12);
        int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            mSurfaceView.setAspectRatio(DESIRED_IMAGE_READER_SIZE.getHeight(), DESIRED_IMAGE_READER_SIZE.getWidth());
            mSurfaceView.getHolder().setFixedSize(mImageReader.getHeight(), mImageReader.getWidth());
        } else {
            mSurfaceView.setAspectRatio(DESIRED_IMAGE_READER_SIZE.getWidth(), DESIRED_IMAGE_READER_SIZE.getHeight());
            mSurfaceView.getHolder().setFixedSize(mImageReader.getWidth(), mImageReader.getHeight());
        }

        mSurfaceView.getHolder().addCallback(mSurfaceCallback);
        mSurfaceView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");

        // Turn off processing.
        mSwitch.setChecked(false);

        closeCamera();
        stopBackgroundThread();
        mSensorManager.unregisterListener(mSensorEventListener);
        super.onPause();

        // Make the SurfaceView GONE so that on resume, surfaceCreated() is called,
        // and on pause, surfaceDestroyed() is called.
        mSurfaceView.getHolder().removeCallback(mSurfaceCallback);
        mSurfaceView.setVisibility(View.GONE);
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            Log.d(TAG, "closeCamera: Stopped camera capture session");
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            Log.d(TAG, "closeCamera: Closed camera device");
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
            Log.d(TAG, "closeCamera: Closed image reader");
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        Log.d(TAG, "stopBackgroundThread");
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        Log.d(TAG, "startBackgroundThread");
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Query the system for available cameras and configurations. If the configuration we want is
     * available, create an ImageReader of the right format and size, save the camera id, and
     * tell the UI what aspect ratio to use.
     */
    private void setUpCameraOutputs() {
        Log.d(TAG, "setUpCameraOutputs");
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                // Check if the sensor is rotated relative to what we expect.
                //                Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                //                Log.d(TAG, "Sensor orientation: " + sensorOrientation);
                //                mCameraRotated = sensorOrientation >= 180;

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // See if the camera supports a YUV resolution that we want and create an
                // ImageReader if it does.
                Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                Log.d(TAG, "Available YUV_420_888 sizes: " + Arrays.toString(sizes));

                Size optimalSize = chooseOptimalSize(sizes,
                        DESIRED_IMAGE_READER_SIZE.getWidth(),
                        DESIRED_IMAGE_READER_SIZE.getHeight(),
                        DESIRED_IMAGE_READER_SIZE /* aspect ratio */);
                if (optimalSize != null) {
                    Log.d(TAG, "Desired image size was: " + DESIRED_IMAGE_READER_SIZE + " closest size was: " + optimalSize);
                    mPreviewSize = optimalSize;

                    // Initialize Halide filters
                    HalideFilters.setImageSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                    HalideFilters.setFilterCoefficients(
                            getDoublePreference("low_cutoff", 1f),
                            getDoublePreference("high_cutoff", 2f));
                    HalideFilters.resetFilterHistory();
                    HalideFilters.setAmplification(getFloatPreference("amplification", 30f));
                    HalideFilters.setTiling(
                            getIntPreference("tile_x_factor", 80),
                            getIntPreference("tile_y_factor", 40));

                    // Create an ImageReader to receive images at that resolution.
                    mImageReader = ImageReader.newInstance(optimalSize.getWidth(), optimalSize.getHeight(),
                            ImageFormat.YUV_420_888, IMAGE_READER_BUFFER_SIZE);
                    mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

                    // Save the camera id to open later.
                    mCameraId = cameraId;
                    return;
                } else {
                    Log.e(TAG, "setUpCameraOutputs, Could not find suitable supported resolution.");
                    ErrorDialog.newInstance(getString(R.string.error_camera2_not_supported))
                            .show(getFragmentManager(), FRAGMENT_DIALOG);
                    activity.finish();
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            ErrorDialog.newInstance(getString(R.string.error_camera2_not_supported))
                    .show(getFragmentManager(), FRAGMENT_DIALOG);
            activity.finish();
        }
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the <b>largest</b>
     * one whose width and height are less than the desired ones, and whose aspect ratio matches
     * the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal {@code Size}, or null if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the desired size.
        List<Size> ok = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() <= width && option.getHeight() <= height) {
                ok.add(option);
            }
        }

        // Pick the biggest of those, assuming we found any.
        if (!ok.isEmpty()) {
            return Collections.max(ok, new CompareSizesByArea());
        } else {
            Log.e(TAG, "chooseOptimalSize: Couldn't find any suitable preview size");
            return null;
        }
    }

    private double getDoublePreference(String key, double defaultValue) {
        return Double.valueOf(getStringPreference(key, String.valueOf(defaultValue)));
    }

    private String getStringPreference(String key, String defaultValue) {
        return PreferenceManager
                .getDefaultSharedPreferences(getActivity())
                .getString(key, defaultValue);
    }

    private boolean getBooleanPreference(String key, boolean defaultValue) {
        return PreferenceManager
                .getDefaultSharedPreferences(getActivity())
                .getBoolean(key, defaultValue);
    }

    private float getFloatPreference(String key, float defaultValue) {
        return Float.valueOf(getStringPreference(key, String.valueOf(defaultValue)));
    }

    private int getIntPreference(String key, int defaultValue) {
        return Integer.valueOf(getStringPreference(key, String.valueOf(defaultValue)));
    }

    /**
     * Opens the camera specified by {@link CameraFragment#mCameraId}.
     */
    private void openCamera() {
        Log.d(TAG, "openCamera");
        if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }

        if (mCameraDevice != null) {
            Log.d(TAG, "openCamera: Camera already opened.");
            return;
        }

        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mCameraCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            showToast("Failed to open camera.");    // failed immediately.
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    private void requestCameraPermission() {
        if (FragmentCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            new CameraPermissionDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            FragmentCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {
        Log.d(TAG, "Toast: " + text);
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            // This is the output Surface we need to start preview.
            Surface surface = mImageReader.getSurface();
            assert surface != null;

            // We set up a CaptureRequest.Builder with the output Surface.
            CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mPreviewRequest = builder.build();

            // Here, we create a CameraCaptureSession for camera preview.
            Log.d(TAG, "Creating capture session");
            mCameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    mCaptureSession = session;
                    try {
                        session.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e("app", "Failed to configure camera capture session!");
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    /**
     * A small class holding 3 floating-point parameters.
     */
    static class Vector3 {
        public static final Vector3 ZERO = new Vector3(0, 0, 0);

        public final float x, y, z;

        public Vector3(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ", " + z + ")";
        }
    }
}
