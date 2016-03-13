package com.example.veronika.secondsight;
import com.googlecode.tesseract.android.TessBaseAPI;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
/**
 «десь происходит отправка изображени€ текста на распознавание
 */
final class DecodeHandler extends Handler
{

  private final CaptureActivity activity;
  private boolean running = true;
  private final TessBaseAPI baseApi;

  private Bitmap bitmap;
  private static boolean isDecodePending;
  private long timeRequired;

  DecodeHandler(CaptureActivity activity)
  {
    this.activity = activity;
    baseApi = activity.getBaseApi();
  }

  @Override
  public void handleMessage(Message message)
  {
    if (!running) {
      return;
    }
    switch (message.what) {        
   /* case R.id.ocr_continuous_decode:
      if (!isDecodePending) {
        isDecodePending = true;
       // ocrContinuousDecode((byte[]) message.obj, message.arg1, message.arg2);
      }
      break;*/
    case R.id.ocr_decode:
      ocrDecode((byte[]) message.obj, message.arg1, message.arg2);
      break;
    case R.id.quit:
      running = false;
      Looper.myLooper().quit();
      break;
    }
  }

  static void resetDecodeState() {
    isDecodePending = false;
  }

  /**
   «апуск AsyncTask дл€ задачи OCR.
   *  
   * @param data массив изображени€
   * @param width ширина
   * @param height высота
   */
  private void ocrDecode(byte[] data, int width, int height)
  {
    activity.displayProgressDialog();
    new OcrRecognizeAsyncTask(activity, baseApi, data, width, height).execute();
  }


}












