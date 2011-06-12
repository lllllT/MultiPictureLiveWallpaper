package org.tamanegi.wallpaper.multipicture;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.tamanegi.gles.GLCanvas;
import org.tamanegi.gles.GLColor;
import org.tamanegi.gles.GLMatrix;
import org.tamanegi.wallpaper.multipicture.picsource.AlbumPickService;
import org.tamanegi.wallpaper.multipicture.picsource.FolderPickService;
import org.tamanegi.wallpaper.multipicture.picsource.SinglePickService;
import org.tamanegi.wallpaper.multipicture.plugin.LazyPickerClient;
import org.tamanegi.wallpaper.multipicture.plugin.PictureContentInfo;
import org.tamanegi.wallpaper.multipicture.plugin.ScreenInfo;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.FloatMath;
import android.view.SurfaceHolder;

public class MultiPictureRenderer
{
    // message id: for drawer
    private static final int MSG_INIT = 1;
    private static final int MSG_DESTROY = 2;
    private static final int MSG_SHOW = 3;
    private static final int MSG_HIDE = 4;
    private static final int MSG_DRAW = 10;
    private static final int MSG_DRAW_STEP = 11;
    private static final int MSG_PREF_CHANGED = 20;
    private static final int MSG_PREF_CHANGED_NORELOAD = 21;
    private static final int MSG_OFFSET_CHANGED = 22;
    private static final int MSG_SURFACE_CHANGED = 23;
    private static final int MSG_KEYGUARD_CHANGED = 24;
    private static final int MSG_CHANGE_PIC_BY_TAP = 30;
    private static final int MSG_CHANGE_PIC_BY_TIME = 31;
    private static final int MSG_CHANGE_PACKAGE_AVAIL = 40;
    private static final int MSG_DELETE_TEXTURE = 50;

    // message id: for loader
    private static final int MSG_UPDATE_SCREEN = 1001;

    // animation params
    private static final int FADE_FRAME_DURATION = 70;      // msec
    private static final int FADE_TOTAL_DURATION = 500;     // msec
    private static final int BLACKOUT_TOTAL_DURATION = 500; // msec
    private static final int SPINNER_FRAME_DURATION = 120;  // msec
    private static final int SPINNER_TOTAL_FRAMES = 8;      // count
    private static final int BORDER_COLOR = 0x3f3f3f;

    // transition params
    private static final int TRANSITION_RANDOM_TIMEOUT = 100; // msec

    // keyguard params
    private static final int KEYGUARD_FRAME_DURATION = 70;  // msec
    private static final int KEYGUARD_FADE_DURATION = 1000;  // msec

    // maximum size of pictures
    private static final int PIXELS_PER_MB = 1024 * 1024 / 2; // 512kPixels/MB
    private static final int MAX_DETECT_PIXELS = 8 * 1024; // 8kPixels

    private static final int MEMORY_SIZE_OFFSET = 8;

    // for broadcast intent
    private static final String ACTION_CHANGE_PICTURE =
        "org.tamanegi.wallpaper.multipicture.CHANGE_PICTURE";

    private static final String ACTION_EXTERNAL_APPLICATIONS_AVAILABLE =
        "android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE";
    private static final String EXTRA_CHANGED_PACKAGE_LIST =
        "android.intent.extra.changed_package_list";

    // preference keys for no need to reload bitmap data
    private static final String[] UNNECCESARY_RELOAD_KEYS = {
        "draw.transition",
        "draw.reflection.top",
        "draw.reflection",
    };

    // transitions
    private static enum TransitionType
    {
        none, random,
            slide, crossfade, fade_inout,
            zoom_inout, zoom_slide,
            wipe, card,
            slide_3d, rotation_3d, swing, swap,
            cube, cube_inside,
            bookshelf
    }

    private static List<TransitionType> random_transition = Arrays.asList(
        new TransitionType[] {
            TransitionType.slide,
            TransitionType.crossfade,
            TransitionType.fade_inout,
            TransitionType.zoom_inout,
            TransitionType.zoom_slide,
            TransitionType.wipe,
            TransitionType.card,
            TransitionType.slide_3d,
            TransitionType.rotation_3d,
            TransitionType.swing,
            TransitionType.swap,
            TransitionType.cube,
            TransitionType.cube_inside,
            TransitionType.bookshelf,
        });

    private static List<TransitionType> need_sort_transition = Arrays.asList(
        new TransitionType[] {
            TransitionType.zoom_slide,
            TransitionType.swap,
            TransitionType.cube,
            TransitionType.cube_inside,
            TransitionType.bookshelf,
        });

    // screen types: for backward compatible
    private static enum ScreenType
    {
        file, folder, buckets, use_default
    }

    // louncher workaround type
    private static enum LauncherWorkaroundType
    {
        none,
            force_5screen, force_7screen,
            htc_sense, htc_sense_5screen,
            honeycomb_launcher,
            no_vertical
    }

    // picture status
    private static enum PictureStatus
    {
        NOT_AVAILABLE,                          // no pic
            NORMAL,                             // normal picture
            FADEOUT, BLACKOUT, SPINNER, FADEIN, // progress
    }

    // texture id, and aspect ratio
    private static class TextureInfo
    {
        private boolean has_content = false;
        private boolean enable_reflect = true;

        private Bitmap bmp = null;
        private int bwidth;
        private int bheight;

        private int tex_id = -1;
        private float xratio;
        private float yratio;
        private int bgcolor;
    }

    // params for each screen
    private static class PictureInfo
    {
        private PictureStatus status;
        private int progress;
        private int loading_cnt;

        private PictureContentInfo cur_content;
        private ComponentName picsource_service;
        private String picsource_key;
        private boolean picsource_need_restart;
        private PickerClient picker;

        private boolean is_update_pending;

        private TextureInfo tex_info;

        private boolean detect_bgcolor;
        private int bgcolor;

        private float clip_ratio;
        private float saturation;
        private float opacity;

        private void setStatus(PictureStatus status)
        {
            if((status == PictureStatus.FADEOUT &&
                this.status == PictureStatus.FADEIN) ||
               (status == PictureStatus.FADEIN &&
                this.status == PictureStatus.FADEOUT)) {
                progress = Math.max(FADE_TOTAL_DURATION - progress, 1);
            }
            else {
                progress = 1;
            }
            this.status = status;
        }
    }

    // transition effect info
    private static class EffectInfo
    {
        private GLMatrix matrix = new GLMatrix();
        private RectF clip_rect = null;
        private float alpha = 1;
        private float fill_background = 0;
        private boolean need_border = false;
    }

    // offset info
    private static class OffsetInfo
    {
        private float xoffset;
        private float yoffset;
        private float xstep;
        private float ystep;
        @SuppressWarnings("unused") private int xpixel;
        @SuppressWarnings("unused") private int ypixel;

        private OffsetInfo(float xoffset, float yoffset,
                           float xstep, float ystep,
                           int xpixel, int ypixel)
        {
            this.xoffset = xoffset;
            this.yoffset = yoffset;
            this.xstep = xstep;
            this.ystep = ystep;
            this.xpixel = xpixel;
            this.ypixel = ypixel;
        }
    }

    // size info
    private static class SurfaceInfo
    {
        private SurfaceHolder holder;
        @SuppressWarnings("unused") private int format;
        private int width;
        private int height;

        private SurfaceInfo(SurfaceHolder sh, int format,
                            int width, int height)
        {
            this.holder = sh;
            this.format = format;
            this.width = width;
            this.height = height;
        }
    }

    // content update info
    private static class ContentUpdateInfo
    {
        private int idx;
        private PictureInfo pic_info;
        private PictureContentInfo content;
        private boolean force_reload;

        private ContentUpdateInfo(
            int idx, PictureInfo pic_info,
            PictureContentInfo content, boolean force_reload)
        {
            this.idx = idx;
            this.pic_info = pic_info;
            this.content = content;
            this.force_reload = force_reload;
        }
    }

    // renderer local values
    private Context context;
    private HandlerThread drawer_thread;
    private Handler drawer_handler;
    private HandlerThread loader_thread;
    private Handler loader_handler;
    private HandlerThread picsource_thread;

    private int width = 1;
    private int height = 1;
    private float wratio = 1;
    private boolean visible = false;
    private GLCanvas glcanvas;

    private int xcnt = 1;
    private int ycnt = 1;
    private float xcur = 0f;
    private float ycur = 0f;
    private float ycur_honeycomb = 1f;

    private int max_screen_pixels;
    private int max_work_pixels;

    private PictureInfo pic[];
    private Object pic_whole_lock;
    private ComponentName default_picsource_service;
    private String default_picsource_key;
    private TransitionType screen_transition;
    private float default_clip_ratio;
    private float default_saturation;
    private float default_opacity;
    private boolean default_detect_bgcolor;
    private int default_bgcolor;
    private boolean show_reflection_top;
    private boolean show_reflection_bottom;
    private boolean change_tap;
    private int change_duration;
    private LauncherWorkaroundType workaround_launcher;
    private int max_memory_size;

    private int last_duration = 0;
    private boolean is_in_transition = false;
    private long transition_prev_time = 0;
    private TransitionType cur_transition;
    private int cur_transition_idx = -1;

    private boolean use_keyguard_pic;
    private boolean is_in_keyguard;
    private boolean is_keyguard_visible;
    private float keyguard_dx;
    private PictureInfo keyguard_pic;
    private long keyguard_prev_time = 0;

    private boolean is_duration_pending = false;

    private TextureInfo spinner = null;

    private Paint paint;
    private Paint text_paint;

    private SharedPreferences pref;
    private PreferenceChangedListener pref_listener;

    private ContentResolver resolver;
    private Random random;

    private BroadcastReceiver receiver;

