package org.tamanegi.wallpaper.multipicture;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

import org.tamanegi.wallpaper.multipicture.picsource.AlbumPickService;
import org.tamanegi.wallpaper.multipicture.picsource.FolderPickService;
import org.tamanegi.wallpaper.multipicture.picsource.SinglePickService;
import org.tamanegi.wallpaper.multipicture.plugin.LazyPickerClient;
import org.tamanegi.wallpaper.multipicture.plugin.PictureContentInfo;
import org.tamanegi.wallpaper.multipicture.plugin.ScreenInfo;

import android.app.ActivityManager;
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
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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

    // message id: for loader
    private static final int MSG_UPDATE_SCREEN = 1001;

    // animation params
    private static final int FADE_FRAME_DURATION = 70;      // msec
    private static final int FADE_TOTAL_DURATION = 500;     // msec
    private static final int BLACKOUT_TOTAL_DURATION = 500; // msec
    private static final int SPINNER_FRAME_DURATION = 100;  // msec
    private static final int SPINNER_TOTAL_FRAMES = 15;     // count
    private static final int BORDER_COLOR = 0x3f3f3f;

    // transition params
    private static final int TRANSITION_RANDOM_TIMEOUT = 500; // msec

    // keyguard params
    private static final int KEYGUARD_FRAME_DURATION = 70;  // msec
    private static final int KEYGUARD_FADE_DURATION = 1000;  // msec

    // maximum size of pictures
    private static final int PIXELS_PER_MB = 1024 * 1024 / 2; // 512kPixels/MB
    private static final int MAX_DETECT_PIXELS = 8 * 1024; // 8kPixels

    private static final int MIN_MEMORY_CLASS = 16;
    private static final int MAX_MEMORY_CLASS = 32;
    private static final int MEMORY_CLASS_OFFSET = 8;

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
            slide, crossfade, fade_inout, zoom_inout,
            wipe, card,
            slide_3d, rotation_3d, swing, swap, cube
    }

    private static TransitionType[] random_transition = {
        TransitionType.slide,
        TransitionType.crossfade,
        TransitionType.fade_inout,
        TransitionType.zoom_inout,
        TransitionType.wipe,
        TransitionType.card,
        TransitionType.slide_3d,
        TransitionType.rotation_3d,
        TransitionType.swing,
        TransitionType.swap,
        TransitionType.cube,
    };

    // screen types: for backward compatible
    private static enum ScreenType
    {
        file, folder, buckets, use_default
    }

    // louncher workaround type
    private static enum LauncherWorkaroundType
    {
        none, htc_sense, honeycomb_launcher, no_vertical
    }

    // picture status
    private static enum PictureStatus
    {
        NOT_AVAILABLE,                          // no pic
            NORMAL,                             // normal picture
            FADEOUT, BLACKOUT, SPINNER, FADEIN, // progress
    }

    // bitmap data, and aspect ratio
    private static class BitmapInfo
    {
        private Bitmap bmp;
        private float xratio;
        private float yratio;
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

        private BitmapInfo bmp_info;

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
                progress = Math.max(FADE_TOTAL_DURATION - progress, 0);
            }
            else {
                progress = 0;
            }
            this.status = status;
        }
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
    private boolean visible = false;
    private SurfaceHolder holder;

    private int xcnt = 1;
    private int ycnt = 1;
    private float xcur = 0f;
    private float ycur = 0f;

    private int max_screen_pixels;
    private int max_work_pixels;
    private int max_width;
    private int max_height;

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
    private LauncherWorkaroundType launcher_workaround;

    private int last_duration = 0;
    private boolean is_in_transition = false;
    private long transition_prev_time = 0;
    private TransitionType cur_transition;
    private int cur_color;

    private boolean use_keyguard_pic;
    private boolean is_in_keyguard;
    private boolean is_keyguard_visible;
    private float keyguard_dx;
    private PictureInfo keyguard_pic;
    private long keyguard_prev_time = 0;

    private boolean is_duration_pending = false;

    private Bitmap spinner = null;

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
        this.holder = holder;

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
              holder = info.holder;
              synchronized(pic_whole_lock) {
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

        // spinner bitmap
        spinner = BitmapFactory.decodeResource(
            context.getResources(), R.drawable.spinner);

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
            clearPictureSetting();
            loadGlobalSetting();
        }
    }

    private void destroy()
    {
        synchronized(pic_whole_lock) {
            // conf
            clearPictureSetting();
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

        if(info.bmp_info != null) {
            info.bmp_info.bmp.recycle();
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
        if(info.bmp_info == null) {
            return;
        }

        info.bmp_info.bmp.recycle();
        info.bmp_info = null;

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
            //false);
            true);                              // dbg:
        is_in_keyguard = false;

        // workaround
        boolean sense_workaround_val =
            pref.getBoolean("workaround.htcsense", true);
        String launcher_workaround_str =
            pref.getString("workaround.launcher",
                           (sense_workaround_val ? "htc_sense" : "none"));
        launcher_workaround =
            LauncherWorkaroundType.valueOf(launcher_workaround_str);
    }

    private void updateScreenSize(SurfaceInfo info)
    {
        int cnt = xcnt * ycnt + (use_keyguard_pic ? 1 : 0);

        if(info != null) {
            // screen size
            this.width = info.width;
            this.height = info.height;
        }

        // restrict by memory class
        int mclass = ((ActivityManager)context.getSystemService(
                          Context.ACTIVITY_SERVICE)).getMemoryClass();
        mclass = Math.min(Math.max(mclass, MIN_MEMORY_CLASS),
                          MAX_MEMORY_CLASS) - MEMORY_CLASS_OFFSET;
        int max_total_pixels = mclass * PIXELS_PER_MB;

        // restrict size
        if(width * height * (cnt + 3) > max_total_pixels) {
            // mw * mh * (cnt + 3) = max_total_pixels
            // mw / mh = width / height
            //  -> mh = mw * height / width
            //  -> mw^2 = max_total_pixels / (height * (cnt + 3)) * width
            max_width = (int)
                Math.sqrt(max_total_pixels / (height * (cnt + 3)) * width);
            max_height = max_width * height / width;
        }
        else {
            max_width = width;
            max_height = height;
        }

        max_screen_pixels = max_total_pixels / (cnt + 3);
        max_work_pixels = max_screen_pixels * 2;
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
                              Math.abs(xn - xcur) < 1 &&
                              Math.abs(yn - ycur) < 1);
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
               info.bmp_info == null) {
                info.loading_cnt += 1;
                sendUpdateScreen(i, info, null, true);
            }
        }

        if(use_keyguard_pic) {
            if(keyguard_pic.loading_cnt == 0 &&
               keyguard_pic.cur_content != null &&
               keyguard_pic.bmp_info == null) {
                keyguard_pic.loading_cnt += 1;
                sendUpdateScreen(-1, keyguard_pic, null, true);
            }
        }

        // draw
        Canvas c = null;
        try {
            c = holder.lockCanvas();
            if(c != null) {
                drawPicture(c);
            }
        }
        finally {
            if(c != null) {
                holder.unlockCanvasAndPost(c);
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

    private void drawPicture(Canvas c)
    {
        // delta
        int xn = (int)Math.floor(xcur);
        int yn = (int)Math.floor(ycur);
        float dx = xn - xcur;
        float dy = yn - ycur;

        // delta for each screen
        class ScreenDelta implements Comparable<ScreenDelta>
        {
            int idx;
            float dx;
            float dy;
            float da;
            boolean visible;
            boolean for_lock;

            ScreenDelta(int xn, int yn, float dx, float dy,
                        boolean visible, boolean for_lock)
            {
                this.idx = xcnt * yn + xn;
                this.dx = dx;
                this.dy = dy;
                this.da = (for_lock ? 1 : keyguard_dx);
                this.visible = (visible && idx < pic.length && da > 0);
                this.for_lock = for_lock;
            }

            @Override
            public int compareTo(ScreenDelta o2)
            {
                if(for_lock != o2.for_lock) {
                    return (for_lock ? +1 : -1);
                }

                // reverse order
                float d1 = Math.max(Math.abs(this.dx), Math.abs(this.dy));
                float d2 = Math.max(Math.abs(o2.dx), Math.abs(o2.dy));
                return (d1 < d2 ? +1 :
                        d1 > d2 ? -1 :
                        0);
            }
        }

        ScreenDelta[] ds = {
            new ScreenDelta(xn,     yn,     dx,     dy,     true, false),
            new ScreenDelta(xn + 1, yn,     dx + 1, dy,     (dx != 0), false),
            new ScreenDelta(xn,     yn + 1, dx,     dy + 1, (dy != 0), false),
            new ScreenDelta(xn + 1, yn + 1, dx + 1, dy + 1,
                            (dx != 0 && dy != 0), false),
            new ScreenDelta(-1, 0, keyguard_dx, 0, is_keyguard_visible, true),
        };

        // for random transition
        if(((! is_in_transition) &&
            (screen_transition == TransitionType.random) &&
            (dx != 0 || dy != 0) &&
            (transition_prev_time + TRANSITION_RANDOM_TIMEOUT <
             SystemClock.elapsedRealtime())) ||
           (cur_transition == TransitionType.random)) {
            TransitionType next_transition;
            do {
                next_transition = random_transition[
                    random.nextInt(random_transition.length)];
            } while(next_transition == cur_transition);
            cur_transition = next_transition;
        }

        if(dx != 0 || dy != 0) {
            is_in_transition = true;
        }
        else {
            is_in_transition = false;
            transition_prev_time = SystemClock.elapsedRealtime();
        }

        // background color
        int color = 0;
        for(ScreenDelta s : ds) {
            if(s.visible) {
                int cc = getBackgroundColor(s.idx, s.dx, s.dy, s.da);
                color = mergeColor(color, cc);
            }
        }

        cur_color = ((color & 0x00ffffff) | 0xff000000);
        paint.setColor(cur_color);
        c.drawColor(cur_color);

        // draw each screen
        if(cur_transition == TransitionType.swap ||
           cur_transition == TransitionType.cube) {
            Arrays.sort(ds);
        }
        for(ScreenDelta s : ds) {
            if(s.visible) {
                drawPicture(c, s.idx, s.dx, s.dy, s.da);
            }
        }
    }

    private void drawPicture(Canvas c, int idx, float dx, float dy, float da)
    {
        PictureInfo pic_info = (idx >= 0 ? pic[idx] : keyguard_pic);
        PictureStatus status = pic_info.status;

        Matrix matrix = new Matrix();
        RectF clip_rect = null;
        int alpha = (int)(0xff * da);
        boolean fill_background = false;
        boolean need_border = false;

        // transition effect
        TransitionType transition =
            (idx >= 0 ? cur_transition : TransitionType.fade_inout);
        if(transition == TransitionType.none) {
            if(dx <= -0.5 || dx > 0.5 ||
               dy <= -0.5 || dy > 0.5) {
                return;
            }
            fill_background = true;
        }
        else if(transition == TransitionType.crossfade) {
            alpha *= ((1 - Math.abs(dx)) * (1 - Math.abs(dy)));
        }
        else if(transition == TransitionType.fade_inout) {
            if(dx <= -0.5 || dx > 0.5 ||
               dy <= -0.5 || dy > 0.5) {
                return;
            }
            alpha *= (1 - Math.max(Math.abs(dx), Math.abs(dy)) * 2);
        }
        else if(transition == TransitionType.slide) {
            matrix.postTranslate(width * dx, height * dy);
            fill_background = true;
        }
        else if(transition == TransitionType.zoom_inout) {
            float fact = Math.min(1 - Math.abs(dx), 1 - Math.abs(dy));
            matrix.postScale(fact, fact, width / 2f, height / 2f);
            matrix.postTranslate(width * dx / 2, height * dy / 2);
        }
        else if(transition == TransitionType.wipe) {
            clip_rect = new RectF((dx <= 0 ? 0 : width * dx),
                                  (dy <= 0 ? 0 : height * dy),
                                  (dx <= 0 ? width * (1 + dx) : width),
                                  (dy <= 0 ? height * (1 + dy) : height));
            fill_background = true;
        }
        else if(transition == TransitionType.card) {
            int sx = (dx < 0 ? 0 : (int)(width * dx));
            int sy = (dy < 0 ? 0 : (int)(height * dy));
            matrix.postTranslate(sx, sy);
            fill_background = true;
        }
        else if(transition == TransitionType.slide_3d) {
            if(dx > 0.6 || dy > 0.6) {
                return;
            }

            Camera camera = new Camera();
            final float ratio = 0.8f;
            camera.translate(dx * width * ratio, dy * height * -ratio,
                             (dx + dy) * -1000);
            camera.getMatrix(matrix);

            final float center = 0.3f;
            matrix.preTranslate(-width * center, -height * center);
            matrix.postTranslate(width * center, height * center);

            alpha *= Math.min(Math.min(dx, dy) + 1, 1);
        }
        else if(transition == TransitionType.rotation_3d) {
            if(dx <= -0.5 || dx > 0.5 ||
               dy <= -0.5 || dy > 0.5) {
                return;
            }

            float fact = 1 - ((1 - Math.abs(dx)) * (1 - Math.abs(dy)));
            Camera camera = new Camera();
            camera.translate(0, 0, fact * 500);
            camera.rotateY(dx * 180);
            camera.rotateX(dy * -180);
            camera.getMatrix(matrix);

            matrix.preTranslate(-width / 2, -height / 2);
            matrix.postTranslate(width / 2, height / 2);

            need_border = true;
        }
        else if(transition == TransitionType.swing) {
            Camera camera = new Camera();
            camera.rotateY(dx * -90);
            camera.rotateX(dy * 90);
            camera.getMatrix(matrix);

            float tx = (dx <= 0 ? 0 : -width);
            float ty = (dy <= 0 ? 0 : -height);
            matrix.preTranslate(tx, ty);
            matrix.postTranslate(-tx, -ty);

            need_border = true;
        }
        else if(transition == TransitionType.swap) {
            float fact = Math.max(Math.abs(dx), Math.abs(dy));
            float ang1 = (float)(fact * Math.PI);
            float ang2 = (float)Math.atan2(-dy, dx);

            Camera camera = new Camera();
            camera.translate(
                FloatMath.cos(ang2) * FloatMath.sin(ang1) * width * 0.51f,
                FloatMath.sin(ang2) * FloatMath.sin(ang1) * height * 0.51f,
                (1 - FloatMath.cos(ang1)) * 100);
            camera.getMatrix(matrix);

            matrix.preTranslate(-width / 2, -height / 2);
            matrix.postTranslate(width / 2, height / 2);

            alpha *= Math.min((FloatMath.cos(ang1) + 1) * 2, 1);
        }
        else if(transition == TransitionType.cube) {
            float fact = Math.max(Math.abs(dx), Math.abs(dy));
            float ang1 = (float)(fact * Math.PI / 2);
            float ang2 = (float)Math.atan2(-dy, dx);

            Camera camera = new Camera();
            camera.translate(
                FloatMath.cos(ang2) * FloatMath.sin(ang1) * width * 0.5f,
                FloatMath.sin(ang2) * FloatMath.sin(ang1) * height * 0.5f,
                (1 - FloatMath.cos(ang1)) * 200);
            camera.rotateY(dx * 90);
            camera.rotateX(dy * -90);
            camera.getMatrix(matrix);

            matrix.preTranslate(-width / 2, -height / 2);
            matrix.postTranslate(width / 2, height / 2);

            alpha *= Math.min(FloatMath.cos(ang1), 1);
            need_border = true;
        }

        // clip region
        if(clip_rect != null) {
            c.save();
            c.clipRect(clip_rect);
        }

        // draw params
        boolean use_bmp = (status == PictureStatus.NORMAL ||
                           status == PictureStatus.FADEIN ||
                           status == PictureStatus.FADEOUT);
        Bitmap bmp = (use_bmp ? pic_info.bmp_info.bmp : null);
        float xratio = (use_bmp ? pic_info.bmp_info.xratio : 1);
        float yratio = (use_bmp ? pic_info.bmp_info.yratio : 1);
        int bgcolor = getBackgroundColorNoAlpha(idx);
        int fade_step =
            (status == PictureStatus.NORMAL ? 0 :
             status == PictureStatus.FADEOUT ? pic_info.progress :
             status == PictureStatus.FADEIN ?
             FADE_TOTAL_DURATION - pic_info.progress :
             FADE_TOTAL_DURATION);
        fade_step = (fade_step < 0 ? 0 :
                     fade_step > FADE_TOTAL_DURATION ? FADE_TOTAL_DURATION :
                     fade_step);

        if(fill_background) {
            // background
            paint.setColor(bgcolor);
            paint.setAlpha(0xff);
            paint.setStyle(Paint.Style.FILL);

            c.save();
            c.concat(matrix);
            c.drawRect(0, 0, width, height, paint);
            c.restore();
        }

        if(need_border && fade_step > 0) {
            // border
            paint.setColor(BORDER_COLOR);
            paint.setAlpha(alpha * fade_step / FADE_TOTAL_DURATION);
            paint.setStyle(Paint.Style.STROKE);

            c.save();
            c.concat(matrix);
            c.drawRect(-1, -1, width, height, paint);
            c.restore();
        }

        if(status == PictureStatus.SPINNER) {
            // spinner
            paint.setColor(0);
            paint.setAlpha(alpha);
            paint.setStyle(Paint.Style.FILL);
            matrix.preRotate(
                360f *
                (int)((SystemClock.uptimeMillis() / SPINNER_FRAME_DURATION) %
                      SPINNER_TOTAL_FRAMES) / SPINNER_TOTAL_FRAMES,
                width / 2f, height / 2f);

            c.save();
            c.concat(matrix);
            c.drawBitmap(spinner,
                         (width - spinner.getWidth()) / 2f,
                         (height - spinner.getHeight()) / 2f,
                         paint);
            c.restore();
        }
        else if(status == PictureStatus.NOT_AVAILABLE) {
            // show "not available"
            String str1 =
                (idx >= 0 ?
                 context.getString(R.string.str_pic_not_avail_1, idx + 1) :
                 context.getString(R.string.str_pic_not_avail_keyguard));
            String str2 = context.getString(R.string.str_pic_not_avail_2);

            Rect rect1 = new Rect();
            Rect rect2 = new Rect();
            text_paint.getTextBounds(str1, 0, str1.length(), rect1);
            text_paint.getTextBounds(str2, 0, str2.length(), rect2);

            int str1_h = Math.abs(rect1.top - rect1.bottom);
            int str2_h = Math.abs(rect2.top - rect2.bottom);

            text_paint.setAlpha(alpha);
            paint.setAlpha(alpha);
            c.save();
            c.concat(matrix);
            c.drawText(str1, width / 2, height / 2 - str1_h, text_paint);
            c.drawText(str2, width / 2, height / 2 + str2_h, text_paint);
            c.restore();
        }
        else if(use_bmp) {
            // alpha with fade
            alpha = alpha *
                (FADE_TOTAL_DURATION - fade_step) / FADE_TOTAL_DURATION;
            ColorFilter cfilter = null;
            if(fade_step > 0) {
                cfilter = new PorterDuffColorFilter(
                    (0xff * fade_step / FADE_TOTAL_DURATION) << 24,
                    PorterDuff.Mode.SRC_ATOP);
            }

            // matrix for main bitmap
            Matrix mcenter = new Matrix(matrix);
            mcenter.preTranslate(width * (1 - xratio) / 2,
                                 height * (1 - yratio) / 2);
            mcenter.preScale(width * xratio / bmp.getWidth(),
                             height * yratio / bmp.getHeight());

            // draw content picture
            paint.setColor(cur_color);
            paint.setAlpha((int)(alpha * pic_info.opacity));
            if(cfilter != null) {
                paint.setColorFilter(cfilter);
            }

            c.drawBitmap(bmp, mcenter, paint);

            // mirrored picture: top
            if(show_reflection_top) {
                Matrix mtop = new Matrix(mcenter);
                mtop.preScale(1, -1, 0, 0);

                if(fill_background) {
                    paint.setColor(bgcolor);
                    paint.setAlpha(0xff);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColorFilter(null);

                    c.save();
                    c.concat(mtop);
                    c.drawRect(0, 0, bmp.getWidth(), bmp.getHeight(), paint);
                    c.restore();
                }

                paint.setColor(cur_color);
                paint.setAlpha((int)(alpha * pic_info.opacity / 4));
                if(cfilter != null) {
                    paint.setColorFilter(cfilter);
                }

                c.drawBitmap(bmp, mtop, paint);
            }

            // mirrored picture: bottom
            if(show_reflection_bottom) {
                Matrix mbottom = new Matrix(mcenter);
                mbottom.preScale(1, -1, 0, bmp.getHeight());

                if(fill_background) {
                    paint.setColor(bgcolor);
                    paint.setAlpha(0xff);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColorFilter(null);

                    c.save();
                    c.concat(mbottom);
                    c.drawRect(0, 0, bmp.getWidth(), bmp.getHeight(), paint);
                    c.restore();
                }

                paint.setColor(cur_color);
                paint.setAlpha((int)(alpha * pic_info.opacity / 4));
                if(cfilter != null) {
                    paint.setColorFilter(cfilter);
                }

                c.drawBitmap(bmp, mbottom, paint);
            }

            paint.setColorFilter(null);
        }

        // restore clip region
        if(clip_rect != null) {
            c.restore();
        }
    }

    private int getBackgroundColorNoAlpha(int idx)
    {
        PictureInfo pic_info = (idx >= 0 ? pic[idx] : keyguard_pic);
        PictureStatus status = pic_info.status;

        if(status == PictureStatus.BLACKOUT ||
           status == PictureStatus.SPINNER ||
           status == PictureStatus.NOT_AVAILABLE) {
            return 0xff000000;
        }

        int color = pic_info.bgcolor;
        if(status == PictureStatus.FADEIN ||
           status == PictureStatus.FADEOUT) {
            int p = (pic_info.status == PictureStatus.FADEIN ?
                     pic_info.progress :
                     FADE_TOTAL_DURATION - pic_info.progress);
            p = (p < 0 ? 0 :
                 p > FADE_TOTAL_DURATION ? FADE_TOTAL_DURATION :
                 p);

            color = mergeColor(
                (color & 0x00ffffff) | (0xff * p / FADE_TOTAL_DURATION) << 24,
                (0xff * (FADE_TOTAL_DURATION - p) / FADE_TOTAL_DURATION) << 24);
        }

        return color;
    }

    private int getBackgroundColor(int idx, float dx, float dy, float da)
    {
        int color = getBackgroundColorNoAlpha(idx);
        float a = (1 - Math.abs(dx)) * (1 - Math.abs(dy)) * da;
        return ((color & 0x00ffffff) |
                ((int)(0xff * a) << 24));
    }

    private int mergeColor(int c1, int c2)
    {
        float a1 = ((c1 >> 24) & 0xff) / (float)0xff;
        int r1 = (c1 >> 16) & 0xff;
        int g1 = (c1 >>  8) & 0xff;
        int b1 = (c1 >>  0) & 0xff;

        float a2 = ((c2 >> 24) & 0xff) / (float)0xff;
        int r2 = (c2 >> 16) & 0xff;
        int g2 = (c2 >>  8) & 0xff;
        int b2 = (c2 >>  0) & 0xff;

        return ((0xff << 24) |
                ((int)(r1 * a1 + r2 * a2) << 16) |
                ((int)(g1 * a1 + g2 * a2) <<  8) |
                ((int)(b1 * a1 + b2 * a2) <<  0));
    }

    private void changeOffsets(OffsetInfo info)
    {
        if(launcher_workaround == LauncherWorkaroundType.htc_sense) {
            // workaround for f*cking HTC Sense home app
            if(info.xstep < 0) {
                info.xstep = 1.0f / 6.0f;
                info.xoffset = (info.xoffset - 0.125f) * (1.0f / 0.75f);
            }
        }
        else if(launcher_workaround ==
                LauncherWorkaroundType.honeycomb_launcher) {
            // workaround for Honeycomb Tablet's launcher
            if(context.getResources().getConfiguration().orientation ==
               Configuration.ORIENTATION_PORTRAIT) {
                info.xoffset = (info.xoffset - 0.25f) * 2;
            }

            if(info.yoffset != 0.5) {
                info.xoffset = xcur * info.xstep;
            }

            info.ystep = 0;
            info.yoffset = 0;
        }
        else if(launcher_workaround == LauncherWorkaroundType.no_vertical) {
            // disable vertical: such as Honeycomb's tablet launcher
            info.ystep = 0;
            info.yoffset = 0;
        }

        // num of screens
        int xn = (info.xstep <= 0 ? 1 : (int)(1 / info.xstep) + 1);
        int yn = (info.ystep <= 0 ? 1 : (int)(1 / info.ystep) + 1);
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
            info.setStatus(PictureStatus.NOT_AVAILABLE);
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

        int max_width;
        int max_height;

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
                   pic_info.bmp_info == null) {
                    pic_info.setStatus(PictureStatus.NOT_AVAILABLE);
                    pic_info.loading_cnt -= 1;
                    return;
                }
                else if(force_reload ||
                        pic_info.bmp_info == null) {
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
               pic_info.bmp_info == null) {
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
            max_width = this.max_width;
            max_height = this.max_height;
        }

        // load bitmap
        BitmapInfo bmp_info = loadBitmap(
            content.getUri(), content.getOrientation(),
            pic_info.clip_ratio, pic_info.saturation,
            max_width, max_height);

        int bgcolor = 0;
        if(bmp_info != null) {
            // background color
            if(pic_info.detect_bgcolor) {
                bgcolor = detectBackgroundColor(
                    bmp_info.bmp, bmp_info.xratio, bmp_info.yratio);
            }
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
                    if(bmp_info != null) {
                        bmp_info.bmp.recycle();
                    }
                    return;
                }

                if(max_width != this.max_width ||
                   max_height != this.max_height) {
                    // retry to load same content
                    if(bmp_info != null) {
                        bmp_info.bmp.recycle();
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

            if(bmp_info != null) {
                if(pic_info.bmp_info != null) {
                    // discard prev data
                    pic_info.bmp_info.bmp.recycle();
                }

                // replace
                pic_info.cur_content = content;
                pic_info.bmp_info = bmp_info;
                if(pic_info.detect_bgcolor) {
                    pic_info.bgcolor = bgcolor;
                }
            }

            if(pic_info.bmp_info != null) {
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
                pic_info.setStatus(PictureStatus.NOT_AVAILABLE);
            }

            pic_info.loading_cnt -= 1;

            // request to start redraw
            drawer_handler.sendEmptyMessage(MSG_DRAW);
        }
    }

    private BitmapInfo loadBitmap(Uri uri, int orientation,
                                  float clip_ratio, float saturation,
                                  int max_width, int max_height)
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
                target_width = max_width;
                target_height = max_height;
            }
            else {
                target_width = max_height;
                target_height = max_width;
            }

            // ask size of picture
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

            int xr = opt.outWidth / target_width;
            int yr = opt.outHeight / target_height;
            int ratio = Math.max(Math.min(xr, yr), 1);

            while((opt.outWidth / ratio) *
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

            float cw = ((bw * bscale) - target_width) / bscale;
            float ch = ((bh * bscale) - target_height) / bscale;
            int src_x = (int)(cw < 0 ? 0 : cw / 2);
            int src_y = (int)(ch < 0 ? 0 : ch / 2);
            int src_w = bw - (int)(cw < 0 ? 0 : cw);
            int src_h = bh - (int)(ch < 0 ? 0 : ch);

            BitmapInfo bmp_info = new BitmapInfo();
            float xratio = (cw < 0 ? bw * bscale / target_width : 1);
            float yratio = (ch < 0 ? bh * bscale / target_height : 1);
            if(orientation != 90 && orientation != 270) {
                bmp_info.xratio = xratio;
                bmp_info.yratio = yratio;
            }
            else {
                bmp_info.xratio = yratio;
                bmp_info.yratio = xratio;
            }

            if(bscale < 1 || orientation != 0) {
                // (down scale or rotate) and clip
                Matrix mat = new Matrix();
                if(bscale < 1) {
                    mat.setScale(bscale, bscale);
                }
                if(orientation != 0) {
                    mat.preRotate(orientation, bw / 2f, bh / 2f);
                }

                bmp_info.bmp = createBitmap(
                    bmp, src_x, src_y, src_w, src_h, mat, saturation);
                bmp.recycle();
            }
            else {
                // clip only
                bmp_info.bmp = createBitmap(
                    bmp, src_x, src_y, src_w, src_h, null, saturation);
                // do not recycle() for 'bmp'
            }

            return bmp_info;
        }
        catch(Exception e) {
            return null;
        }
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
                                int x, int y, int width, int height, Matrix m,
                                float saturation)
    {
        Canvas canvas = new Canvas();
        Bitmap bmp;
        boolean has_alpha =
            (src.hasAlpha() || (m != null && ! m.rectStaysRect()));
        Bitmap.Config format = getBitmapFormat(width, height, has_alpha);
        Paint paint = new Paint();

        Rect src_rect = new Rect(x, y, x + width, y + height);
        RectF dst_rect = new RectF(0, 0, width, height);

        if(m == null || m.isIdentity()) {
            // no scale
            bmp = Bitmap.createBitmap(width, height, format);
        }
        else {
            // with scale
            RectF device_rect = new RectF();
            m.mapRect(device_rect, dst_rect);

            width = Math.round(device_rect.width());
            height = Math.round(device_rect.height());
            format = getBitmapFormat(width, height, has_alpha);

            bmp = Bitmap.createBitmap(width, height, format);
            if(has_alpha) {
                bmp.eraseColor(0);
            }

            canvas.translate(-device_rect.left, -device_rect.top);
            canvas.concat(m);

            paint.setFilterBitmap(true);
            if(! m.rectStaysRect()) {
                paint.setAntiAlias(true);
            }
        }

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
        boolean allow_8888 = (width * height * 2 <= max_screen_pixels);
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
