package com.example.veronika.secondsight;

import java.io.File;
import java.io.IOException;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.text.SpannableStringBuilder;
import android.text.style.CharacterStyle;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.googlecode.tesseract.android.TessBaseAPI;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;


/*
  Здесь запускается видеопоток, отрисовыватся интерактивная рамка для помещения в нее текста.
 */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback, 
  ShutterButton.OnShutterButtonListener
{

  private static final String TAG = CaptureActivity.class.getSimpleName();

  /* Язык используемый по умолчанию. */
  public static final String DEFAULT_SOURCE_LANGUAGE_CODE = "rus";
  public static final String DEFAULT_OCR_ENGINE_MODE = "Tesseract";
  
  /* Задаем способ сегментации изображения. */
  public static final String DEFAULT_PAGE_SEGMENTATION_MODE = "Auto";
  
  /* Использовать автофокусировку по умолчанию. */
  public static final boolean DEFAULT_TOGGLE_AUTO_FOCUS = true;
  

  /* Показывать результат распознавания в вверхем углу экрана. */
  private static final boolean CONTINUOUS_DISPLAY_RECOGNIZED_TEXT = true;

  /* Флаг активации кнопки. */
  private static final boolean DISPLAY_SHUTTER_BUTTON = true;
  

  // Контекстное меню
  private static final int SETTINGS_ID = Menu.FIRST;
  private static final int ABOUT_ID = Menu.FIRST + 1;

  private static final int OPTIONS_COPY_RECOGNIZED_TEXT_ID = Menu.FIRST;
  private static final int OPTIONS_COPY_TRANSLATED_TEXT_ID = Menu.FIRST + 1;
  private static final int OPTIONS_SHARE_RECOGNIZED_TEXT_ID = Menu.FIRST + 2;
  private static final int OPTIONS_SHARE_TRANSLATED_TEXT_ID = Menu.FIRST + 3;

  private CameraManager cameraManager;
  private CaptureActivityHandler handler;
  private ViewFinderView viewfinderView;
  private SurfaceView surfaceView;
  private SurfaceHolder surfaceHolder;
  private TextView statusViewBottom;
  private TextView statusViewTop;
  private TextView ocrResultView;
  private View cameraButtonView;
  private View resultView;
  private OcrResult lastResult;
  private boolean hasSurface;
  private TessBaseAPI baseApi; // Java интерфейс для Tesseract OCR engine
  private String sourceLanguageCodeOcr; // Код языка
  private String sourceLanguageReadable; // Имя языка, типо "Russian"
  private int pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_AUTO_OSD;
  private int ocrEngineMode = TessBaseAPI.OEM_TESSERACT_ONLY;
  private String characterBlacklist;
  private String characterWhitelist;
  private ShutterButton shutterButton;
  private SharedPreferences prefs;
  private OnSharedPreferenceChangeListener listener;
  private ProgressDialog dialog;
  private ProgressDialog indeterminateDialog;
  private boolean isEngineReady;
  private boolean isPaused;
  private static boolean isFirstLaunch; // Первый запуск приложения
  private Mat frame;

  Handler getHandler() {
    return handler;
  }

  TessBaseAPI getBaseApi() {
    return baseApi;
  }
  
  CameraManager getCameraManager() {
    return cameraManager;
  }
  
  @Override
  public void onCreate(Bundle icicle)
  {
    super.onCreate(icicle);

    if (isFirstLaunch) {
      setDefaultPreferences();
    }
    
    Window window = getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
    setContentView(R.layout.capture);
    viewfinderView = (ViewFinderView) findViewById(R.id.viewfinder_view);
    cameraButtonView = findViewById(R.id.camera_button_view);
    resultView = findViewById(R.id.result_view);
    
    statusViewBottom = (TextView) findViewById(R.id.status_view_bottom);
    registerForContextMenu(statusViewBottom);
    statusViewTop = (TextView) findViewById(R.id.status_view_top);
    registerForContextMenu(statusViewTop);
    
    handler = null;
    lastResult = null;
    hasSurface = false;
    
    // Кнопка по которой делается снимок и происходит распознавание
    if (DISPLAY_SHUTTER_BUTTON)
    {
      shutterButton = (ShutterButton) findViewById(R.id.shutter_button);
      shutterButton.setOnShutterButtonListener(this);
    }
   
    ocrResultView = (TextView) findViewById(R.id.ocr_result_text_view);
    registerForContextMenu(ocrResultView);

    cameraManager = new CameraManager(getApplication());
    viewfinderView.setCameraManager(cameraManager);
    
    // Лисенер для интеректавной рамки
    viewfinderView.setOnTouchListener(new View.OnTouchListener()
    {
      int lastX = -1;
      int lastY = -1;

      @Override
      public boolean onTouch(View v, MotionEvent event)
      {

        switch (event.getAction())
        {
        case MotionEvent.ACTION_DOWN:
          lastX = -1;
          lastY = -1;
          return true;
        case MotionEvent.ACTION_MOVE:
          int currentX = (int) event.getX();
          int currentY = (int) event.getY();

          try
          {
            Rect rect = cameraManager.getFramingRect();

            final int BUFFER = 50;
            final int BIG_BUFFER = 60;
            if (lastX >= 0)
            {
              //Регулиравка интерактивной рамки
              if (((currentX >= rect.left - BIG_BUFFER && currentX <= rect.left + BIG_BUFFER) || (lastX >= rect.left - BIG_BUFFER && lastX <= rect.left + BIG_BUFFER))
                  && ((currentY <= rect.top + BIG_BUFFER && currentY >= rect.top - BIG_BUFFER) || (lastY <= rect.top + BIG_BUFFER && lastY >= rect.top - BIG_BUFFER)))
              {
                // Верхний левый угол: регулировка верхней и левой сторон
                cameraManager.adjustFramingRect( 2 * (lastX - currentX), 2 * (lastY - currentY));
              }
              else if (((currentX >= rect.right - BIG_BUFFER && currentX <= rect.right + BIG_BUFFER) || (lastX >= rect.right - BIG_BUFFER && lastX <= rect.right + BIG_BUFFER))
                  && ((currentY <= rect.top + BIG_BUFFER && currentY >= rect.top - BIG_BUFFER) || (lastY <= rect.top + BIG_BUFFER && lastY >= rect.top - BIG_BUFFER)))
              {
                // Верхний правый угол: регулировка верхней и правой сторон
                cameraManager.adjustFramingRect( 2 * (currentX - lastX), 2 * (lastY - currentY));
              } else if (((currentX >= rect.left - BIG_BUFFER && currentX <= rect.left + BIG_BUFFER) || (lastX >= rect.left - BIG_BUFFER && lastX <= rect.left + BIG_BUFFER))
                  && ((currentY <= rect.bottom + BIG_BUFFER && currentY >= rect.bottom - BIG_BUFFER) || (lastY <= rect.bottom + BIG_BUFFER && lastY >= rect.bottom - BIG_BUFFER))) {
                // Нижний левый угол: регулировка нижней и левой сторон
                cameraManager.adjustFramingRect(2 * (lastX - currentX), 2 * (currentY - lastY));
              } else if (((currentX >= rect.right - BIG_BUFFER && currentX <= rect.right + BIG_BUFFER) || (lastX >= rect.right - BIG_BUFFER && lastX <= rect.right + BIG_BUFFER)) 
                  && ((currentY <= rect.bottom + BIG_BUFFER && currentY >= rect.bottom - BIG_BUFFER) || (lastY <= rect.bottom + BIG_BUFFER && lastY >= rect.bottom - BIG_BUFFER))) {
                // Нижний правый угол: регулировка нижней и правой сторон
                cameraManager.adjustFramingRect(2 * (currentX - lastX), 2 * (currentY - lastY));
              } else if (((currentX >= rect.left - BUFFER && currentX <= rect.left + BUFFER) || (lastX >= rect.left - BUFFER && lastX <= rect.left + BUFFER))
                  && ((currentY <= rect.bottom && currentY >= rect.top) || (lastY <= rect.bottom && lastY >= rect.top))) {
                // Регулировка левой
                cameraManager.adjustFramingRect(2 * (lastX - currentX), 0);
              } else if (((currentX >= rect.right - BUFFER && currentX <= rect.right + BUFFER) || (lastX >= rect.right - BUFFER && lastX <= rect.right + BUFFER))
                  && ((currentY <= rect.bottom && currentY >= rect.top) || (lastY <= rect.bottom && lastY >= rect.top))) {
                // Регулировка правой стороны
                cameraManager.adjustFramingRect(2 * (currentX - lastX), 0);
              } else if (((currentY <= rect.top + BUFFER && currentY >= rect.top - BUFFER) || (lastY <= rect.top + BUFFER && lastY >= rect.top - BUFFER))
                  && ((currentX <= rect.right && currentX >= rect.left) || (lastX <= rect.right && lastX >= rect.left))) {
                // Регулировка верхней стороны
                cameraManager.adjustFramingRect(0, 2 * (lastY - currentY));
              } else if (((currentY <= rect.bottom + BUFFER && currentY >= rect.bottom - BUFFER) || (lastY <= rect.bottom + BUFFER && lastY >= rect.bottom - BUFFER))
                  && ((currentX <= rect.right && currentX >= rect.left) || (lastX <= rect.right && lastX >= rect.left))) {
                // Регулировка нижней стороны
                cameraManager.adjustFramingRect(0, 2 * (currentY - lastY));
              }     
            }
          } catch (NullPointerException e) {
            Log.e(TAG, "Framing rect not available", e);
          }
          v.invalidate();
          lastX = currentX;
          lastY = currentY;
          return true;
        case MotionEvent.ACTION_UP:
          lastX = -1;
          lastY = -1;
          return true;
        }
        return false;
      }
    });
    
    isEngineReady = false;
  }
  //Callback метод срабатывающий при инициализации OpenCV
    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this)
    {
        public String TAG;

        @Override
        public void onManagerConnected(int status)
        {
            switch (status)
            {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(TAG, "OpenCV loaded successfully");
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };
  @Override
  protected void onResume()
  {
    super.onResume();
    //Инициализаци OpenCV
    OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_6, this, mLoaderCallback);
    resetStatusView();
    
    String previousSourceLanguageCodeOcr = sourceLanguageCodeOcr;
    int previousOcrEngineMode = ocrEngineMode;
    
    retrievePreferences();
    
    // Устанавливаем camera preview surface.
    surfaceView = (SurfaceView) findViewById(R.id.preview_view);
    surfaceHolder = surfaceView.getHolder();
    if (!hasSurface)
    {
      surfaceHolder.addCallback(this);
      surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    //Инициализации Tesseract
    boolean doNewInit = (baseApi == null) || !sourceLanguageCodeOcr.equals(previousSourceLanguageCodeOcr) || 
        ocrEngineMode != previousOcrEngineMode;
    if (doNewInit) {
      String storageDirectory = getStorageDirectory();
      if (storageDirectory != null) {
        initOcrEngine(storageDirectory, sourceLanguageCodeOcr, sourceLanguageReadable);
      }
    } else {
      resumeOCR();
    }
  }
  
  void resumeOCR()
  {
    Log.d(TAG, "resumeOCR()");

    isEngineReady = true;
    
    isPaused = false;

    if (handler != null) {
      handler.resetState();
    }
    if (baseApi != null)
    {
      baseApi.setPageSegMode(pageSegmentationMode);
      baseApi.setVariable(TessBaseAPI.VAR_CHAR_BLACKLIST, characterBlacklist);
      baseApi.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, characterWhitelist);
    }

    if (hasSurface)
    {
      initCamera(surfaceHolder);
    }
  }
  
  @Override
  public void surfaceCreated(SurfaceHolder holder)
  {
    Log.d(TAG, "surfaceCreated()");
    
    if (holder == null)
    {
      Log.e(TAG, "surfaceCreated gave us a null surface");
    }
    
    // Инициализаци камеры
    if (!hasSurface && isEngineReady)
    {
      Log.d(TAG, "surfaceCreated(): calling initCamera()...");
      initCamera(holder);
    }
    hasSurface = true;
  }
  private void initCamera(SurfaceHolder surfaceHolder)
  {
    Log.d(TAG, "initCamera()");
    if (surfaceHolder == null) {
      throw new IllegalStateException("No SurfaceHolder provided");
    }
    try
    {
      cameraManager.openDriver(surfaceHolder);
      handler = new CaptureActivityHandler(this, cameraManager);

    }
    catch (IOException ioe)
    {
      showErrorMessage("Error", "Could not initialize camera. Please try restarting device.");
    }
    catch (RuntimeException e)
    {
      showErrorMessage("Error", "Could not initialize camera. Please try restarting device.");
    }   
  }
  
  @Override
  protected void onPause()
  {
    if (handler != null) {
      handler.quitSynchronously();
    }
    cameraManager.closeDriver();
    if (!hasSurface) {
      SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
      SurfaceHolder surfaceHolder = surfaceView.getHolder();
      surfaceHolder.removeCallback(this);
    }
    super.onPause();
  }

  void stopHandler() {
    if (handler != null) {
      handler.stop();
    }
  }

  @Override
  protected void onDestroy() {
    if (baseApi != null) {
      baseApi.end();
    }
    super.onDestroy();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event)
  {
    if (keyCode == KeyEvent.KEYCODE_BACK)
    {
      if (isPaused)
      {
        Log.d(TAG, "only resuming continuous recognition, not quitting...");
        return true;
      }
      if (lastResult == null)
      {
        setResult(RESULT_CANCELED);
        finish();
        return true;
      }
      else
      {
        resetStatusView();
        if (handler != null)
        {
          handler.sendEmptyMessage(R.id.restart_preview);
        }
        return true;
      }
    }
    else if (keyCode == KeyEvent.KEYCODE_CAMERA)
    {

      return true;
    }
    else if (keyCode == KeyEvent.KEYCODE_FOCUS)
    {
      if (event.getRepeatCount() == 0)
      {
        cameraManager.requestAutoFocus(500L);
      }
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    super.onCreateOptionsMenu(menu);
    menu.add(0, SETTINGS_ID, 0, "Settings").setIcon(android.R.drawable.ic_menu_preferences);
    menu.add(0, ABOUT_ID, 0, "About").setIcon(android.R.drawable.ic_menu_info_details);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item)
  {
    Intent intent;
    switch (item.getItemId()) {
    case SETTINGS_ID: {
      intent = new Intent().setClass(this, PreferencesActivity.class);
      startActivity(intent);
      break;
    }
    case ABOUT_ID: {
     /* intent = new Intent(this, HelpActivity.class);
      intent.putExtra(HelpActivity.REQUESTED_PAGE_KEY, HelpActivity.ABOUT_PAGE);
      startActivity(intent);*/
      break;
    }
    }
    return super.onOptionsItemSelected(item);
  }

  public void surfaceDestroyed(SurfaceHolder holder) {
    hasSurface = false;
  }

  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
  }
  private boolean setSourceLanguage(String languageCode)
  {
    sourceLanguageCodeOcr = "rus";
    sourceLanguageReadable = "Russian";
    return true;
  }
  private String getStorageDirectory()
  {
    return Environment.getExternalStorageDirectory().toString();
  }

  private void initOcrEngine(String storageRoot, String languageCode, String languageName)
  {
    isEngineReady = false;
    if (dialog != null) {
      dialog.dismiss();
    }
    dialog = new ProgressDialog(this);

    if (ocrEngineMode != TessBaseAPI.OEM_TESSERACT_ONLY) {
      boolean cubeOk = false;
      if (!cubeOk) {
        ocrEngineMode = TessBaseAPI.OEM_TESSERACT_ONLY;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putString(PreferencesActivity.KEY_OCR_ENGINE_MODE, getOcrEngineModeName()).commit();
      }
    }

    indeterminateDialog = new ProgressDialog(this);
    indeterminateDialog.setTitle("Please wait");
    String ocrEngineModeName = getOcrEngineModeName();
    if (ocrEngineModeName.equals("Both")) {
      indeterminateDialog.setMessage("Initializing Cube and Tesseract OCR engines for " + languageName + "...");
    } else {
      indeterminateDialog.setMessage("Initializing " + ocrEngineModeName + " OCR engine for " + languageName + "...");
    }
    indeterminateDialog.setCancelable(false);
    indeterminateDialog.show();
    
    if (handler != null) {
      handler.quitSynchronously();     
    }

    if (ocrEngineMode == TessBaseAPI.OEM_CUBE_ONLY || ocrEngineMode == TessBaseAPI.OEM_TESSERACT_CUBE_COMBINED) {
      Log.d(TAG, "Disabling continuous preview");
      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
      prefs.edit().putBoolean(PreferencesActivity.KEY_CONTINUOUS_PREVIEW, false);
    }
    
    baseApi = new TessBaseAPI();
    baseApi.init(storageRoot + File.separator, languageCode, ocrEngineMode);
  }
  
  /*
   Отображение результата распознавания.
   */
  boolean handleOcrDecode(OcrResult ocrResult)
  {
    lastResult = ocrResult;
    
    // Если результат null
    if (ocrResult.getText() == null || ocrResult.getText().equals("")) {
      Toast toast = Toast.makeText(this, "OCR failed. Please try again.", Toast.LENGTH_SHORT);
      toast.setGravity(Gravity.TOP, 0, 0);
      toast.show();
      return false;
    }
    shutterButton.setVisibility(View.GONE);
    statusViewBottom.setVisibility(View.GONE);
    statusViewTop.setVisibility(View.GONE);
    cameraButtonView.setVisibility(View.GONE);
    viewfinderView.setVisibility(View.GONE);
    resultView.setVisibility(View.VISIBLE);

    // Отображение распознанного текста
    TextView ocrResultTextView = (TextView) findViewById(R.id.ocr_result_text_view);
    ocrResultTextView.setText(ocrResult.getText());

    int scaledSize = Math.max(22, 32 - ocrResult.getText().length() / 4);
    ocrResultTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);
    return true;
  }
  
    private CharSequence setSpanBetweenTokens(CharSequence text, String token,
      CharacterStyle... cs) {
    int tokenLen = token.length();
    int start = text.toString().indexOf(token) + tokenLen;
    int end = text.toString().indexOf(token, start);

    if (start > -1 && end > -1) {
      SpannableStringBuilder ssb = new SpannableStringBuilder(text);
      for (CharacterStyle c : cs)
        ssb.setSpan(c, start, end, 0);
      text = ssb;
    }
    return text;
  }
  
  @Override
  public void onCreateContextMenu(ContextMenu menu, View v,
      ContextMenuInfo menuInfo) {
    super.onCreateContextMenu(menu, v, menuInfo);
    if (v.equals(ocrResultView)) {
      menu.add(Menu.NONE, OPTIONS_COPY_RECOGNIZED_TEXT_ID, Menu.NONE, "Copy recognized text");
      menu.add(Menu.NONE, OPTIONS_SHARE_RECOGNIZED_TEXT_ID, Menu.NONE, "Share recognized text");
    }
  }

  @Override
  public boolean onContextItemSelected(MenuItem item) {
    ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    switch (item.getItemId()) {

    case OPTIONS_COPY_RECOGNIZED_TEXT_ID:
        clipboardManager.setText(ocrResultView.getText());
      if (clipboardManager.hasText()) {
        Toast toast = Toast.makeText(this, "Text copied.", Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM, 0, 0);
        toast.show();
      }
      return true;
    case OPTIONS_SHARE_RECOGNIZED_TEXT_ID:
    	Intent shareRecognizedTextIntent = new Intent(Intent.ACTION_SEND);
    	shareRecognizedTextIntent.setType("text/plain");
    	shareRecognizedTextIntent.putExtra(Intent.EXTRA_TEXT, ocrResultView.getText());
    	startActivity(Intent.createChooser(shareRecognizedTextIntent, "Share via"));
    	return true;
    default:
      return super.onContextItemSelected(item);
    }
  }

  private void resetStatusView() {
    resultView.setVisibility(View.GONE);

    if (CONTINUOUS_DISPLAY_RECOGNIZED_TEXT) {
      statusViewTop.setText("");
      statusViewTop.setTextSize(14);
      statusViewTop.setVisibility(View.VISIBLE);
    }
    viewfinderView.setVisibility(View.VISIBLE);
    cameraButtonView.setVisibility(View.VISIBLE);
    if (DISPLAY_SHUTTER_BUTTON) {
      shutterButton.setVisibility(View.VISIBLE);
    }
  }
  
  /* Текущий распознаваемый язык*/
  void showLanguageName() {   
    Toast toast = Toast.makeText(this, "OCR: " + sourceLanguageCodeOcr, Toast.LENGTH_LONG);
    toast.setGravity(Gravity.TOP, 0, 0);
    toast.show();
  }
  
  void setButtonVisibility(boolean visible)
  {
    if (shutterButton != null && visible == true && DISPLAY_SHUTTER_BUTTON)
    {
      shutterButton.setVisibility(View.VISIBLE);
    } else if (shutterButton != null) {
      shutterButton.setVisibility(View.GONE);
    }
  }

  void setShutterButtonClickable(boolean clickable) {
    shutterButton.setClickable(clickable);
  }

  @Override
  public void onShutterButtonClick(ShutterButton b)
  {

      if (handler != null) {
        handler.shutterButtonClick();
      }

  }

  @Override
  public void onShutterButtonFocus(ShutterButton b, boolean pressed) {
    requestDelayedAutoFocus();
  }
  
  private void requestDelayedAutoFocus() {
    cameraManager.requestAutoFocus(350L);
  }
  
  String getOcrEngineModeName() {
    String ocrEngineModeName = "";
    String[] ocrEngineModes = getResources().getStringArray(R.array.ocrenginemodes);
    if (ocrEngineMode == TessBaseAPI.OEM_TESSERACT_ONLY) {
      ocrEngineModeName = ocrEngineModes[0];
    } else if (ocrEngineMode == TessBaseAPI.OEM_CUBE_ONLY) {
      ocrEngineModeName = ocrEngineModes[1];
    } else if (ocrEngineMode == TessBaseAPI.OEM_TESSERACT_CUBE_COMBINED) {
      ocrEngineModeName = ocrEngineModes[2];
    }
    return ocrEngineModeName;
  }
  
  private void retrievePreferences()
  {
      prefs = PreferenceManager.getDefaultSharedPreferences(this);

      PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
      setSourceLanguage(prefs.getString(PreferencesActivity.KEY_SOURCE_LANGUAGE_PREFERENCE, CaptureActivity.DEFAULT_SOURCE_LANGUAGE_CODE));
      String[] pageSegmentationModes = getResources().getStringArray(R.array.pagesegmentationmodes);
      String pageSegmentationModeName = prefs.getString(PreferencesActivity.KEY_PAGE_SEGMENTATION_MODE, pageSegmentationModes[0]);
      if (pageSegmentationModeName.equals(pageSegmentationModes[0])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_AUTO_OSD;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[1])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_AUTO;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[2])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[3])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_CHAR;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[4])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[5])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[6])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_WORD;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[7])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK_VERT_TEXT;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[8])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT;
      }

      String[] ocrEngineModes = getResources().getStringArray(R.array.ocrenginemodes);
      String ocrEngineModeName = prefs.getString(PreferencesActivity.KEY_OCR_ENGINE_MODE, ocrEngineModes[0]);
      if (ocrEngineModeName.equals(ocrEngineModes[0])) {
        ocrEngineMode = TessBaseAPI.OEM_TESSERACT_ONLY;
      } else if (ocrEngineModeName.equals(ocrEngineModes[1])) {
        ocrEngineMode = TessBaseAPI.OEM_CUBE_ONLY;
      } else if (ocrEngineModeName.equals(ocrEngineModes[2])) {
        ocrEngineMode = TessBaseAPI.OEM_TESSERACT_CUBE_COMBINED;
      }
      characterBlacklist = OcrCharacterHelper.getBlacklist(prefs, sourceLanguageCodeOcr);
      characterWhitelist = OcrCharacterHelper.getWhitelist(prefs, sourceLanguageCodeOcr);
      
      prefs.registerOnSharedPreferenceChangeListener(listener);

  }
  
  /*
   Установка дефолтных значений настроек.
   */
  private void setDefaultPreferences()
  {
    prefs = PreferenceManager.getDefaultSharedPreferences(this);

    // Recognition language
    prefs.edit().putString(PreferencesActivity.KEY_SOURCE_LANGUAGE_PREFERENCE, CaptureActivity.DEFAULT_SOURCE_LANGUAGE_CODE).commit();

    // OCR Engine
    prefs.edit().putString(PreferencesActivity.KEY_OCR_ENGINE_MODE, CaptureActivity.DEFAULT_OCR_ENGINE_MODE).commit();

    // Autofocus
    prefs.edit().putBoolean(PreferencesActivity.KEY_AUTO_FOCUS, CaptureActivity.DEFAULT_TOGGLE_AUTO_FOCUS).commit();
    

    // Character blacklist
    prefs.edit().putString(PreferencesActivity.KEY_CHARACTER_BLACKLIST, 
        OcrCharacterHelper.getDefaultBlacklist(CaptureActivity.DEFAULT_SOURCE_LANGUAGE_CODE)).commit();

    // Character whitelist
    prefs.edit().putString(PreferencesActivity.KEY_CHARACTER_WHITELIST,
            OcrCharacterHelper.getDefaultWhitelist(CaptureActivity.DEFAULT_SOURCE_LANGUAGE_CODE)).commit();

    // Segmentation mode
    prefs.edit().putString(PreferencesActivity.KEY_PAGE_SEGMENTATION_MODE, CaptureActivity.DEFAULT_PAGE_SEGMENTATION_MODE).commit();

  }
  
  void displayProgressDialog()
  {
    indeterminateDialog = new ProgressDialog(this);
    indeterminateDialog.setTitle("Please wait");        
    String ocrEngineModeName = getOcrEngineModeName();
    if (ocrEngineModeName.equals("Both")) {
      indeterminateDialog.setMessage("Performing OCR using Cube and Tesseract...");
    } else {
      indeterminateDialog.setMessage("Performing OCR using " + ocrEngineModeName + "...");
    }
    indeterminateDialog.setCancelable(false);
    indeterminateDialog.show();
  }
  
  ProgressDialog getProgressDialog() {
    return indeterminateDialog;
  }
  
  /*
   Сообщение об ошибке в UI потоке
   */
  void showErrorMessage(String title, String message) {
	  new AlertDialog.Builder(this)
	    .setTitle(title)
	    .setMessage(message)
	    .show();
  }
}