    public MultiPictureRenderer(Context context)
    {
        this.context = context;

        // draw thread and handler
        drawer_thread = new HandlerThread("MultiPicture.drawer");
        drawer_thread.start();
        drawer_handler = new Handler(
            drawer_thread.getLooper(), new Handler.Callback() {
                    public boolean handleMessage(Message msg) {
                        return onHandleDrawMessage(msg);
                    }
                });

        // load thread and handler
        loader_thread = new HandlerThread(
            "MultiPicture.loader", Process.THREAD_PRIORITY_BACKGROUND);
        loader_thread.start();
        loader_handler = new Handler(
            loader_thread.getLooper(), new Handler.Callback() {
                    public boolean handleMessage(Message msg) {
                        return onHandleLoadMessage(msg);
                    }
                });

        // picture source thread and handler
        picsource_thread = new HandlerThread(
            "MultiPicture.picsource", Process.THREAD_PRIORITY_BACKGROUND);
        picsource_thread.start();
    }

    public void onCreate(SurfaceHolder holder, boolean is_preview)
    {
        int priority = (is_preview ?
                        Process.THREAD_PRIORITY_DEFAULT :
                        Process.THREAD_PRIORITY_DISPLAY);
        drawer_handler.obtainMessage(MSG_INIT, Integer.valueOf(priority))
            .sendToTarget();
    }

    public void onDestroy()
    {
        drawer_handler.sendEmptyMessage(MSG_DESTROY);
    }

    public void onLowMemory()
    {
        synchronized(pic_whole_lock) {
            clearPictureBitmap();
        }
    }

    public void onOffsetsChanged(float xoffset, float yoffset,
                                 float xstep, float ystep,
                                 int xpixel, int ypixel)
    {
        OffsetInfo info = new OffsetInfo(
            xoffset, yoffset, xstep, ystep, xpixel, ypixel);
        drawer_handler.obtainMessage(MSG_OFFSET_CHANGED, info).sendToTarget();
    }

    public void onSurfaceChanged(SurfaceHolder sh, int format,
                                 int width, int height)
    {
        if(this.width == width && this.height == height) {
            return;
        }

        SurfaceInfo info = new SurfaceInfo(sh, format, width, height);
        drawer_handler.obtainMessage(MSG_SURFACE_CHANGED, info).sendToTarget();
    }

    public void onVisibilityChanged(boolean visible)
    {
        drawer_handler.sendEmptyMessage(visible ? MSG_SHOW : MSG_HIDE);
    }

    public void onDoubleTap()
    {
        drawer_handler.sendEmptyMessage(MSG_CHANGE_PIC_BY_TAP);
    }

    private boolean onHandleDrawMessage(Message msg)
    {
        switch(msg.what) {
          case MSG_INIT:
              init(((Integer)msg.obj).intValue());
              break;

          case MSG_DESTROY:
              destroy();
              break;

          case MSG_DRAW:
          case MSG_DRAW_STEP:
              synchronized(pic_whole_lock) {
                  draw(msg.what == MSG_DRAW_STEP);
                  pic_whole_lock.notifyAll();
              }
              break;

          case MSG_SHOW:
              synchronized(pic_whole_lock) {
                  visible = true;

                  if(is_duration_pending) {
                      postDurationCallback();
                  }

                  if(pic != null) {
                      for(PictureInfo info : pic) {
                          if(info.is_update_pending) {
                              info.picker.sendGetNext();
                          }
                          info.is_update_pending = false;
                      }
                  }

                  if(use_keyguard_pic && keyguard_pic != null) {
                      if(keyguard_pic.is_update_pending) {
                          keyguard_pic.picker.sendGetNext();
                      }
                      keyguard_pic.is_update_pending = false;
                  }

                  drawer_handler.sendEmptyMessage(MSG_DRAW);
              }
              break;

          case MSG_HIDE:
              synchronized(pic_whole_lock) {
                  visible = false;
              }
              break;

          case MSG_PREF_CHANGED:
          case MSG_PREF_CHANGED_NORELOAD:
              synchronized(pic_whole_lock) {
                  if(msg.what != MSG_PREF_CHANGED_NORELOAD) {
                      clearPictureSetting();
                  }

                  loadGlobalSetting();
                  drawer_handler.sendEmptyMessage(MSG_DRAW);
              }
              break;

          case MSG_OFFSET_CHANGED:
              changeOffsets((OffsetInfo)msg.obj);
              drawer_handler.sendEmptyMessage(MSG_DRAW);
              break;

          case MSG_SURFACE_CHANGED:
              SurfaceInfo info = (SurfaceInfo)msg.obj;
              synchronized(pic_whole_lock) {
                  glcanvas.setSurface(info.holder, info.width, info.height);
                  updateScreenSize(info);
                  clearPictureBitmap();
                  drawer_handler.sendEmptyMessage(MSG_DRAW);
              }
              break;

          case MSG_KEYGUARD_CHANGED:
              keyguard_prev_time = msg.getWhen();
              drawer_handler.sendEmptyMessage(MSG_DRAW);
              break;

          case MSG_CHANGE_PIC_BY_TAP:
              if(change_tap) {
                  synchronized(pic_whole_lock) {
                      updateAllScreen(true);
                      drawer_handler.sendEmptyMessage(MSG_DRAW);
                  }
                  postDurationCallback();
              }
              break;

          case MSG_CHANGE_PIC_BY_TIME:
              synchronized(pic_whole_lock) {
                  updateAllScreen(false);
                  drawer_handler.sendEmptyMessage(MSG_DRAW);
              }
              postDurationCallback();
              break;

          case MSG_CHANGE_PACKAGE_AVAIL:
              synchronized(pic_whole_lock) {
                  changePackageAvailable((String[])msg.obj);
                  drawer_handler.sendEmptyMessage(MSG_DRAW);
              }
              break;

          case MSG_DELETE_TEXTURE:
              glcanvas.deleteTexture(msg.arg1);
              break;

          default:
              return false;
        }

        return true;
    }

    private boolean onHandleLoadMessage(Message msg)
    {
        switch(msg.what) {
          case MSG_UPDATE_SCREEN:
              loadScreenContent((ContentUpdateInfo)msg.obj);
              break;

          default:
              return false;
        }

        return true;
    }

    private void init(int priority)
    {
        // thread priority
        Process.setThreadPriority(priority);

        // paint
        paint = new Paint();
        paint.setFilterBitmap(true);
        paint.setAntiAlias(true);
        paint.setDither(true);
        paint.setColor(0xff000000);

        text_paint = new Paint();
        text_paint.setAntiAlias(true);
        text_paint.setColor(0xffffffff);
        text_paint.setTextAlign(Paint.Align.CENTER);
        text_paint.setTextSize(text_paint.getTextSize() * 1.5f);

        // resolver
        resolver = context.getContentResolver();

        // random
        random = new Random();

        // prefs
        pref = PreferenceManager.getDefaultSharedPreferences(context);
        pref_listener = new PreferenceChangedListener();
        pref.registerOnSharedPreferenceChangeListener(pref_listener);

        // prepare to receive broadcast
        IntentFilter filter;
        receiver = new Receiver();

        filter = new IntentFilter();
        filter.addAction(ACTION_CHANGE_PICTURE);
        context.registerReceiver(receiver, filter);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        context.registerReceiver(receiver, filter);

        filter = new IntentFilter();
        filter.addAction(ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        context.registerReceiver(receiver, filter);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(receiver, filter);

        // init conf
        pic_whole_lock = new Object();
        synchronized(pic_whole_lock) {
            glcanvas = new GLCanvas();
            clearPictureSetting();
            loadGlobalSetting();
        }

        // spinner texture
        {
            spinner = new TextureInfo();
            spinner.has_content = true;
            spinner.enable_reflect = false;

            Bitmap spinner_bmp = BitmapFactory.decodeResource(
                context.getResources(), R.drawable.spinner);
            int bw = spinner_bmp.getWidth();
            int bh = spinner_bmp.getHeight();
            int tw = getLeastPowerOf2GE(bw);
            int th = getLeastPowerOf2GE(bh);
            spinner.bmp = createBitmap(spinner_bmp,
                                       (bw - tw) / 2, (bh - th) / 2, tw, th,
                                       null, tw, th, 1);
            spinner_bmp.recycle();

            spinner.bwidth = spinner.bmp.getWidth();
            spinner.bheight = spinner.bmp.getHeight();
            spinner.xratio = (float)spinner.bwidth / width;
            spinner.yratio = (float)spinner.bheight / height;
            spinner.bgcolor = 0xff000000;
        }
    }

    private void destroy()
    {
        synchronized(pic_whole_lock) {
            // conf
            clearPictureSetting();
            glcanvas.terminateGL();
        }

        // broadcast
        context.unregisterReceiver(receiver);

        // prefs
        pref.unregisterOnSharedPreferenceChangeListener(pref_listener);

        // stop threads
        drawer_thread.quit();
        loader_thread.quit();
        picsource_thread.quit();
    }

    private void clearPictureSetting()
    {
        if(pic != null) {
            // clear for each picture info
            for(PictureInfo info : pic) {
                clearPictureSetting(info);
            }

            pic = null;
        }

        if(keyguard_pic != null) {
            // clear keyguard picture info
            clearPictureSetting(keyguard_pic);
            keyguard_pic = null;
        }
    }

    private void clearPictureSetting(PictureInfo info)
    {
        if(info == null) {
            return;
        }

        info.picker.sendStop();

        if(info.tex_info.bmp != null) {
            info.tex_info.bmp.recycle();
        }
        else if(info.tex_info.tex_id >= 0) {
            glcanvas.deleteTexture(info.tex_info.tex_id);
        }
    }

    private void clearPictureBitmap()
    {
        if(pic != null) {
            for(PictureInfo info : pic) {
                clearPictureBitmap(info);
            }
        }

        if(keyguard_pic != null) {
            clearPictureBitmap(keyguard_pic);
        }
    }

    private void clearPictureBitmap(PictureInfo info)
    {
        if(info.tex_info.bmp != null) {
            info.tex_info.bmp.recycle();
            info.tex_info.bmp = null;
        }
        else if(info.tex_info.tex_id >= 0) {
            glcanvas.deleteTexture(info.tex_info.tex_id);
            info.tex_info.tex_id = -1;
        }

        info.tex_info.has_content = false;
        info.setStatus(PictureStatus.BLACKOUT);
    }

    private void loadGlobalSetting()
    {
        // default picture source
        default_picsource_key = MultiPictureSetting.SCREEN_DEFAULT;

        String service_str = pref.getString(
            MultiPictureSetting.getKey(
                MultiPictureSetting.SCREEN_PICSOURCE_SERVICE_KEY, -1), null);
        if(service_str != null) {
            // lazy picker
            default_picsource_service =
                ComponentName.unflattenFromString(service_str);
        }
        else {
            // backward compatible
            ScreenType default_type = ScreenType.valueOf(
                pref.getString(
                    MultiPictureSetting.getKey(
                        MultiPictureSetting.SCREEN_TYPE_KEY, -1), "file"));

            if(default_type == ScreenType.file) {
                // single file
                default_picsource_service =
                    new ComponentName(context, SinglePickService.class);
            }
            else if(default_type == ScreenType.folder) {
                // from folder
                default_picsource_service =
                    new ComponentName(context, FolderPickService.class);
            }
            else if(default_type == ScreenType.buckets) {
                // from album
                default_picsource_service =
                    new ComponentName(context, AlbumPickService.class);
            }
        }

        // background color
        {
            String bgcolor = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_BGCOLOR_KEY, -1), "black");
            if("auto_detect".equals(bgcolor)) {
                default_detect_bgcolor = true;
            }
            else if("custom".equals(bgcolor)) {
                default_bgcolor = pref.getInt(
                    MultiPictureSetting.getKey(
                        MultiPictureSetting.SCREEN_BGCOLOR_CUSTOM_KEY, -1),
                    0xff000000);
            }
            else {
                default_detect_bgcolor = false;
                default_bgcolor = Color.parseColor(bgcolor);
            }
        }

