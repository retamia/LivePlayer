package org.retamia.lalafell.record.source;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import android.util.Log;
import android.util.Size;
import android.util.SparseArray;
import android.view.Surface;

import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.retamia.lalafell.record.media.Frame;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CameraSource extends Source {

    private final static String TAG = CameraSource.class.getName();

    private String[] permissions = {Manifest.permission.CAMERA};

    private CameraDevice cameraDevice;
    private CameraManager cameraManager;
    private Context context;

    private SparseArray<String> cameraOrientationMap;

    private ImageReader cameraImageReader;
    private Surface     cameraImageReaderSurface;

    private HandlerThread cameraHandlerThread;
    private Handler cameraHandler;

    private boolean inited = false;

    public CameraSource(Context context) {
        this.context = context;
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        cameraOrientationMap = new SparseArray<>();
    }

    public synchronized void init(Handler handler) {
        try {
            for (String cameraId: cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                if (facing == null) {
                    Log.e(TAG, "Camera facing is null");
                    return;
                }

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }

                cameraOrientationMap.put(facing, cameraId);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return;
        }

        cameraHandlerThread = new HandlerThread("CameraSource Handler Thread");
        cameraHandlerThread.start();
        cameraHandler = new Handler(cameraHandlerThread.getLooper());

        cameraImageReader = ImageReader.newInstance(1080, 1920, ImageFormat.YUV_420_888, 10);
        cameraImageReader.setOnImageAvailableListener(onImageAvailableListener, cameraHandler);

        cameraImageReaderSurface = cameraImageReader.getSurface();


        inited = true;
    }

    /*//@TODO 权限这样做有点问题，先暂时实现功能，后续再调整
    @Override
    public boolean requestPermission() {

        int result = ActivityCompat.checkSelfPermission(context, permissions[0]);

        if (result != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((Activity) context, permissions, 1);
            return false;
        }

        return true;
    }*/


    @Override
    public void open() {

        if (!inited) {

            if (listener != null) {
                listener.onOpenError(this,"not init");
            }

            return;
        }

        if (ActivityCompat.checkSelfPermission(context, permissions[0]) != PackageManager.PERMISSION_GRANTED) {
            //requestPermission();
            //@TODO 权限这样做有点问题，先暂时实现功能，后续再调整
            return;
        }

        try {
            cameraManager.openCamera(cameraOrientationMap.get(CameraCharacteristics.LENS_FACING_BACK), stateCallback, null);
        } catch (CameraAccessException e) {

            if (listener != null) {
                listener.onOpenError(this, e.getMessage());
            }

            e.printStackTrace();
        }
    }

    @Override
    public void close() {

        if (!inited) {
            return;
        }

        if (cameraImageReader != null) {
            cameraImageReader.close();
        }

        if (cameraDevice != null) {
            cameraDevice.close();
        }
    }

    @Override
    public void release() {

    }

    @Override
    public String getName() {
        return "摄像头";
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {

            android.media.Image nativeImage = reader.acquireLatestImage();

            if (nativeImage == null) {
                return;
            }

            if (CameraSource.this.listener == null) {
                return;
            }

            try {
                android.media.Image.Plane[] plane = nativeImage.getPlanes();

                Frame outputImage = new Frame();

                int []rowStrides = {
                        plane[0].getRowStride() / plane[0].getPixelStride(),
                        plane[1].getRowStride() / plane[1].getPixelStride(),
                        plane[2].getRowStride() / plane[2].getPixelStride()
                };

                byte []yBuffer = new byte[rowStrides[0] * nativeImage.getHeight()];
                byte []uBuffer = new byte[rowStrides[1] * nativeImage.getHeight() / 2];
                byte []vBuffer = new byte[rowStrides[2] * nativeImage.getHeight() / 2];

                plane[0].getBuffer().get(yBuffer);
                //@TODO Android Camera2 YUV420 UV 数据格式为 U0U0U0 V0V0V0

                for (int i = 0; i < uBuffer.length; i++) {
                    uBuffer[i] = plane[1].getBuffer().get(i * plane[1].getPixelStride());
                    vBuffer[i] = plane[2].getBuffer().get(i * plane[2].getPixelStride());
                }

                byte [][]yuvBuffer = {yBuffer, uBuffer, vBuffer};

                outputImage.setPlane(plane.length);
                outputImage.setRowStride(rowStrides);
                outputImage.setFormat(Frame.PixelFmt.YUV420P);
                outputImage.setHeight(nativeImage.getHeight());
                outputImage.setWidth(nativeImage.getWidth());
                outputImage.setData(yuvBuffer);
                outputImage.setPresentationNanoTime(nativeImage.getTimestamp());
                nativeImage.close();

                //test: yuv 图片可以查看，YUV Player https://blog.csdn.net/leixiaohua1020/article/details/50466201
            /*{
                File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File yuvFile = new File(downloadFolder, "test.yuv");

                if (yuvFile.exists()) {
                    yuvFile.delete();
                }

                try {
                    FileOutputStream fos = new FileOutputStream(yuvFile.getPath());
                    fos.write(outputImage.getData()[0]);
                    fos.write(outputImage.getData()[1]);
                    fos.write(outputImage.getData()[2]);
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "save yuv file error");
                }
            }*/

                outputImage.setRotation(new Quaternionf(new AxisAngle4f(270, 0, 0, 1)));

                CameraSource.this.listener.onImageDataAvailable(CameraSource.this, outputImage);
            } catch (IllegalStateException e) {
                Log.d(TAG, "image reader close");
            }
        }
    };

    private final CameraCaptureSession.StateCallback captureSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {

            if (CameraSource.this.cameraDevice == null) {
                return;
            }

            try {
                CaptureRequest.Builder requestBuilder = session.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                requestBuilder.addTarget(cameraImageReaderSurface);
                //requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION));
                session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);

                Log.d(TAG, "CameraCaptureSession.StateCallback onConfigured success");
            } catch (CameraAccessException e) {
                Log.e(TAG, "CameraCaptureSession.StateCallback onConfigured: CameraAccessException");
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "CameraCaptureSession.StateCallback onConfigureFailed");
        }
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            CameraSource.this.cameraDevice = cameraDevice;
            Log.d(TAG, "StateCallback onOpened: " + CameraSource.this.cameraDevice.getId());
            List<Surface> surfaceList = new ArrayList<>();
            surfaceList.add(cameraImageReaderSurface);
            try {
                CameraSource.this.cameraDevice.createCaptureSession(surfaceList, captureSessionStateCallback, cameraHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "CameraDevice.StateCallback onOpened: CameraAccessException");
                e.printStackTrace();
            }
            //createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            //mCameraOpenCloseLock.release();
            cameraDevice.close();
            CameraSource.this.cameraDevice = null;
            Log.d(TAG, "CameraDevice.StateCallback onDisconnected: " + cameraDevice.getId());
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            //mCameraOpenCloseLock.release();
            cameraDevice.close();
            CameraSource.this.cameraDevice = null;
            Log.d(TAG, "CameraDevice.StateCallback onError: " + cameraDevice.getId());
        }

    };
}
