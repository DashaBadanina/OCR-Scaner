package com.example.veronika.secondsight;

import android.graphics.Bitmap;
import android.os.Environment;
import android.text.format.Time;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import static org.opencv.core.Core.line;
import static org.opencv.imgproc.Imgproc.getStructuringElement;
/*
 Здесь вершится калибровка изображения
 */
 public class CallibrationImage {

    static private Mat                    frame;
    static private Mat                    mRgba;
    static private Mat                    mIntermediateMat;
    static private Mat                    mGray;
    static private Mat                    hist;
    static String folderToSave = Environment.getExternalStorageDirectory().toString()+ "/SecondSight/";
    static String folderToSavehist = Environment.getExternalStorageDirectory().toString()+ "/SecondSight/Hist/";

    static  public  Bitmap Binnarization (Bitmap bitmap, int height, int width)
    {
        mRgba = new Mat(height, width, CvType.CV_8UC1);
        mIntermediateMat = new Mat(height, width, CvType.CV_8UC1);
        frame = new Mat(height, width, CvType.CV_8UC1);
        mGray = new Mat(height, width, CvType.CV_8UC1);
        hist = new Mat();

        String textResult;

        //Построение гистограммы
        List<Mat> images = new ArrayList<Mat>();
        images.add(mGray);
        Imgproc.calcHist(images, new MatOfInt(0), new Mat(), hist, new MatOfInt(256), new MatOfFloat(0f, 256f), false);
        int hist_w = 512;
        int hist_h = 400;
        int bin_w = (int) Math.round((double) hist_w / 256);
        Mat histImage = new Mat(hist_h, hist_w, CvType.CV_8UC1);
        Core.normalize(hist, hist, 0, histImage.rows(), Core.NORM_MINMAX, -1, new Mat());
        for (int i = 1; i < 256; i++) {
            line(histImage, new Point(bin_w * (i - 1), hist_h - Math.round(hist.get(i - 1, 0)[0])),
                    new Point(bin_w * (i), hist_h - Math.round(hist.get(i, 0)[0])), new Scalar(255, 255, 255), 2, 8, 0);
        }

        Bitmap bmp = Bitmap.createBitmap(histImage.width(), histImage.height(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(histImage, bmp);
        SavePicture(bmp, folderToSavehist);

        //Бинаризация
        Imgproc.threshold(mGray, mIntermediateMat, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        Imgproc.adaptiveThreshold(mGray, mRgba, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 75, 10);
        Mat element = getStructuringElement(Imgproc.MORPH_RECT, new Size(2 * Imgproc.MORPH_RECT + 1, 2 * Imgproc.MORPH_RECT + 1),
                new Point(Imgproc.MORPH_RECT, Imgproc.MORPH_RECT));
        Imgproc.erode(mRgba, mIntermediateMat, element);
        Imgproc.threshold(mRgba, mIntermediateMat, 80, 255, Imgproc.THRESH_BINARY);
        Utils.matToBitmap(mRgba, bitmap);

        //Сохранение картинки
        SavePicture(bitmap, folderToSave);
        return bitmap;
    }


   static private String SavePicture(Bitmap bitmap, String folder)
    {
        OutputStream fOut = null;
        Time time = new Time();
        time.setToNow();

        try {
            File file = new File(folder, Integer.toString(time.year) + Integer.toString(time.month) + Integer.toString(time.monthDay) + Integer.toString(time.hour) + Integer.toString(time.minute) + Integer.toString(time.second) +".jpg"); // создать уникальное имя для файла основываясь на дате сохранения
            fOut = new FileOutputStream(file);

            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fOut); // сохранять картинку в jpeg-формате с 85% сжатия.
            fOut.flush();
            fOut.close();
            //MediaStore.Images.Media.insertImage(getContentResolver(), file.getAbsolutePath(), file.getName(),  file.getName()); // регистрация в фотоальбоме
        }
        catch (Exception e)
        {
            return e.getMessage();
        }
        return "";
    }
}