        // draw setting
        screen_transition = TransitionType.valueOf(
            pref.getString("draw.transition", "slide"));
        cur_transition = screen_transition;
        show_reflection_top = pref.getBoolean("draw.reflection.top", false);
        show_reflection_bottom = pref.getBoolean("draw.reflection", true);

        default_clip_ratio = Float.valueOf(
            pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_CLIP_KEY, -1), "1.0"));
        default_saturation = Float.valueOf(
            pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_SATURATION_KEY, -1), "1.0"));
        default_opacity = Float.valueOf(
            pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_OPACITY_KEY, -1), "1.0"));

        // folder setting
        change_tap = pref.getBoolean("folder.changetap", true);
        {
            String min_str = pref.getString("folder.duration", null);
            String sec_str = pref.getString("folder.duration_sec", null);
            change_duration =
                (sec_str != null ? Integer.parseInt(sec_str) :
                 min_str != null ? Integer.parseInt(min_str) * 60 :
                 60 * 60);
        }

        // keyguard screen
        use_keyguard_pic = pref.getBoolean(
            MultiPictureSetting.getKey(MultiPictureSetting.SCREEN_ENABLE_KEY,
                                       MultiPictureSetting.SCREEN_KEYGUARD),
            false);
        is_in_keyguard = false;

        // workaround
        boolean workaround_sense_val =
            pref.getBoolean("workaround.htcsense", true);
        String workaround_launcher_str =
            pref.getString("workaround.launcher", null);
        if(workaround_launcher_str == null) {
            workaround_launcher_str =
                (pref.contains("workaround.htcsense") ?
                 (workaround_sense_val ? "htc_sense" : "none") :
                 context.getString(R.string.workaround_default));
        }
        workaround_launcher =
            LauncherWorkaroundType.valueOf(workaround_launcher_str);

        // maximum memory usage
        String max_memory_str = pref.getString("memory.max", "auto");
        if("auto".equals(max_memory_str)) {
            max_memory_size = MultiPictureSetting.getAutoMemoryClass(context);
        }
        else {
            max_memory_size = Integer.valueOf(max_memory_str);
        }
        max_memory_size -= (max_memory_size > 0 ? MEMORY_SIZE_OFFSET : 0);

        updateScreenSize(null);
    }

    private void updateScreenSize(SurfaceInfo info)
    {
        int cnt = xcnt * ycnt + (use_keyguard_pic ? 1 : 0);

        if(info != null) {
            // screen size
            width = info.width;
            height = info.height;
            wratio = (float)width / height;
        }

        // spinner texture
        if(spinner != null) {
            if(spinner.tex_id >= 0) {
                glcanvas.deleteTexture(spinner.tex_id);
            }
            spinner.tex_id = glcanvas.genTexture(spinner.bmp);
            spinner.xratio = (float)spinner.bmp.getWidth() / width;
            spinner.yratio = (float)spinner.bmp.getHeight() / height;
        }

        // restrict size
        if(max_memory_size > 0) {
            // restrict by memory class
            int max_total_pixels = max_memory_size * PIXELS_PER_MB;
            max_screen_pixels = max_total_pixels / (cnt + 3);
            max_work_pixels = max_screen_pixels * 2;
        }
        else {
            // unlimited size
            max_screen_pixels = -1;
            max_work_pixels = -1;
        }
    }

    private void changePackageAvailable(String[] pkgnames)
    {
        if(pic != null) {
            for(PictureInfo info : pic) {
                changePackageAvailable(info, pkgnames);
            }
        }

        if(use_keyguard_pic && keyguard_pic != null) {
            changePackageAvailable(keyguard_pic, pkgnames);
        }
    }

    private void changePackageAvailable(PictureInfo info, String[] pkgnames)
    {
        String cur_pkgname = info.picsource_service.getPackageName();
        for(String pkgname : pkgnames) {
            if(cur_pkgname.equals(pkgname)) {
                info.picsource_need_restart = true;
                break;
            }
        }
    }

    private int updatePictureStatus(int add_step)
    {
        int next_duration = 0;

        for(int i = -1; i < pic.length; i++) {
            if(i < 0 && ! use_keyguard_pic) {
                continue;
            }

            boolean is_visible;
            PictureInfo info;
            if(i >= 0) {
                int xn = i % xcnt;
                int yn = i / xcnt;
                is_visible = (visible &&
                              (is_in_transition ||
                               (Math.abs(xn - xcur) < 1 &&
                                Math.abs(yn - ycur) < 1)));
                info = pic[i];
            }
            else {
                is_visible = is_keyguard_visible;
                info = keyguard_pic;

                if(! is_visible) {
                    keyguard_prev_time = 0;
                    keyguard_dx = 1;
                }
            }

            if(info.status == PictureStatus.FADEIN) {
                if((is_visible && info.progress >= FADE_TOTAL_DURATION) ||
                   (! is_visible)) {
                    info.setStatus(PictureStatus.NORMAL);
                }
            }
            else if(info.status == PictureStatus.FADEOUT) {
                if((is_visible && info.progress >= FADE_TOTAL_DURATION) ||
                   (! is_visible)) {
                    info.setStatus(PictureStatus.BLACKOUT);
                }
            }
            else if(info.status == PictureStatus.BLACKOUT) {
                if((is_visible && info.progress >= BLACKOUT_TOTAL_DURATION) ||
                   (! is_visible)) {
                    info.setStatus(PictureStatus.SPINNER);
                }
            }

            if(is_visible) {
                int duration = next_duration;

                if(info.status == PictureStatus.BLACKOUT ||
                   info.status == PictureStatus.FADEIN ||
                   info.status == PictureStatus.FADEOUT) {
                    duration = FADE_FRAME_DURATION;
                }
                else if(info.status == PictureStatus.SPINNER) {
                    duration = SPINNER_FRAME_DURATION;
                }
                next_duration = (next_duration == 0 ? duration :
                                 Math.min(next_duration, duration));

                if(i < 0 && ! is_in_keyguard) {
                    duration = KEYGUARD_FRAME_DURATION;
                }
                next_duration = (next_duration == 0 ? duration :
                                 Math.min(next_duration, duration));
            }

            info.progress += add_step;
        }

        return next_duration;
    }

    private void draw(boolean is_step)
    {
        drawer_handler.removeMessages(MSG_DRAW);

        // check draw interval
        long cur_time = SystemClock.uptimeMillis();
        int cur_duration = 0;

        // update keyguard state
        if(use_keyguard_pic) {
            is_in_keyguard =
                ((KeyguardManager)context.getSystemService(
                    Context.KEYGUARD_SERVICE)).inKeyguardRestrictedInputMode();
            if(is_in_keyguard) {
                keyguard_prev_time = cur_time;
            }

            keyguard_dx =
                (float)(cur_time - keyguard_prev_time) / KEYGUARD_FADE_DURATION;
            keyguard_dx = Math.min(keyguard_dx, 1);
            is_keyguard_visible =
                (visible && (is_in_keyguard || keyguard_dx < 1));
        }
        else {
            keyguard_dx = 1;
            is_keyguard_visible = false;
        }

        // check picture data: pic != null
        if(pic != null) {
            for(int i = 0; i < pic.length; i++) {
                if(pic[i].picsource_need_restart) {
                    clearPictureSetting(pic[i]);
                    pic[i] = loadPictureInfo(i);
                }
            }
        }
        if(use_keyguard_pic && keyguard_pic != null) {
            if(keyguard_pic.picsource_need_restart) {
                clearPictureSetting(keyguard_pic);
                keyguard_pic = loadPictureInfo(-1);
            }
        }

        // prepare next draw
        if(pic != null && (is_step || last_duration == 0)) {
            cur_duration = updatePictureStatus(last_duration);
        }

        // check bitmap to texture
        if(pic != null) {
            for(int i = 0; i < pic.length; i++) {
                PictureInfo info = pic[i];
                if(info.tex_info.has_content &&
                   info.tex_info.bmp != null) {
                    info.tex_info.tex_id =
                        glcanvas.genTexture(info.tex_info.bmp);
                    info.tex_info.bmp.recycle();
                    info.tex_info.bmp = null;
                }
            }
        }

        if(use_keyguard_pic && keyguard_pic != null) {
            if(keyguard_pic.tex_info.has_content &&
               keyguard_pic.tex_info.bmp != null) {
                keyguard_pic.tex_info.tex_id =
                    glcanvas.genTexture(keyguard_pic.tex_info.bmp);
                keyguard_pic.tex_info.bmp.recycle();
                keyguard_pic.tex_info.bmp = null;
            }
        }

        // check visible
        if(! visible) {
            if(is_step || cur_duration > 0) {
                last_duration = cur_duration;
            }
            return;
        }

        // check picture data: pic == null
        if(pic == null) {
            loadPictureSetting();
            postDurationCallback();
            cur_duration = updatePictureStatus(0);
        }

        for(int i = 0; i < pic.length; i++) {
            PictureInfo info = pic[i];
            if(info.loading_cnt == 0 &&
               info.cur_content != null &&
               ! info.tex_info.has_content) {
                info.loading_cnt += 1;
                sendUpdateScreen(i, info, null, true);
            }
        }

        if(use_keyguard_pic) {
            if(keyguard_pic.loading_cnt == 0 &&
               keyguard_pic.cur_content != null &&
               ! keyguard_pic.tex_info.has_content) {
                keyguard_pic.loading_cnt += 1;
                sendUpdateScreen(-1, keyguard_pic, null, true);
            }
        }

        // draw
        try {
            drawPicture();
        }
        finally {
            if(! glcanvas.swap()) {
                // reload and retry
                clearPictureBitmap();
                drawer_handler.sendEmptyMessage(MSG_DRAW);
            }
        }

        // prepare next draw step
        if(cur_duration > 0) {
            long next_time = cur_time + cur_duration;
            last_duration = cur_duration;

            drawer_handler.removeMessages(MSG_DRAW_STEP);
            drawer_handler.sendEmptyMessageAtTime(MSG_DRAW_STEP, next_time);
        }
        else if(is_step) {
            last_duration = 0;
        }
    }

    private void drawPicture()
    {
        // delta for each screen
        class ScreenDelta implements Comparable<ScreenDelta>
        {
            PictureInfo pic_info;
            float dx;
            float dy;
            float dz;
            float fade;
            float xpos;
            boolean visible;
            boolean for_lock;

            ScreenDelta(PictureInfo pic_info, float dx, float dy, float xpos,
                        boolean for_lock)
            {
                this.pic_info = pic_info;
                this.dx = dx;
                this.dy = dy;
                this.xpos = xpos;
                this.for_lock = for_lock;

                float fade_r =
                    ((pic_info.status == PictureStatus.NOT_AVAILABLE ||
                      pic_info.status == PictureStatus.SPINNER ||
                      pic_info.status == PictureStatus.NORMAL) ?
                     FADE_TOTAL_DURATION :
                     pic_info.status == PictureStatus.FADEIN ?
                     pic_info.progress :
                     pic_info.status == PictureStatus.FADEOUT ?
                     FADE_TOTAL_DURATION - pic_info.progress :
                     0) / (float)FADE_TOTAL_DURATION;
                float fade_k =
                    ratioRange(for_lock ? 1 - keyguard_dx * 2 :
                               keyguard_dx * 2 - 1);

                dz = fade_k;
                fade = ratioRange(fade_r) * fade_k;
                visible = (for_lock ? is_keyguard_visible : true);
            }

            @Override
            public int compareTo(ScreenDelta o2)
            {
                if(for_lock != o2.for_lock) {
                    return (for_lock ? +1 : -1);
                }

                // reverse order
                float d1 = Math.abs(this.dx) + Math.abs(this.dy);
                float d2 = Math.abs(o2.dx) + Math.abs(o2.dy);
                return (d1 < d2 ? +1 :
                        d1 > d2 ? -1 :
                        0);
            }
        }

        ScreenDelta[] ds = new ScreenDelta[
            pic.length + (use_keyguard_pic ? 1 : 0)];

        for(int i = 0; i < pic.length; i++) {
            int xx = i % xcnt;
            int yy = i / xcnt;
            float xpos = (xcnt > 0 ? (float)xx / (xcnt - 1) : 0);
            ds[i] = new ScreenDelta(pic[i], xx - xcur, yy - ycur, xpos, false);
        }

        if(use_keyguard_pic) {
            ds[pic.length] = new ScreenDelta(keyguard_pic, 0, 0, 0, true);
        }

        // delta
        int xn = (int)Math.floor(xcur);
        int yn = (int)Math.floor(ycur);
        float dxpx = Math.abs(xn - xcur) * width;
        float dypx = Math.abs(yn - ycur) * height;

        // for random transition
        if(((! is_in_transition) &&
            (dxpx >= 1 || dypx >= 1) &&
            (screen_transition == TransitionType.random) &&
            (transition_prev_time + TRANSITION_RANDOM_TIMEOUT <
             SystemClock.elapsedRealtime())) ||
           (cur_transition == TransitionType.random)) {
            cur_transition_idx =
                (cur_transition_idx + 1) % random_transition.size();
            if(cur_transition_idx == 0) {
                Collections.shuffle(random_transition, random);
            }
            cur_transition = random_transition.get(cur_transition_idx);
        }

        if(dxpx >= 1 || dypx >= 1) {
            is_in_transition = true;
        }
        else if(is_in_transition) {
            is_in_transition = false;
            transition_prev_time = SystemClock.elapsedRealtime();
        }

        // background color
        GLColor color = new GLColor(0);
        for(ScreenDelta s : ds) {
            if(s.visible) {
                GLColor cc =
                    getBackgroundColor(s.pic_info, s.dx, s.dy, s.dz, s.fade);
                color.red   += cc.red * cc.alpha;
                color.green += cc.green * cc.alpha;
                color.blue  += cc.blue * cc.alpha;
                color.alpha += cc.alpha;
            }
        }

        color.red   /= color.alpha;
        color.green /= color.alpha;
        color.blue  /= color.alpha;
        color.alpha = 1;

        glcanvas.drawColor(color);

        // draw each screen
        float hc_ratio = 1 - Math.min(1, Math.abs(ycur_honeycomb - 1));

        if(need_sort_transition.contains(cur_transition)) {
            Arrays.sort(ds);
        }

        for(ScreenDelta s : ds) {
            float dx = s.dx * hc_ratio;
            float dy = s.dy * hc_ratio;

            EffectInfo effect =
                (s.visible ? getTransitionEffect(cur_transition, dx, dy) :
                 null);
            if(effect != null && hc_ratio < 1 && ! s.for_lock) {
                EffectInfo hc_effect = getHoneycombEffect(
                    s.xpos, ycur_honeycomb);
                effect = mergeEffect(effect, hc_effect, hc_ratio);
            }

            if(effect != null && effect.alpha > 0) {
                drawPicture(s.pic_info, effect, s.dz, s.fade);
            }
        }
    }

    private void drawPicture(PictureInfo pic_info,
                             EffectInfo effect, float dz, float fade)
    {
        PictureStatus status = pic_info.status;

        // clip region
        if(effect.clip_rect != null) {
            glcanvas.setClipRect(effect.matrix, effect.clip_rect);
        }

        // draw params
        TextureInfo tex_info =
            ((status == PictureStatus.NORMAL ||
              status == PictureStatus.FADEIN ||
              status == PictureStatus.FADEOUT ||
              status == PictureStatus.NOT_AVAILABLE) ? pic_info.tex_info :
             status == PictureStatus.SPINNER ? spinner :
             null);
        GLColor bgcolor =
            (effect.fill_background > 0 ?
             getBackgroundColorNoAlpha(pic_info, fade).setAlpha(
                 effect.fill_background * dz) : null);
        float border_ratio =
            (status == PictureStatus.SPINNER ||
             status == PictureStatus.NOT_AVAILABLE ? 1 :
             1 - fade) * dz;
        float opacity =
            (status == PictureStatus.SPINNER ||
             status == PictureStatus.NOT_AVAILABLE ? 1 :
             pic_info.opacity);

        if(effect.need_border && border_ratio > 0) {
            // border and/or background
            glcanvas.drawRect(
                effect.matrix, bgcolor,
                new GLColor(BORDER_COLOR).setAlpha(
                    effect.alpha * border_ratio));
        }
        else if(bgcolor != null) {
            // background
            glcanvas.drawRect(effect.matrix, bgcolor, null);
        }

        if(status == PictureStatus.SPINNER) {
            // spinner
            long uptime = SystemClock.uptimeMillis();
            effect.matrix
                .rotateZ(-360f *
                         (int)((uptime / SPINNER_FRAME_DURATION) %
                               SPINNER_TOTAL_FRAMES) / SPINNER_TOTAL_FRAMES);
        }

        if(tex_info != null) {
            // alpha with fade
            effect.alpha *= fade;

            // matrix for main texture
            GLMatrix mcenter = new GLMatrix(effect.matrix)
                .scale(tex_info.xratio, tex_info.yratio, 1);

            // draw content picture
            glcanvas.drawTexture(
                mcenter, tex_info.tex_id,
                effect.alpha * opacity, fade);

            // mirrored picture: top
            if(tex_info.enable_reflect && show_reflection_top) {
                GLMatrix mtop = new GLMatrix(mcenter)
                    .translate(0, 2, 0)
                    .scale(1, -1, 1);

                if(bgcolor != null) {
                    glcanvas.drawRect(mtop, bgcolor, null);
                }

                glcanvas.drawTexture(
                    mtop, tex_info.tex_id,
                    effect.alpha * opacity / 4, fade);
            }

            // mirrored picture: bottom
            if(tex_info.enable_reflect && show_reflection_bottom) {
                GLMatrix mbtm = new GLMatrix(mcenter)
                    .translate(0, -2, 0)
                    .scale(1, -1, 1);

                if(bgcolor != null) {
                    glcanvas.drawRect(mbtm, bgcolor, null);
                }

                glcanvas.drawTexture(
                    mbtm, tex_info.tex_id,
                    effect.alpha * opacity / 4, fade);
            }
        }

        // restore clip region
        if(effect.clip_rect != null) {
            glcanvas.clearClipRect();
        }
    }

    private EffectInfo getTransitionEffect(TransitionType transition,
                                           float dx, float dy)
    {
        EffectInfo effect = new EffectInfo();

        if(transition == TransitionType.none) {
            if(dx <= -0.5 || dx > 0.5 ||
               dy <= -0.5 || dy > 0.5) {
                return null;
            }

            effect.fill_background = 1;
        }
        else if(transition == TransitionType.crossfade) {
            if(dx <= -1 || dx >= 1 ||
               dy <= -1 || dy >= 1) {
                return null;
            }

            effect.alpha *= ((1 - Math.abs(dx)) * (1 - Math.abs(dy)));
        }
        else if(transition == TransitionType.fade_inout) {
            if(dx <= -0.5 || dx >= 0.5 ||
               dy <= -0.5 || dy >= 0.5) {
                return null;
            }

            effect.alpha *= (1 - Math.max(Math.abs(dx), Math.abs(dy)) * 2);
        }
        else if(transition == TransitionType.slide) {
            effect.matrix.translate(dx * 2 * wratio, -dy * 2, 0);
            effect.fill_background = 1;
        }
        else if(transition == TransitionType.zoom_inout) {
            float fact = 1 - Math.max(Math.abs(dx), Math.abs(dy));
            if(fact <= 0) {
                return null;
            }

            effect.matrix
                .translate(dx * wratio, -dy, 0)
                .scale(fact, fact, 1);
        }
        else if(transition == TransitionType.zoom_slide) {
            float dxy = Math.abs(dx) + Math.abs(dy);
            float dr =
                Math.abs(dx - Math.round(dx)) + Math.abs(dy - Math.round(dy));
            float fact =
                1 - Math.min(1, Math.max(Math.abs(dx), Math.abs(dy)) * 1.25f);
            fact = 1 - fact * fact;

            effect.matrix.translate(dx * 2 * wratio, -dy * 2, fact * -8);
            effect.alpha *= (dxy < 0.5f ? 1 : Math.min(1, dr * 8));
        }
        else if(transition == TransitionType.wipe) {
            if(dx <= -1 || dx >= 1 ||
               dy <= -1 || dy >= 1) {
                return null;
            }

            effect.clip_rect =
                new RectF(((dx <= 0 ? 0 : +dx) * 2f - 1f) * wratio,
                          ((dy <= 0 ? 0 : -dy) * 2f + 1f),
                          ((dx <= 0 ? +dx : 0) * 2f + 1f) * wratio,
                          ((dy <= 0 ? -dy : 0) * 2f - 1f));
            effect.fill_background = 1;
        }
        else if(transition == TransitionType.card) {
            if(dx <= -1 || dy <= -1) {
                return null;
            }

            float tx = Math.max(dx, 0) * wratio;
            float ty = Math.max(dy, 0);
            effect.matrix.translate(tx * 2, -ty * 2, 0);
            effect.fill_background = 1;
        }
        else if(transition == TransitionType.slide_3d) {
            if(dx >= 1 || dy >= 1) {
                return null;
            }

            float dxy = Math.abs(dx) + Math.abs(dy);
            float dr =
                Math.abs(dx - Math.round(dx)) + Math.abs(dy - Math.round(dy));
            float tx = (float)Math.log(1 - dx);
            float ty = (float)Math.log(1 - dy);
            effect.matrix.translate((tx * 3f + dy * dy * 0.75f) * -wratio,
                                    (dx * dx * 0.75f + ty * 3f),
                                    (dx + dy) * 8f);
            effect.alpha *= (dxy < 0.5f ? 1 : Math.min(1, dr * 8 / dxy));
        }
        else if(transition == TransitionType.rotation_3d) {
            if(dx <= -0.5 || dx > 0.5 ||
               dy <= -0.5 || dy > 0.5) {
                return null;
            }

            float fact = 1 - (1 - Math.abs(dx)) * (1 - Math.abs(dy));
            effect.matrix
                .translate(0, 0, fact * -(1 + wratio))
                .rotateY(dx * 180)
                .rotateX(dy * 180);

            effect.need_border = true;
        }
        else if(transition == TransitionType.swing) {
            if(dx <= -1 || dx >= 1 ||
               dy <= -1 || dy >= 1) {
                return null;
            }

            float tx = (dx <= 0 ? +1 : -1) * wratio;
            float ty = (dy <= 0 ? -1 : +1);
            effect.matrix
                .translate(-tx, -ty, 0)
                .rotateY(dx * -120)
                .rotateX(dy * -120)
                .translate(tx, ty, 0);

            effect.need_border = true;
        }
        else if(transition == TransitionType.swap) {
            float fact = Math.max(Math.abs(dx), Math.abs(dy));
            if(fact >= 1) {
                return null;
            }

            float ang1 = (float)(fact * Math.PI);
            float ang2 = (float)Math.atan2(-dy, dx);

            effect.matrix.translate(
                FloatMath.cos(ang2) * FloatMath.sin(ang1) * 1.01f * wratio,
                FloatMath.sin(ang2) * FloatMath.sin(ang1) * 1.01f,
                (FloatMath.cos(ang1) - 1) * 2);

            effect.alpha *= Math.min((FloatMath.cos(ang1) + 1) * 2, 1);
        }
        else if(transition == TransitionType.cube) {
            float fact = Math.max(Math.abs(dx), Math.abs(dy));
            if(fact >= 1) {
                return null;
            }

            float ang1 = (float)(fact * Math.PI / 2);
            float ang2 = (float)Math.atan2(-dy, dx);

            effect.matrix
                .translate(
                    FloatMath.cos(ang2) * FloatMath.sin(ang1) * wratio,
                    FloatMath.sin(ang2) * FloatMath.sin(ang1),
                    (FloatMath.cos(ang1) - 1) * (wratio + 1) * 0.5f)
                .rotateY(dx * 90)
                .rotateX(dy * 90);

            effect.alpha *= Math.min(FloatMath.cos(ang1), 1);
            effect.need_border = true;
        }
        else if(transition == TransitionType.cube_inside) {
            float fact = Math.max(Math.abs(dx), Math.abs(dy));
            if(fact >= 1) {
                return null;
            }

            float ang1 = (float)(fact * Math.PI / 2);
            float ang2 = (float)Math.atan2(-dy, dx);

            effect.matrix
                .translate(
                    FloatMath.cos(ang2) * FloatMath.sin(ang1) * wratio,
                    FloatMath.sin(ang2) * FloatMath.sin(ang1),
                    (FloatMath.cos(ang1) - 1) * (wratio + 1) * -0.5f)
                .rotateY(dx * -90)
                .rotateX(dy * -90);

            effect.alpha *= Math.min(FloatMath.cos(ang1), 1);
            effect.need_border = true;
        }
        else if(transition == TransitionType.bookshelf) {
            float thr = 1.75f;
            float tdx = dx * thr;
            float radx = 1 - Math.min(1, Math.abs(dx));
            float atdx = Math.min(1, Math.abs(tdx));
            float ratdx = 1 - atdx;
            float sy = Math.min(1, Math.abs(dy));
            float rsy = 1 - sy;

            float rx = (dx < 0 ? 1 : -1) * (80 + radx * 10) + dx * 10;
            float tx = dx * 0.125f * wratio;
            float tz =
                Math.max(1, wratio) * -3 +
                wratio * Math.max(0, 1 - atdx);

            rx = rx * sy + rx * (1 - ratdx * ratdx) * rsy;
            tx += FloatMath.sin(Math.min(1, Math.max(-1, tdx)) *
                                (float)Math.PI) * 0.25f * wratio * rsy;
            tz = tz * sy + tz * Math.min(1, Math.abs(tdx)) * rsy;

            effect.matrix
                .translate(tx, dy * -2.1f, tz)
                .rotateY(rx)
                .translate(
                    (dx < 0 ? Math.max(-1, tdx) : Math.min(1, tdx)) * wratio,
                    0, 0);

            float dxy = Math.abs(dx) + Math.abs(dy);
            float dr =
                Math.abs(dx - Math.round(dx)) + Math.abs(dy - Math.round(dy));
            effect.alpha *= (dxy < 0.5f ? 1 : Math.min(1, dr * 8 / dxy));
            effect.need_border = true;
        }

        return effect;
    }

    private EffectInfo getHoneycombEffect(float xpos, float dy)
    {
        float dx = xpos - 0.5f;
        EffectInfo effect = new EffectInfo();

        float wr = wratio * (wratio < 1 ? 1 : 0.8f);
        effect.matrix
            .translate(0, 0, 4)
            .rotateX((dy < 1 ? -0.79f : +0.6f) * 14)
            .translate(dx * wr * 8.6f, 0, -28 + Math.abs(dx) * 2)
            .scale(0.8f, 0.8f, 2)
            .rotateY(dx * -32);

        return effect;
    }

    private EffectInfo mergeEffect(EffectInfo effect1, EffectInfo effect2,
                                   float ratio1)
    {
        float ratio2 = 1 - ratio1;

        EffectInfo effect = new EffectInfo();

        float md[] = effect.matrix.get();
        float m1[] = effect1.matrix.get();
        float m2[] = effect2.matrix.get();
        for(int i = 0; i < md.length; i++) {
            md[i] = m1[i] * ratio1 + m2[i] * ratio2;
        }

        if(effect1.clip_rect != null || effect2.clip_rect != null) {
            effect.clip_rect = new RectF(0, 0, 0, 0);

            if(effect1.clip_rect != null) {
                effect.clip_rect.left += effect1.clip_rect.left * ratio1;
                effect.clip_rect.top += effect1.clip_rect.top * ratio1;
                effect.clip_rect.right += effect1.clip_rect.right * ratio1;
                effect.clip_rect.bottom += effect1.clip_rect.bottom * ratio1;
            }
            else {
                effect.clip_rect.left += -wratio * ratio1;
                effect.clip_rect.top += ratio1;
                effect.clip_rect.right += wratio * ratio1;
                effect.clip_rect.bottom += -ratio1;
            }

            if(effect2.clip_rect != null) {
                effect.clip_rect.left += effect2.clip_rect.left * ratio2;
                effect.clip_rect.top += effect2.clip_rect.top * ratio2;
                effect.clip_rect.right += effect2.clip_rect.right * ratio2;
                effect.clip_rect.bottom += effect2.clip_rect.bottom * ratio2;
            }
            else {
                effect.clip_rect.left += -wratio * ratio2;
                effect.clip_rect.top += ratio2;
                effect.clip_rect.right += wratio * ratio2;
                effect.clip_rect.bottom += -ratio2;
            }
        }

        effect.alpha =
            effect1.alpha * ratio1 +
            effect2.alpha * ratio2;
        effect.fill_background =
            effect1.fill_background * ratio1 +
            effect2.fill_background * ratio2;
        effect.need_border = (effect1.need_border || effect2.need_border);

        return effect;
    }

    private GLColor getBackgroundColorNoAlpha(PictureInfo pic_info, float fade)
    {
        PictureStatus status = pic_info.status;

        if(status == PictureStatus.BLACKOUT ||
           status == PictureStatus.SPINNER ||
           status == PictureStatus.NOT_AVAILABLE) {
            return new GLColor();
        }

        GLColor color = new GLColor(pic_info.tex_info.bgcolor);
        color.red   *= fade;
        color.green *= fade;
        color.blue  *= fade;
        color.alpha = 1;

        return color;
    }

    private GLColor getBackgroundColor(PictureInfo pic_info,
                                       float dx, float dy, float dz, float fade)
    {
        float a =
            Math.max(0, (1 - Math.abs(dx))) *
            Math.max(0, (1 - Math.abs(dy))) * dz;
        return getBackgroundColorNoAlpha(pic_info, fade).setAlpha(a);
    }

    private void changeOffsets(OffsetInfo info)
    {
        ycur_honeycomb = 1f;

        if(workaround_launcher == LauncherWorkaroundType.htc_sense ||
           workaround_launcher == LauncherWorkaroundType.htc_sense_5screen) {
            // workaround for f*cking HTC Sense home app
            if(info.xstep < 0) {
                int ns = (workaround_launcher ==
                          LauncherWorkaroundType.htc_sense ? 7 : 5);
                float margin = 1f / (ns + 1);
                info.xstep = 1f / (ns - 1);
                info.xoffset = (info.xoffset - margin) / (1 - margin * 2);
            }
        }
        else if(workaround_launcher ==
                LauncherWorkaroundType.honeycomb_launcher) {
            // workaround for Honeycomb Tablet's launcher
            if(context.getResources().getConfiguration().orientation ==
               Configuration.ORIENTATION_PORTRAIT) {
                info.xoffset = (info.xoffset - 0.25f) * 2;
            }
            else {
                info.yoffset = (info.yoffset - 3f / 16f) * (16f / 10f);
            }

            ycur_honeycomb = (info.ystep <= 0 ? 1 : info.yoffset / info.ystep);

            info.ystep = 0;
            info.yoffset = 0;
        }
        else if(workaround_launcher == LauncherWorkaroundType.no_vertical) {
            // disable vertical
            info.ystep = 0;
            info.yoffset = 0;
        }
        else if(workaround_launcher == LauncherWorkaroundType.force_5screen) {
            info.xstep = 1f / 4f;
            info.ystep = 0;
            info.yoffset = 0;
        }
        else if(workaround_launcher == LauncherWorkaroundType.force_7screen) {
            info.xstep = 1f / 6f;
            info.ystep = 0;
            info.yoffset = 0;
        }

        // num of screens
        int xn = (info.xstep <= 0 ? 1 : Math.round(1 / info.xstep) + 1);
        int yn = (info.ystep <= 0 ? 1 : Math.round(1 / info.ystep) + 1);
        if(xn != xcnt || yn != ycnt) {
            synchronized(pic_whole_lock) {
                xcnt = xn;
                ycnt = yn;
                clearPictureSetting();
                updateScreenSize(null);
            }
        }

        // current screen position
        xcur = (info.xstep <= 0 ? 0 : info.xoffset / info.xstep);
        ycur = (info.ystep <= 0 ? 0 : info.yoffset / info.ystep);
    }

    private void postDurationCallback()
    {
        AlarmManager mgr = (AlarmManager)
            context.getSystemService(Context.ALARM_SERVICE);

        Intent intent = new Intent(ACTION_CHANGE_PICTURE);
        PendingIntent alarm_intent =
            PendingIntent.getBroadcast(context, 0, intent, 0);

        mgr.cancel(alarm_intent);

        if(change_duration > 0) {
            if(visible) {
                int duration_msec = change_duration * 1000;
                mgr.set(AlarmManager.ELAPSED_REALTIME,
                        SystemClock.elapsedRealtime() + duration_msec,
                        alarm_intent);

                is_duration_pending = false;
            }
            else {
                is_duration_pending = true;
            }
        }
    }

    private void loadPictureSetting()
    {
        int cnt = xcnt * ycnt;
        pic = new PictureInfo[cnt];

        // for each screen
        for(int i = 0; i < cnt; i++) {
            pic[i] = loadPictureInfo(i);
        }

        // for keyguard screen
        if(use_keyguard_pic) {
            keyguard_pic = loadPictureInfo(-1);
        }
    }

    private PictureInfo loadPictureInfo(int idx)
    {
        // (idx == -1) for keyguard screen
        String idx_key = (idx >= 0 ? String.valueOf(idx) :
                          MultiPictureSetting.SCREEN_KEYGUARD);

        // allocate info
        PictureInfo info = new PictureInfo();
        info.tex_info = new TextureInfo();

        // picture status, progress
        info.cur_content = null;
        info.setStatus(PictureStatus.BLACKOUT);
        info.loading_cnt = 1;

        // picture source service
        String service_str = pref.getString(
            MultiPictureSetting.getKey(
                MultiPictureSetting.SCREEN_PICSOURCE_SERVICE_KEY, idx_key),
            null);

        info.picsource_key = idx_key;

        if("".equals(service_str)) {
            // same as default
            info.picsource_service = default_picsource_service;
            info.picsource_key = default_picsource_key;
        }
        else if(service_str != null) {
            // lazy picker
            info.picsource_service =
                ComponentName.unflattenFromString(service_str);
        }
        else {
            // backward compatible
            String type_str = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_TYPE_KEY, idx_key), null);
            String fname = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_FILE_KEY, idx_key), null);

            ScreenType type =
                ((type_str == null && fname != null) ? ScreenType.file :
                 type_str == null ? ScreenType.use_default :
                 ScreenType.valueOf(type_str));

            if(type == ScreenType.use_default) {
                // same as default
                info.picsource_service = default_picsource_service;
                info.picsource_key = default_picsource_key;
            }
            else if(type == ScreenType.file) {
                // single file
                info.picsource_service =
                    new ComponentName(context, SinglePickService.class);
            }
            else if(type == ScreenType.folder) {
                // from folder
                info.picsource_service =
                    new ComponentName(context, FolderPickService.class);
            }
            else if(type == ScreenType.buckets) {
                // from album
                info.picsource_service =
                    new ComponentName(context, AlbumPickService.class);
            }
        }

        // lazy picker
        info.picker = new PickerClient(
            info.picsource_service, info.picsource_key, idx, info);
        if(! info.picker.start()) {
            setNotAvailableStatus(info, idx);
            info.loading_cnt = 0;
        }

        // background color
        String bgcolor = pref.getString(
            MultiPictureSetting.getKey(
                MultiPictureSetting.SCREEN_BGCOLOR_KEY, idx_key),
            "use_default");
        if("use_default".equals(bgcolor)) {
            info.detect_bgcolor = default_detect_bgcolor;
            info.bgcolor = default_bgcolor;
        }
        else if("auto_detect".equals(bgcolor)) {
            info.detect_bgcolor = true;
        }
        else if("custom".equals(bgcolor)) {
            info.detect_bgcolor = false;
            info.bgcolor = pref.getInt(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_BGCOLOR_CUSTOM_KEY, idx_key),
                0xff000000);
        }
        else {
            info.detect_bgcolor = false;
            info.bgcolor = Color.parseColor(bgcolor);
        }

        // clip ratio
        String clip = pref.getString(
            MultiPictureSetting.getKey(
                MultiPictureSetting.SCREEN_CLIP_KEY, idx_key),
            "use_default");
        if("use_default".equals(clip)) {
            info.clip_ratio = default_clip_ratio;
        }
        else {
            info.clip_ratio = Float.valueOf(clip);
        }

        // saturation
        String satu = pref.getString(
            MultiPictureSetting.getKey(
                MultiPictureSetting.SCREEN_SATURATION_KEY, idx_key),
            "use_default");
        if("use_default".equals(satu)) {
            info.saturation = default_saturation;
        }
        else {
            info.saturation = Float.valueOf(satu);
        }

        // opacity
        String opac = pref.getString(
            MultiPictureSetting.getKey(
                MultiPictureSetting.SCREEN_OPACITY_KEY, idx_key),
            "use_default");
        if("use_default".equals(opac)) {
            info.opacity = default_opacity;
        }
        else {
            info.opacity = Float.valueOf(opac);
        }

        return info;
    }

    private void setNotAvailableStatus(PictureInfo info, int idx)
    {
        info.setStatus(PictureStatus.NOT_AVAILABLE);

        // draw "not available"
        String str1 =
            (idx >= 0 ?
             context.getString(R.string.str_pic_not_avail_1, idx + 1) :
             context.getString(R.string.str_pic_not_avail_keyguard));
        String str2 = context.getString(R.string.str_pic_not_avail_2);

        Rect rect1 = new Rect();
        Rect rect2 = new Rect();
        text_paint.getTextBounds(str1, 0, str1.length(), rect1);
        text_paint.getTextBounds(str2, 0, str2.length(), rect2);
        int th = (rect1.height() + rect2.height()) / 2;

        int bw = getLeastPowerOf2GE(Math.max(rect1.width(), rect2.width()));
        int bh = getLeastPowerOf2GE(th * 4);

        info.tex_info.has_content = true;
        info.tex_info.enable_reflect = false;
        info.tex_info.bmp =
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_4444);
        info.tex_info.bgcolor = 0xff000000;
        info.tex_info.bwidth = bw;
        info.tex_info.bheight = bh;
        info.tex_info.xratio = (float)bw / width;
        info.tex_info.yratio = (float)bh / height;

        Canvas c = new Canvas(info.tex_info.bmp);
        c.drawColor(0, PorterDuff.Mode.SRC);
        c.drawText(str1, bw / 2, bh / 2 - th / 2, text_paint);
        c.drawText(str2, bw / 2, bh / 2 + th * 3 / 2, text_paint);
    }

    private void updateAllScreen(boolean fadeout)
    {
        if(pic == null) {
            return;
        }

        for(PictureInfo info : pic) {
            updateScreen(info, fadeout);
        }

        if(use_keyguard_pic && keyguard_pic != null) {
            updateScreen(keyguard_pic, fadeout);
        }
    }

    private void updateScreen(PictureInfo info, boolean fadeout)
    {
        if(visible) {
            if(info.loading_cnt == 0) {
                info.loading_cnt += 1;
                info.picker.sendGetNext();
            }

            if(fadeout && info.loading_cnt != 0) {
                if(info.status == PictureStatus.NORMAL ||
                   info.status == PictureStatus.FADEIN) {
                    info.setStatus(PictureStatus.FADEOUT);
                }
                else if(info.status == PictureStatus.NOT_AVAILABLE) {
                    info.setStatus(PictureStatus.BLACKOUT);
                }
            }
        }
        else if(! info.is_update_pending) {
            if(info.loading_cnt == 0) {
                info.loading_cnt += 1;
                info.is_update_pending = true;
            }
        }
    }

    private void sendUpdateScreen(int idx, PictureInfo info,
                                  PictureContentInfo content,
                                  boolean force_reload)
    {
        loader_handler
            .obtainMessage(
                MSG_UPDATE_SCREEN,
                new ContentUpdateInfo(idx, info, content, force_reload))
            .sendToTarget();
    }

    private void loadScreenContent(ContentUpdateInfo update_info)
    {
        int idx = update_info.idx;
        PictureInfo pic_info = update_info.pic_info;
        PictureContentInfo content = update_info.content;
        boolean force_reload = update_info.force_reload;

        int width;
        int height;

        synchronized(pic_whole_lock) {
            // check target screen
            if(pic == null ||
               pic.length <= idx ||
               (idx >= 0 && (pic[idx] == null ||
                             pic[idx] != pic_info)) ||
               (idx < 0 && (keyguard_pic == null ||
                            keyguard_pic != pic_info))) {
                return;
            }

            // not retrieved, or just reload
            if(content == null || content.getUri() == null) {
                if(pic_info.cur_content == null &&
                   ! pic_info.tex_info.has_content) {
                    setNotAvailableStatus(pic_info, idx);
                    pic_info.loading_cnt -= 1;
                    return;
                }
                else if(force_reload ||
                        ! pic_info.tex_info.has_content) {
                    content = pic_info.cur_content;
                    pic_info.cur_content = null;
                }
                else {
                    pic_info.loading_cnt -= 1;
                    if(pic_info.status == PictureStatus.BLACKOUT ||
                       pic_info.status == PictureStatus.SPINNER ||
                       pic_info.status == PictureStatus.FADEOUT) {
                        pic_info.setStatus(PictureStatus.FADEIN);
                    }
                    return;
                }
            }

            // set status and progress
            if(pic_info.status == PictureStatus.NOT_AVAILABLE ||
               ! pic_info.tex_info.has_content) {
                if(pic_info.status != PictureStatus.SPINNER) {
                    pic_info.setStatus(PictureStatus.BLACKOUT);
                }
            }
            else {
                if(pic_info.status == PictureStatus.NORMAL ||
                   pic_info.status == PictureStatus.FADEIN) {
                    pic_info.setStatus(PictureStatus.FADEOUT);
                }
            }

            // request to start redraw
            drawer_handler.sendEmptyMessage(MSG_DRAW);

            // save current width/height
            width = this.width;
            height = this.height;
        }

        // load texture
        TextureInfo tex_info = loadTexture(
            content.getUri(), content.getOrientation(),
            pic_info.clip_ratio, pic_info.saturation,
            width, height);

        if(tex_info != null) {
            // background color
            tex_info.bgcolor =
                (pic_info.detect_bgcolor ?
                 detectBackgroundColor(
                     tex_info.bmp, tex_info.xratio, tex_info.yratio) :
                 pic_info.bgcolor);
        }

        synchronized(pic_whole_lock) {
            while(true) {
                // check target screen
                if(pic == null ||
                   pic.length <= idx ||
                   (idx >= 0 && (pic[idx] == null ||
                                 pic[idx] != pic_info)) ||
                   (idx < 0 && (keyguard_pic == null ||
                                keyguard_pic != pic_info))) {
                    // already cleared: discard
                    if(tex_info != null && tex_info.bmp != null) {
                        tex_info.bmp.recycle();
                    }
                    return;
                }

                if(width != this.width ||
                   height != this.height) {
                    // retry to load same content
                    if(tex_info != null && tex_info.bmp != null) {
                        tex_info.bmp.recycle();
                    }
                    sendUpdateScreen(idx, pic_info, content, force_reload);
                    return;
                }

                if(pic_info.status != PictureStatus.BLACKOUT &&
                   pic_info.status != PictureStatus.SPINNER) {
                    // wait for fade-out
                    try {
                        pic_whole_lock.wait();
                    }
                    catch(InterruptedException e) {
                        // ignore
                    }
                    continue;
                }

                break;
            }

            if(tex_info != null) {
                if(pic_info.tex_info.has_content) {
                    // discard prev data
                    if(pic_info.tex_info.bmp != null) {
                        pic_info.tex_info.bmp.recycle();
                    }
                    else if(pic_info.tex_info.tex_id >= 0) {
                        drawer_handler.obtainMessage(
                            MSG_DELETE_TEXTURE,
                            pic_info.tex_info.tex_id, 0).sendToTarget();
                    }
                }

                // replace
                pic_info.cur_content = content;
                pic_info.tex_info = tex_info;
            }

            if(pic_info.tex_info.has_content) {
                // set status
                pic_info.setStatus(PictureStatus.FADEIN);
            }
            else if(pic_info.cur_content != null) {
                // reload cur_content: not change status
                sendUpdateScreen(idx, pic_info, null, true);
                return;
            }
            else {
                // picture not available
                setNotAvailableStatus(pic_info, idx);
            }

            pic_info.loading_cnt -= 1;

            // request to start redraw
            drawer_handler.sendEmptyMessage(MSG_DRAW);
        }
    }

    private TextureInfo loadTexture(Uri uri, int orientation,
                                    float clip_ratio, float saturation,
                                    int width, int height)
    {
        try {
            InputStream instream;
            BitmapFactory.Options opt;

            // orientation
            if(orientation < 0) {
                orientation += ((-orientation) / 360 + 1) * 360;
            }
            orientation %= 360;

            int target_width;
            int target_height;
            if(orientation != 90 && orientation != 270) {
                target_width = width;
                target_height = height;
            }
            else {
                target_width = height;
                target_height = width;
            }

            // query size of picture
            opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;

            instream = resolver.openInputStream(uri);
            if(instream == null) {
                return null;
            }
            try {
                BitmapFactory.decodeStream(instream, null, opt);
                if(opt.outWidth < 0 || opt.outHeight < 0) {
                    return null;
                }
            }
            finally {
                instream.close();
            }

            int ratio = 1;
            while(max_work_pixels > 0 &&
                  (opt.outWidth / ratio) *
                  (opt.outHeight / ratio) > max_work_pixels) {
                ratio += 1;
            }

            // read picture
            opt = new BitmapFactory.Options();
            opt.inDither = true;
            opt.inSampleSize = ratio;

            Bitmap bmp;
            instream = resolver.openInputStream(uri);
            if(instream == null) {
                return null;
            }
            try {
                bmp = BitmapFactory.decodeStream(instream, null, opt);
                if(bmp == null) {
                    return null;
                }
            }
            finally {
                instream.close();
            }

            // calc geometry of subset to draw
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            float bxs = (float)target_width / bw;
            float bys = (float)target_height / bh;
            float bmax = Math.max(bxs, bys);
            float bmin = Math.min(bxs, bys);
            float bscale = (bmax * clip_ratio +
                            bmin * (1 - clip_ratio));

            float cw = bw - target_width / bscale;
            float ch = bh - target_height / bscale;
            int src_x = (int)(cw < 0 ? 0 : cw / 2);
            int src_y = (int)(ch < 0 ? 0 : ch / 2);
            int src_w = bw - src_x * 2;
            int src_h = bh - src_y * 2;

            TextureInfo tex_info = new TextureInfo();
            float xratio = (cw < 0 ? bw * bscale / target_width : 1);
            float yratio = (ch < 0 ? bh * bscale / target_height : 1);
            if(orientation != 90 && orientation != 270) {
                tex_info.xratio = xratio;
                tex_info.yratio = yratio;
            }
            else {
                tex_info.xratio = yratio;
                tex_info.yratio = xratio;
            }

            // scale to power of 2
            int tex_width = getLeastPowerOf2GE((int)(src_w * bscale));
            int tex_height = getLeastPowerOf2GE((int)(src_h * bscale));
            while(max_screen_pixels > 0 &&
                  tex_width * tex_height > max_screen_pixels) {
                if((double)tex_width / target_width >=
                   (double)tex_height / target_height) {
                    tex_width /= 2;
                }
                else {
                    tex_height /= 2;
                }
            }

            Matrix mat = new Matrix();
            mat.setScale((float)tex_width / src_w, (float)tex_height / src_h);
            if(orientation != 0) {
                mat.preRotate(orientation, bw / 2f, bh / 2f);
            }

            tex_info.bmp = createBitmap(
                bmp, src_x, src_y, src_w, src_h,
                mat, tex_width, tex_height, saturation);
            bmp.recycle();

            tex_info.has_content = true;
            return tex_info;
        }
        catch(Exception e) {
            return null;
        }
    }

    private static int getLeastPowerOf2GE(int val)
    {
        int x = 1;

        while(x < val) {
            x *= 2;
        }

        return x;
    }

    private static float ratioRange(float v)
    {
        return Math.min(1, Math.max(0, v));
    }

    private int detectBackgroundColor(Bitmap bmp, float xratio, float yratio)
    {
        int w = bmp.getWidth();
        int h = bmp.getHeight();

        // resize if larger than MAX_DETECT_PIXELS
        int ratio = 1;
        while(w * h / (ratio * ratio) > MAX_DETECT_PIXELS) {
            ratio += 1;
        }
        w = w / ratio;
        h = h / ratio;
        bmp = Bitmap.createScaledBitmap(bmp, w, h, true);

        final int rex = (xratio < 1 ? 200 : 100);
        final int rey = (yratio < 1 ? 200 : 100);
        final int eratio = 10;

        int[] cnt = new int[0x1000];

        // search most significant color in [0x000...0xfff]
        Arrays.fill(cnt, 0);
        for(int y = 0; y < h; y++) {
            int ry = 0;
            if(y < h / eratio) {
                ry = (h - y * eratio) * rey / h;
            }
            else if(y > h * (eratio - 1) / eratio) {
                ry = (y * eratio - h * (eratio - 1)) * rey / h;
            }

            for(int x = 0; x < w; x++) {
                int rx = 0;
                if(x < w / eratio) {
                    rx = (w - x * eratio) * rex / w;
                }
                else if(x > w * (eratio - 1) / eratio) {
                    rx = (x * eratio - w * (eratio - 1)) * rex / w;
                }

                int c = bmp.getPixel(x, y);
                c = ((c >> 12) & 0xf00 |
                     (c >>  8) & 0x0f0 |
                     (c >>  4) & 0x00f);

                cnt[c] += 10 + rx + ry;
            }
        }

        // search max
        int base_color = 0;
        for(int i = 1; i < cnt.length; i++) {
            if(cnt[base_color] < cnt[i]) {
                base_color = i;
            }
        }

        // search most significant color more detailed
        Arrays.fill(cnt, 0);
        for(int y = 0; y < h; y++) {
            int ry = 0;
            if(y < h / eratio) {
                ry = (h - y * eratio) * rey / h;
            }
            else if(y > h * (eratio - 1) / eratio) {
                ry = (y * eratio - h * (eratio - 1)) * rey / h;
            }

            for(int x = 0; x < w; x++) {
                int rx = 0;
                if(x < w / eratio) {
                    rx = (w - x * eratio) * rex / w;
                }
                else if(x > w * (eratio - 1) / eratio) {
                    rx = (x * eratio - w * (eratio - 1)) * rex / w;
                }

                int c = bmp.getPixel(x, y);
                int cb = ((c >> 12) & 0xf00 |
                          (c >>  8) & 0x0f0 |
                          (c >>  4) & 0x00f);
                if(cb != base_color) {
                    continue;
                }

                int cd = ((c >>  8) & 0xf00 |
                          (c >>  4) & 0x0f0 |
                          (c >>  0) & 0x00f);

                cnt[cd] += 10 + rx + ry;
            }
        }

        bmp.recycle();

        // search max
        int detail_color = 0;
        for(int i = 1; i < cnt.length; i++) {
            if(cnt[detail_color] < cnt[i]) {
                detail_color = i;
            }
        }

        int color = (((base_color & 0xf00) << 12) |
                     ((base_color & 0x0f0) <<  8) |
                     ((base_color & 0x00f) <<  4) |
                     ((detail_color & 0xf00) << 8) |
                     ((detail_color & 0x0f0) << 4) |
                     ((detail_color & 0x00f) << 0));
        return color;
    }

    private Bitmap createBitmap(Bitmap src,
                                int x, int y, int width, int height,
                                Matrix m, int dst_width, int dst_height,
                                float saturation)
    {
        Canvas canvas = new Canvas();
        Bitmap bmp;
        boolean has_alpha =
            (src.hasAlpha() || (m != null && ! m.rectStaysRect()));
        Bitmap.Config format =
            getBitmapFormat(dst_width, dst_height, has_alpha);
        Paint paint = new Paint();

        Rect src_rect = new Rect(x, y, x + width, y + height);
        RectF dst_rect = new RectF(0, 0, width, height);

        if(m != null && ! m.isIdentity()) {
            // with scale
            RectF device_rect = new RectF();
            m.mapRect(device_rect, dst_rect);

            canvas.translate(-device_rect.left, -device_rect.top);
            canvas.concat(m);

            paint.setFilterBitmap(true);
            if(! m.rectStaysRect()) {
                paint.setAntiAlias(true);
            }
        }

        bmp = Bitmap.createBitmap(dst_width, dst_height, format);
        bmp.eraseColor(0);

        // color filter
        if(saturation != 1.0f) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(saturation);
            paint.setColorFilter(new ColorMatrixColorFilter(cm));
        }

        paint.setDither(true);

        bmp.setDensity(src.getDensity());
        canvas.setBitmap(bmp);
        canvas.drawBitmap(src, src_rect, dst_rect, paint);

        return bmp;
    }

    private Bitmap.Config getBitmapFormat(int width, int height,
                                          boolean has_alpha)
    {
        boolean allow_8888 = (max_screen_pixels <= 0 ||
                              width * height * 2 <= max_screen_pixels);
        return (allow_8888 ? Bitmap.Config.ARGB_8888 :
                has_alpha ? Bitmap.Config.ARGB_4444 :
                Bitmap.Config.RGB_565);
    }

    private class PreferenceChangedListener
        implements SharedPreferences.OnSharedPreferenceChangeListener
    {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences pref,
                                              String key)
        {
            boolean is_unneccesary_reload = false;
            for(String k : UNNECCESARY_RELOAD_KEYS) {
                if(k.equals(key)) {
                    is_unneccesary_reload = true;
                    break;
                }
            }

            if(is_unneccesary_reload) {
                drawer_handler.sendEmptyMessage(MSG_PREF_CHANGED_NORELOAD);
            }
            else {
                drawer_handler.sendEmptyMessage(MSG_PREF_CHANGED);
            }
        }
    }

    private class Receiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            if(ACTION_CHANGE_PICTURE.equals(intent.getAction())) {
                // change by interval
                drawer_handler.sendEmptyMessage(MSG_CHANGE_PIC_BY_TIME);
            }
            else if(Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction()) &&
                    intent.getData() != null) {
                String pkgname =
                    intent.getData().getEncodedSchemeSpecificPart();
                drawer_handler.obtainMessage(MSG_CHANGE_PACKAGE_AVAIL,
                                             new String[] { pkgname })
                    .sendToTarget();
            }
            else if(ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(
                        intent.getAction())) {
                String[] pkgnames = intent.getStringArrayExtra(
                    EXTRA_CHANGED_PACKAGE_LIST);
                drawer_handler.obtainMessage(MSG_CHANGE_PACKAGE_AVAIL, pkgnames)
                    .sendToTarget();
            }
            else if(Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                drawer_handler.sendEmptyMessage(MSG_KEYGUARD_CHANGED);
            }
        }
    }

    private ScreenInfo getScreenHint(int idx)
    {
        ScreenInfo hint = new ScreenInfo();
        hint.setScreenNumber(idx);
        hint.setScreenColumns(xcnt);
        hint.setScreenRows(ycnt);
        hint.setTargetColumn(idx >= 0 ? idx % xcnt : -1);
        hint.setTargetRow(idx >= 0 ? idx / xcnt : -1);
        hint.setScreenWidth(width);
        hint.setScreenHeight(height);
        hint.setChangeFrequency(change_duration);

        return hint;
    }

    private class PickerClient extends LazyPickerClient
    {
        private int idx;
        private PictureInfo pic_info;

        private PickerClient(ComponentName comp, String key,
                             int idx, PictureInfo pic_info)
        {
            super(context, comp, key, getScreenHint(idx),
                  picsource_thread.getLooper());

            this.idx = idx;
            this.pic_info = pic_info;
        }

        @Override
        protected void onStartCompleted()
        {
            sendGetNext();
        }

        @Override
        protected void onStopCompleted()
        {
            pic_info = null;
        }

        @Override
        protected void onReceiveNext(PictureContentInfo content)
        {
            sendUpdateScreen(idx, pic_info, content, false);
        }

        @Override
        protected void onNotifyChanged()
        {
            if(pic_info != null) {
                synchronized(pic_whole_lock) {
                    if(visible) {
                        pic_info.loading_cnt += 1;
                        sendGetNext();
                    }
                    else if(! pic_info.is_update_pending) {
                        pic_info.loading_cnt += 1;
                        pic_info.is_update_pending = true;
                    }

                    if(pic_info.status == PictureStatus.NOT_AVAILABLE) {
                        pic_info.setStatus(PictureStatus.BLACKOUT);
                        drawer_handler.sendEmptyMessage(MSG_DRAW);
                    }
                }
            }
        }
    }
}
