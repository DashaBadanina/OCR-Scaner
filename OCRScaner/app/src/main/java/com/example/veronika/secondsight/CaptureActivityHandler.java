package com.example.veronika.secondsight;

import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;

final class CaptureActivityHandler extends Handler {

  private static final String TAG = CaptureActivityHandler.class.getSimpleName();
  
  private final CaptureActivity activity;
  private final DecodeThread decodeThread;
  private static State state;
  private final CameraManager cameraManager;

  private enum State {
    PREVIEW,
    PREVIEW_PAUSED,
    CONTINUOUS,
    CONTINUOUS_PAUSED,
    SUCCESS,
    DONE
  }

  CaptureActivityHandler(CaptureActivity activity, CameraManager cameraManager)
  {
    this.activity = activity;
    this.cameraManager = cameraManager;

    // Начать Preview
    cameraManager.startPreview();
    
    decodeThread = new DecodeThread(activity);
    decodeThread.start();
      state = State.SUCCESS;
      
      // Сделать кнопку видимой
      activity.setButtonVisibility(true);
      
  }

  @Override
  public void handleMessage(Message message)
  {
    
    switch (message.what) {
      case R.id.restart_preview:
        break;
      case R.id.ocr_continuous_decode_failed:
        DecodeHandler.resetDecodeState();        
        try {
         // activity.handleOcrContinuousDecode((OcrResultFailure) message.obj);
        } catch (NullPointerException e) {
          Log.w(TAG, "got bad OcrResultFailure", e);
        }
        if (state == State.CONTINUOUS) {
          //restartOcrPreviewAndDecode();
        }
        break;
      case R.id.ocr_continuous_decode_succeeded:
        DecodeHandler.resetDecodeState();
        try {
          //activity.handleOcrContinuousDecode((OcrResult) message.obj);
        } catch (NullPointerException e) {
          // Continue
        }
        if (state == State.CONTINUOUS) {
          //restartOcrPreviewAndDecode();
        }
        break;
      case R.id.ocr_decode_succeeded:
        state = State.SUCCESS;
        activity.setShutterButtonClickable(true);
        activity.handleOcrDecode((OcrResult) message.obj);
        break;
      case R.id.ocr_decode_failed:
        state = State.PREVIEW;
        activity.setShutterButtonClickable(true);
        Toast toast = Toast.makeText(activity.getBaseContext(), "OCR failed. Please try again.", Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.TOP, 0, 0);
        toast.show();
        break;
    }
  }
  
  void stop() {
    Log.d(TAG, "Setting state to CONTINUOUS_PAUSED.");
    state = State.CONTINUOUS_PAUSED;
    removeMessages(R.id.ocr_continuous_decode);
    removeMessages(R.id.ocr_decode);
    removeMessages(R.id.ocr_continuous_decode_failed);
    removeMessages(R.id.ocr_continuous_decode_succeeded);
  }
  
  void resetState() {
    if (state == State.CONTINUOUS_PAUSED) {
      Log.d(TAG, "Setting state to CONTINUOUS");
      state = State.CONTINUOUS;
    }
  }
  
  void quitSynchronously()
  {
    state = State.DONE;
    if (cameraManager != null) {
      cameraManager.stopPreview();
    }
    try {
      decodeThread.join(500L);
    } catch (InterruptedException e) {
      Log.w(TAG, "Caught InterruptedException in quitSyncronously()", e);
      // continue
    } catch (RuntimeException e) {
      Log.w(TAG, "Caught RuntimeException in quitSyncronously()", e);
      // continue
    } catch (Exception e) {
      Log.w(TAG, "Caught unknown Exception in quitSynchronously()", e);
    }

    removeMessages(R.id.ocr_continuous_decode);
    removeMessages(R.id.ocr_decode);

  }

  private void ocrDecode()
  {
    cameraManager.requestOcrDecode(decodeThread.getHandler(), R.id.ocr_decode);
  }
  
  void shutterButtonClick()
  {
    activity.setShutterButtonClickable(false);
    ocrDecode();
  }

}
