package com.example.veronika.secondsight;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.IOException;

public class CameraManager
{
    private static final String TAG = CameraManager.class.getSimpleName();

    private static final int MIN_FRAME_WIDTH = 50;
    private static final int MIN_FRAME_HEIGHT = 20;
    private static final int MAX_FRAME_WIDTH = 800;
    private static final int MAX_FRAME_HEIGHT = 600;

    private final Context context;
    private final CameraConfigurationManager configManager;
    private Camera camera;
    private AutoFocusManager autoFocusManager;
    private Rect framingRect;
    private Rect framingRectInPreview;
    private boolean initialized;
    private boolean previewing;
    private boolean reverseImage;
    private int requestedFramingRectWidth;
    private int requestedFramingRectHeight;
    /*
    Управление камерой устройства и видеопотоком.
     */
    private final PreviewCallback previewCallback;

    public CameraManager(Context context)
    {
        this.context = context;
        this.configManager = new CameraConfigurationManager(context);
        previewCallback = new PreviewCallback(configManager);
        Log.d("CameraManager", "Create");
    }

    /*
     Открываем камеру устройства и инициализируем параметры камеры.
     */
    public synchronized void openDriver(SurfaceHolder holder) throws IOException
    {
        Camera theCamera = camera;
        if (theCamera == null)
        {
            theCamera = Camera.open();
            if (theCamera == null)
            {
                throw new IOException();
            }
            camera = theCamera;
        }
        camera.setPreviewDisplay(holder);
        if (!initialized)
        {
            initialized = true;
            configManager.initFromCameraParameters(theCamera);
            if (requestedFramingRectWidth > 0 && requestedFramingRectHeight > 0)
            {
                adjustFramingRect(requestedFramingRectWidth, requestedFramingRectHeight);
                requestedFramingRectWidth = 0;
                requestedFramingRectHeight = 0;
            }
        }
        configManager.setDesiredCameraParameters(theCamera);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    /*
    Закрываем камеру устройства.
     */
    public synchronized void closeDriver()
    {
        if (camera != null) {
            camera.release();
            camera = null;
            framingRect = null;
            framingRectInPreview = null;
        }
    }

    /*
    Начать предпросмотр.
     */
    public synchronized void startPreview()
    {
        Camera theCamera = camera;
        if (theCamera != null && !previewing)
        {
            theCamera.startPreview();
            previewing = true;
            autoFocusManager = new AutoFocusManager(context, camera);
        }
    }

    /*
    Остановить предпросмотр.
     */
    public synchronized void stopPreview()
    {
        if (autoFocusManager != null)
        {
            autoFocusManager.stop();
            autoFocusManager = null;
        }
        if (camera != null && previewing)
        {
            camera.stopPreview();
            previewCallback.setHandler(null, 0);
            previewing = false;
        }
    }

    /*
    Когда поступил запрос на распознавание текста, нажата кнопка
     */
    public synchronized void requestOcrDecode(Handler handler, int message)
    {
        Camera theCamera = camera;
        if (theCamera != null && previewing)
        {
            previewCallback.setHandler(handler, message);
            theCamera.setOneShotPreviewCallback(previewCallback);
        }
    }

    /*
     Просим сделать автофокусировку.
     */
    public synchronized void requestAutoFocus(long delay)
    {
        autoFocusManager.start(delay);
    }

    /*
    Вычисление размеров интерактивной рамки
     */
    public synchronized Rect getFramingRect()
    {
        Log.d("CameraManager", "getFraming");
        if (framingRect == null)
        {
            if (camera == null)
            {
                return null;
            }
            Point screenResolution = configManager.getScreenResolution();
            if (screenResolution == null)
            {
                return null;
            }
            int width = screenResolution.x * 3/5;
            if (width < MIN_FRAME_WIDTH)
            {
                width = MIN_FRAME_WIDTH;
            }
            else if (width > MAX_FRAME_WIDTH)
            {
                width = MAX_FRAME_WIDTH;
            }

            int height = screenResolution.y * 1/5;
            if (height < MIN_FRAME_HEIGHT) {
                height = MIN_FRAME_HEIGHT;
            } else if (height > MAX_FRAME_HEIGHT) {
                height = MAX_FRAME_HEIGHT;
            }
            int leftOffset = (screenResolution.x - width) / 2;
            int topOffset = (screenResolution.y - height) / 2;
            framingRect = new Rect(leftOffset, topOffset, leftOffset + width, topOffset + height);
        }
        return framingRect;
    }

    public synchronized Rect getFramingRectInPreview()
    {
        if (framingRectInPreview == null) {
            Rect rect = new Rect(getFramingRect());
            Point cameraResolution = configManager.getCameraResolution();
            Point screenResolution = configManager.getScreenResolution();
            if (cameraResolution == null || screenResolution == null) {
                return null;
            }
            rect.left = rect.left * cameraResolution.x / screenResolution.x;
            rect.right = rect.right * cameraResolution.x / screenResolution.x;
            rect.top = rect.top * cameraResolution.y / screenResolution.y;
            rect.bottom = rect.bottom * cameraResolution.y / screenResolution.y;
            framingRectInPreview = rect;
        }
        return framingRectInPreview;
    }

    /*
    Измнение размера интерактивной рамки
     */
    public synchronized void adjustFramingRect(int deltaWidth, int deltaHeight)
    {
        if (initialized)
        {
            Point screenResolution = configManager.getScreenResolution();
            if ((framingRect.width() + deltaWidth > screenResolution.x - 4) || (framingRect.width() + deltaWidth < 50))
            {
                deltaWidth = 0;
            }
            if ((framingRect.height() + deltaHeight > screenResolution.y - 4) || (framingRect.height() + deltaHeight < 50))
            {
                deltaHeight = 0;
            }

            int newWidth = framingRect.width() + deltaWidth;
            int newHeight = framingRect.height() + deltaHeight;
            int leftOffset = (screenResolution.x - newWidth) / 2;
            int topOffset = (screenResolution.y - newHeight) / 2;
            framingRect = new Rect(leftOffset, topOffset, leftOffset + newWidth, topOffset + newHeight);
            framingRectInPreview = null;
        } else
        {
            requestedFramingRectWidth = deltaWidth;
            requestedFramingRectHeight = deltaHeight;
        }
    }

/*
 Вырезаем часть обрамленную интерактивной рамкой из кадра
 */
    public PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height)
    {
        Rect rect = getFramingRectInPreview();
        if (rect == null)
        {
            return null;
        }
        return new PlanarYUVLuminanceSource(data, width, height, rect.left, rect.top,
                rect.width(), rect.height(), reverseImage);
    }

}
