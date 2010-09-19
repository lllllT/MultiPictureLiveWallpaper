package org.tamanegi.wallpaper.multipicture;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.service.wallpaper.WallpaperService;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

public class MultiPictureService extends WallpaperService
{
    private static final int LOADING_INITIAL_TIME = 500;   // msec
    private static final int LOADING_FRAME_DURATION = 100; // msec
    private static final int LOADING_FRAME_TICK = 12;

    private static final int RELOAD_STEP = 10;
    private static final int RELOAD_DURATION = 500; // msec

    private static final int CLEAR_DURATION = 5000; // msec

    private static final int TRANSITION_RANDOM_TIMEOUT = 500; // msec

    private static final int MAX_TOTAL_PIXELS = 4 * 1024 * 1024; // 4MPixels
    private static final int MAX_DETECT_PIXELS = 8 * 1024; // 8kPixels

    private static final String ACTION_CHANGE_PICTURE =
        "org.tamanegi.wallpaper.multipicture.CHANGE_PICTURE";

    private static final Uri IMAGE_LIST_URI =
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    private static final String[] IMAGE_LIST_COLUMNS = {
        MediaStore.Images.ImageColumns._ID,
        MediaStore.Images.ImageColumns.BUCKET_ID,
        MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
        MediaStore.Images.ImageColumns.DISPLAY_NAME,
        MediaStore.Images.ImageColumns.DATE_TAKEN,
    };
    private static final int IMAGE_LIST_COL_ID = 0;
    private static final int IMAGE_LIST_COL_BUCKET_NAME = 2;
    private static final int IMAGE_LIST_COL_DISPLAY_NAME = 3;
    private static final int IMAGE_LIST_COL_DATE = 4;
    private static final String IMAGE_LIST_WHERE =
        MediaStore.Images.ImageColumns.BUCKET_ID + " = ?";

    private static enum TransitionType
    {
        none, random,
            slide, crossfade, fade_inout, zoom_inout, card,
            slide_3d, rotation_3d,
    }

    private static TransitionType[] random_transition = {
        TransitionType.slide,
        TransitionType.crossfade,
        TransitionType.fade_inout,
        TransitionType.zoom_inout,
        TransitionType.card,
        TransitionType.slide_3d,
        TransitionType.rotation_3d,
    };

    private static enum ScreenType
    {
        file, folder, buckets, use_default
    }

    private static enum OrderType
    {
        name_asc, name_desc, date_asc, date_desc, random, shuffle
    }

    private static class PictureInfo
    {
        private ScreenType type;
        private Bitmap bmp;
        private float xratio;
        private float yratio;

        private boolean detect_bgcolor;
        private int bgcolor;

        private ArrayList<String> file_list;
        private int cur_file_idx;
    }

    private static class FileInfo
    {
        private String uri;
        private String comp_name;
        private long date;

        private static Comparator<FileInfo> getComparator(OrderType type)
        {
            if(type == OrderType.name_asc) {
                return new FileInfoNameComparator();
            }
            if(type == OrderType.name_desc) {
                return Collections.reverseOrder(new FileInfoNameComparator());
            }
            if(type == OrderType.date_asc) {
                return new FileInfoDateComparator();
            }
            if(type == OrderType.date_desc) {
                return Collections.reverseOrder(new FileInfoDateComparator());
            }
            return null;
        }
    }

    private static class FileInfoNameComparator implements Comparator<FileInfo>
    {
        @Override
        public int compare(FileInfo v1, FileInfo v2)
        {
            return v1.comp_name.compareTo(v2.comp_name);
        }
    }

    private static class FileInfoDateComparator implements Comparator<FileInfo>
    {
        @Override
        public int compare(FileInfo v1, FileInfo v2)
        {
            return (v1.date < v2.date ? -1 :
                    v1.date > v2.date ?  1 :
                    0);
        }
    }

    @Override
    public Engine onCreateEngine()
    {
        return new MultiPictureEngine();
    }

    private class MultiPictureEngine extends Engine
        implements SharedPreferences.OnSharedPreferenceChangeListener
    {
        private int width = 1;
        private int height = 1;

        private int xcnt = 1;
        private int ycnt = 1;
        private float xcur = 0f;
        private float ycur = 0f;

        private int max_work_pixels;
        private int max_width;
        private int max_height;

        private boolean is_duration_pending = false;

        private boolean is_reloading = false;
        private int rotate_progress = -1;       // [-1...RELOAD_STEP]
        private int fadein_progress = 0;        // [ 0...RELOAD_STEP]
        private boolean clear_setting_required = false;
        private long transition_prev_time = 0;
        private boolean is_in_transition = false;
        private TransitionType cur_transition;
        private int cur_color;

        private PictureInfo pic[];
        private ScreenType default_type;
        private TransitionType screen_transition;
        private float clip_ratio;
        private boolean detect_bgcolor;
        private int default_bgcolor;
        private boolean show_reflection;
        private boolean use_recursive;
        private boolean change_tap;
        private OrderType change_order;
        private int change_duration;
        private boolean enable_workaround_htcsense;
        private boolean reload_extmedia_mounted;

        private Paint paint;
        private Paint text_paint;

        private Runnable draw_picture_callback;
        private Runnable fadein_callback;
        private Runnable clear_redraw_callback;

        private BroadcastReceiver receiver;

        private SharedPreferences pref;
        private GestureDetector gdetector;
        private ContentResolver resolver;
        private Random random;

        private Handler handler;

        private ArrayList<PictureFolderObserver> folder_observers;
        private ArrayList<PictureContentObserver> bucket_observers;

        private MultiPictureEngine()
        {
            paint = new Paint();
            paint.setFilterBitmap(true);
            paint.setDither(true);
            paint.setColor(0xff000000);

            text_paint = new Paint();
            text_paint.setAntiAlias(true);
            text_paint.setColor(0xffffffff);
            text_paint.setTextAlign(Paint.Align.CENTER);
            text_paint.setTextSize(text_paint.getTextSize() * 1.5f);

            resolver = getContentResolver();

            random = new Random();

            // for double tap
            gdetector = new GestureDetector(
                MultiPictureService.this, new GestureListener());

            // prefs
            pref = PreferenceManager.getDefaultSharedPreferences(
                MultiPictureService.this);
            pref.registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onCreate(SurfaceHolder sh)
        {
            super.onCreate(sh);

            handler = new Handler();
            draw_picture_callback = new DrawPictureCallback();
            fadein_callback = new FadeInCallback();
            clear_redraw_callback = new ClearAndReloadCallback();

            folder_observers = new ArrayList<PictureFolderObserver>();
            bucket_observers = new ArrayList<PictureContentObserver>();

            // init conf
            clearPictureSetting();
            loadGlobalSetting();

            // for double tap
            setTouchEventsEnabled(true);

            // prepare to receive broadcast
            IntentFilter filter;
            receiver = new Receiver();

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
            filter.addDataScheme(ContentResolver.SCHEME_FILE);
            registerReceiver(receiver, filter);

            filter = new IntentFilter();
            filter.addAction(ACTION_CHANGE_PICTURE);
            registerReceiver(receiver, filter);
        }

        @Override
        public void onDestroy()
        {
            super.onDestroy();
            unregisterReceiver(receiver);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences pref,
                                              String key)
        {
            clearPictureSetting();
            loadGlobalSetting();
        }

        @Override
        public void onOffsetsChanged(float xoffset, float yoffset,
                                     float xstep, float ystep,
                                     int xpixel, int ypixel)
        {
            if(enable_workaround_htcsense) {
                // workaround for f*cking HTC Sense home app
                if(xstep < 0) {
                    xstep = 1.0f / 6.0f;
                    xoffset = (xoffset - 0.125f) * (1.0f / 0.75f);
                }
            }

            // num of screens
            int xn = (xstep <= 0 ? 1 : (int)(1 / xstep) + 1);
            int yn = (ystep <= 0 ? 1 : (int)(1 / ystep) + 1);
            if(xn != xcnt || yn != ycnt) {
                xcnt = xn;
                ycnt = yn;
                clearPictureSetting();
            }

            // current screen position
            xcur = (xstep <= 0 ? 0 : xoffset / xstep);
            ycur = (ystep <= 0 ? 0 : yoffset / ystep);

            drawMain();
        }

        @Override
        public void onVisibilityChanged(boolean visible)
        {
            if(is_duration_pending) {
                postDurationCallback();
            }

            drawMain();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder sh, int format,
                                     int width, int height)
        {
            super.onSurfaceChanged(sh, format, width, height);

            if(this.width != width || this.height != height) {
                this.width = width;
                this.height = height;
                clearPictureSetting();
            }

            drawMain();
        }

        @Override
        public void onTouchEvent(MotionEvent ev)
        {
            gdetector.onTouchEvent(ev);
        }

        private class GestureListener
            extends GestureDetector.SimpleOnGestureListener
        {
            @Override
            public boolean onDoubleTap(MotionEvent ev)
            {
                if(change_tap) {
                    new AsyncRotateFolderBitmap().execute();
                }

                return true;
            }
        }

        private class Receiver extends BroadcastReceiver
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                if(ACTION_CHANGE_PICTURE.equals(intent.getAction())) {
                    // change by interval
                    new AsyncRotateFolderBitmap().execute();
                }
                else if(Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(
                            intent.getAction())) {
                    if(reload_extmedia_mounted) {
                        // reload by media scanner finished
                        postDelayedClearAndReload();
                    }
                }
            }
        }

        private void postDurationCallback()
        {
            AlarmManager mgr = (AlarmManager)getSystemService(ALARM_SERVICE);

            Intent intent = new Intent(ACTION_CHANGE_PICTURE);
            PendingIntent alarm_intent =
                PendingIntent.getBroadcast(
                    MultiPictureService.this, 0, intent, 0);

            mgr.cancel(alarm_intent);

            if(change_duration > 0) {
                if(isVisible()) {
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

        private class FadeInCallback implements Runnable
        {
            @Override
            public void run()
            {
                if(! isVisible()) {
                    fadein_progress = 0;
                    return;
                }

                fadein_progress -= 1;
                if(fadein_progress > 0) {
                    handler.postDelayed(fadein_callback,
                                        RELOAD_DURATION / RELOAD_STEP);
                }

                drawMain();
            }
        }

        private void startFadeInDraw()
        {
            if(! isVisible()) {
                fadein_progress = 0;
                return;
            }

            fadein_progress = RELOAD_STEP;
            handler.postDelayed(fadein_callback, RELOAD_DURATION / RELOAD_STEP);

            drawMain();
        }

        private void drawMain()
        {
            handler.removeCallbacks(draw_picture_callback);
            handler.post(draw_picture_callback);
        }

        private class DrawPictureCallback implements Runnable
        {
            @Override
            public void run()
            {
                if(! isVisible()) {
                    return;
                }

                if(is_reloading || rotate_progress >= 0) {
                    return;
                }

                if(pic == null) {
                    new AsyncLoadPictureSetting().execute();
                    return;
                }

                SurfaceHolder holder = getSurfaceHolder();
                Canvas c = null;
                try {
                    c = holder.lockCanvas();
                    if(c != null) {
                        drawPicture(c);

                        if(fadein_progress > 0) {
                            c.drawColor(
                                (0xff * fadein_progress / RELOAD_STEP) << 24);
                        }
                    }
                }
                finally {
                    if(c != null) {
                        holder.unlockCanvasAndPost(c);
                    }
                }
            }
        }

        private void drawPicture(Canvas c)
        {
            // delta
            int xn = (int)Math.floor(xcur);
            int yn = (int)Math.floor(ycur);
            float dx = xn - xcur;
            float dy = yn - ycur;

            // for random transition
            if(((! is_in_transition) &&
                (screen_transition == TransitionType.random) &&
                (dx != 0 || dy != 0) &&
                (transition_prev_time + TRANSITION_RANDOM_TIMEOUT <
                 SystemClock.elapsedRealtime())) ||
               (cur_transition == TransitionType.random)) {
                cur_transition = random_transition[
                    random.nextInt(random_transition.length)];
            }

            if(dx != 0 || dy != 0) {
                is_in_transition = true;
            }
            else {
                is_in_transition = false;
                transition_prev_time = SystemClock.elapsedRealtime();
            }

            // background color
            int color = getBackgroundColor(xn, yn, dx, dy);
            if(dx != 0) {
                int cc = getBackgroundColor(xn + 1, yn, dx + 1, dy);
                color = mergeColor(color, cc);
            }
            if(dy != 0) {
                int cc = getBackgroundColor(xn, yn + 1, dx, dy + 1);
                color = mergeColor(color, cc);
            }
            if(dx != 0 && dy != 0) {
                int cc = getBackgroundColor(xn + 1, yn + 1, dx + 1, dy + 1);
                color = mergeColor(color, cc);
            }

            cur_color = ((color & 0x00ffffff) | 0xff000000);
            paint.setColor(cur_color);
            c.drawColor(cur_color);

            // draw each screen
            drawPicture(c, xn, yn, dx, dy);
            if(dx != 0) {
                drawPicture(c, xn + 1, yn, dx + 1, dy);
            }
            if(dy != 0) {
                drawPicture(c, xn, yn + 1, dx, dy + 1);
            }
            if(dx != 0 && dy != 0) {
                drawPicture(c, xn + 1, yn + 1, dx + 1, dy + 1);
            }
        }

        private void drawPicture(Canvas c, int xn, int yn, float dx, float dy)
        {
            int idx = xcnt * yn + xn;
            if(idx < 0 || idx >= pic.length) {
                return;
            }

            Matrix matrix = new Matrix();
            int alpha = 255;
            boolean fill_background = false;

            if(cur_transition == TransitionType.none) {
                if(dx <= -0.5 || dx > 0.5 ||
                   dy <= -0.5 || dy > 0.5) {
                    return;
                }
            }
            else if(cur_transition == TransitionType.crossfade) {
                alpha = (int)((1 - Math.abs(dx)) * (1 - Math.abs(dy)) * 255);
            }
            else if(cur_transition == TransitionType.fade_inout) {
                if(dx <= -0.5 || dx > 0.5 ||
                   dy <= -0.5 || dy > 0.5) {
                    return;
                }
                alpha = (int)(
                    (1 - Math.max(Math.abs(dx), Math.abs(dy)) * 2) * 255);
            }
            else if(cur_transition == TransitionType.slide) {
                matrix.postTranslate(width * dx, height * dy);
                alpha = 255;
                fill_background = true;
            }
            else if(cur_transition == TransitionType.zoom_inout) {
                float fact = Math.min(1 - Math.abs(dx), 1 - Math.abs(dy));
                matrix.postScale(fact, fact, width / 2f, height / 2f);
                matrix.postTranslate(width * dx / 2, height * dy / 2);
                alpha = 255;
            }
            else if(cur_transition == TransitionType.card) {
                int sx = (dx < 0 ? 0 : (int)(width * dx));
                int sy = (dy < 0 ? 0 : (int)(height * dy));
                matrix.postTranslate(sx, sy);
                alpha = 255;
                fill_background = true;
            }
            else if(cur_transition == TransitionType.slide_3d) {
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

                alpha = Math.min((int)((Math.min(dx, dy) + 1) * 0xff), 0xff);
            }
            else if(cur_transition == TransitionType.rotation_3d) {
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
            }

            if(pic[idx] == null || pic[idx].bmp == null) {
                text_paint.setAlpha(alpha);
                paint.setAlpha(alpha);
                c.save();
                c.concat(matrix);
                c.drawRect(0, 0, width, height, paint);
                c.drawText(getString(R.string.str_pic_not_set, idx + 1),
                           width / 2, height / 2, text_paint);
                c.restore();
            }
            else {
                Bitmap bmp = pic[idx].bmp;
                float xratio = pic[idx].xratio;
                float yratio = pic[idx].yratio;

                // real picture
                matrix.preTranslate(width * (1 - xratio) / 2,
                                    height * (1 - yratio) / 2);
                matrix.preScale(width * xratio / bmp.getWidth(),
                                height * yratio / bmp.getHeight());

                if(fill_background) {
                    paint.setColor(pic[idx].bgcolor);
                    paint.setAlpha(alpha);
                    c.save();
                    c.concat(matrix);
                    c.drawRect(-width * (1 - xratio) / 2,
                               -height * (1 - yratio) / 2,
                               width, height, paint);
                    c.restore();
                }

                paint.setColor(cur_color);
                paint.setAlpha(alpha);
                c.drawBitmap(bmp, matrix, paint);

                if(show_reflection) {
                    // mirrored picture
                    if(fill_background) {
                        paint.setColor(pic[idx].bgcolor);
                        paint.setAlpha(alpha);
                    }
                    matrix.preScale(1, -1, 0, bmp.getHeight());
                    c.save();
                    c.concat(matrix);
                    c.drawRect(0, 0, bmp.getWidth(), bmp.getHeight(), paint);
                    c.restore();
                    paint.setAlpha(alpha / 4);
                    c.drawBitmap(bmp, matrix, paint);
                }
            }
        }

        private int getBackgroundColor(int xn, int yn, float dx, float dy)
        {
            int idx = xcnt * yn + xn;
            if(idx < 0 || idx >= pic.length) {
                return 0;
            }

            if(pic[idx].bmp == null) {
                return 0;
            }

            int color = pic[idx].bgcolor;
            float a = (1 - Math.abs(dx)) * (1 - Math.abs(dy));
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

            return (((int)((a1 + a2) * 0xff) << 24) |
                    ((int)(r1 * a1 + r2 * a2) << 16) |
                    ((int)(g1 * a1 + g2 * a2) <<  8) |
                    ((int)(b1 * a1 + b2 * a2) <<  0));
        }

        private void loadGlobalSetting()
        {
            // screen type
            default_type = ScreenType.valueOf(
                pref.getString("screen.default.type", "file"));

            String bgcolor = pref.getString("screen.default.bgcolor", "black");
            if("auto_detect".equals(bgcolor)) {
                detect_bgcolor = true;
            }
            else {
                detect_bgcolor = false;
                default_bgcolor = Color.parseColor(bgcolor);
            }

            // draw setting
            screen_transition = TransitionType.valueOf(
                pref.getString("draw.transition", "slide"));
            cur_transition = screen_transition;
            clip_ratio = Float.valueOf(pref.getString("draw.clipratio", "1.0"));
            show_reflection = pref.getBoolean("draw.reflection", true);

            // folder setting
            use_recursive = pref.getBoolean("folder.recursive", true);
            change_tap = pref.getBoolean("folder.changetap", true);
            {
                boolean change_random = pref.getBoolean("folder.random", true);
                String order_val = pref.getString("folder.order", null);
                if(order_val == null) {
                    order_val = (change_random ? "random" : "name_asc");
                }
                change_order = OrderType.valueOf(order_val);
            }
            {
                String min_str = pref.getString("folder.duration", null);
                String sec_str = pref.getString("folder.duration_sec", null);
                change_duration =
                    (sec_str != null ? Integer.parseInt(sec_str) :
                     min_str != null ? Integer.parseInt(min_str) * 60 :
                     60 * 60);
            }

            // workaround
            enable_workaround_htcsense = pref.getBoolean(
                "workaround.htcsense", true);

            // external media
            reload_extmedia_mounted = pref.getBoolean(
                "reload.extmedia.mounted", true);
        }

        private void postDelayedClearAndReload()
        {
            handler.removeCallbacks(clear_redraw_callback);
            handler.postDelayed(clear_redraw_callback, CLEAR_DURATION);
        }

        private class ClearAndReloadCallback implements Runnable
        {
            @Override
            public void run()
            {
                clearPictureSetting();
                drawMain();
            }
        }

        private void clearPictureSetting()
        {
            // check loading
            if(is_reloading || rotate_progress >= 0) {
                clear_setting_required = true;
                return;
            }
            clear_setting_required = false;

            // is already cleared
            if(pic == null) {
                return;
            }

            // clear for each picture info
            for(PictureInfo info : pic) {
                if(info == null) {
                    continue;
                }

                if(info.bmp != null) {
                    info.bmp.recycle();
                }
            }

            // clear picture info
            pic = null;

            // clear event listeners
            for(PictureFolderObserver observer : folder_observers) {
                observer.stopWatching();
            }
            folder_observers.clear();

            for(ContentObserver observer : bucket_observers) {
                resolver.unregisterContentObserver(observer);
            }
            bucket_observers.clear();
        }

        private abstract class AsyncProgressDraw
            extends AsyncTask<Void, Void, Void>
        {
            private Bitmap spinner = null;

            protected void drawProgress()
            {
                SurfaceHolder holder = getSurfaceHolder();
                Canvas c = null;
                try {
                    c = holder.lockCanvas();
                    if(c != null) {
                        draw(c);
                    }
                }
                finally {
                    if(c != null) {
                        holder.unlockCanvasAndPost(c);
                    }
                }
            }

            protected void drawSpinner(Canvas c, int progress)
            {
                if(spinner == null) {
                    spinner = BitmapFactory.decodeResource(
                        getResources(), R.drawable.spinner);
                }

                c.drawColor(0xff000000);
                c.save();
                c.rotate(360f / LOADING_FRAME_TICK * progress,
                         width / 2f, height / 2f);
                c.drawBitmap(spinner,
                             (width - spinner.getWidth()) / 2f,
                             (height - spinner.getHeight()) / 2f,
                             null);
                c.restore();
            }

            protected abstract void draw(Canvas c);
        }

        private class AsyncLoadPictureSetting
            extends AsyncProgressDraw
            implements Runnable
        {
            private int cnt;

            @Override
            protected void draw(Canvas c)
            {
                if(cnt != 0) {
                    drawSpinner(c, cnt);
                }
            }

            @Override
            protected void onPreExecute()
            {
                if(is_reloading || rotate_progress >= 0) {
                    cancel(false);
                    return;
                }

                is_reloading = true;
                rotate_progress = -1;
                fadein_progress = 0;

                handler.removeCallbacks(fadein_callback);

                cnt = 0;
                handler.postDelayed(this, LOADING_INITIAL_TIME);
                drawProgress();
            }

            @Override
            protected Void doInBackground(Void... args)
            {
                loadPictureSetting();
                return null;
            }

            @Override
            public void run()
            {
                handler.postDelayed(this, LOADING_FRAME_DURATION);
                drawProgress();
                cnt += 1;
            }

            @Override
            protected void onPostExecute(Void result)
            {
                startRedraw();
            }

            private void startRedraw()
            {
                handler.removeCallbacks(this);
                is_reloading = false;

                if(clear_setting_required) {
                    clearPictureSetting();
                }

                startFadeInDraw();
            }
        }

        private class AsyncRotateFolderBitmap
            extends AsyncProgressDraw
            implements Runnable
        {
            private Bitmap cur_screen = null;
            private int cnt = 0;
            private boolean is_complete = false;

            private void saveCurrentScreen()
            {
                if(pic == null) {
                    cur_screen = null;
                    return;
                }

                cur_screen = Bitmap.createBitmap(
                    width, height, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(cur_screen);
                drawPicture(c);
            }

            @Override
            protected void draw(Canvas c)
            {
                if(rotate_progress > 0) {
                    if(cur_screen != null) {
                        paint.setAlpha(0xff);
                        c.drawBitmap(cur_screen, null,
                                     new Rect(0, 0, width, height), paint);
                        c.drawColor((0xff * (RELOAD_STEP - rotate_progress) /
                                     RELOAD_STEP) << 24);
                    }
                }
                else if(cnt != 0) {
                    drawSpinner(c, cnt);
                }
                else {
                    c.drawColor(0xff000000);
                }
            }

            @Override
            protected void onPreExecute()
            {
                if(is_reloading || rotate_progress >= 0) {
                    cancel(false);
                    return;
                }

                if(! isNeedRotateFolderBitmap()) {
                    cancel(false);
                    return;
                }

                rotate_progress =
                    (isVisible() ? RELOAD_STEP - fadein_progress : 0);
                fadein_progress = 0;

                if(rotate_progress > 0) {
                    saveCurrentScreen();

                    handler.removeCallbacks(fadein_callback);
                    handler.postDelayed(this, RELOAD_DURATION / RELOAD_STEP);
                }
                else {
                    handler.postDelayed(this, LOADING_INITIAL_TIME);
                }
            }

            @Override
            protected Void doInBackground(Void... args)
            {
                rotateFolderBitmap();
                return null;
            }

            @Override
            public void run()
            {
                if(rotate_progress > 0) {
                    rotate_progress -= 1;

                    if(rotate_progress > 0) {
                        handler.postDelayed(
                            this, RELOAD_DURATION / RELOAD_STEP);
                    }
                    else {
                        handler.postDelayed(this, LOADING_INITIAL_TIME);
                    }

                    drawProgress();
                }
                else {
                    handler.postDelayed(this, LOADING_FRAME_DURATION);
                    drawProgress();
                    cnt += 1;
                }

                if(rotate_progress <= 0 && is_complete) {
                    startRedraw();
                }
            }

            @Override
            protected void onPostExecute(Void result)
            {
                is_complete = true;

                if(rotate_progress <= 0) {
                    startRedraw();
                }
            }

            private void startRedraw()
            {
                handler.removeCallbacks(this);
                rotate_progress = -1;

                if(clear_setting_required) {
                    clearPictureSetting();
                }

                startFadeInDraw();
            }
        }

        private void loadPictureSetting()
        {
            int cnt = xcnt * ycnt;
            pic = new PictureInfo[cnt];

            // restrict size
            if(width * height * (cnt + 3) > MAX_TOTAL_PIXELS) {
                // mw * mh * (cnt + 3) = MAX_TOTAL_PIXELS
                // mw / mh = width / height
                //  -> mh = mw * height / width
                //  -> mw^2 = MAX_TOTAL_PIXELS / (height * (cnt + 3)) * width
                max_width = (int)
                    Math.sqrt(MAX_TOTAL_PIXELS / (height * (cnt + 3)) * width);
                max_height = max_width * height / width;
                max_work_pixels = MAX_TOTAL_PIXELS / (cnt + 3) * 3;
            }
            else {
                max_width = width;
                max_height = height;
                max_work_pixels = MAX_TOTAL_PIXELS - width * height * cnt;
            }

            // for each screen
            for(int i = 0; i < cnt; i++) {
                pic[i] = loadPictureInfo(i);
            }

            rotateFolderBitmap();
        }

        private PictureInfo loadPictureInfo(int idx)
        {
            String type_str = pref.getString(
                String.format(MultiPictureSetting.SCREEN_TYPE_KEY, idx), null);
            String fname = pref.getString(
                String.format(MultiPictureSetting.SCREEN_FILE_KEY, idx), null);
            String folder = pref.getString(
                String.format(MultiPictureSetting.SCREEN_FOLDER_KEY, idx), "");
            String bucket = pref.getString(
                String.format(MultiPictureSetting.SCREEN_BUCKET_KEY, idx), "");
            String bgcolor = pref.getString(
                String.format(MultiPictureSetting.SCREEN_BGCOLOR_KEY, idx),
                "use_default");

            // type of screen
            ScreenType type =
                ((type_str == null && fname != null) ? ScreenType.file :
                 type_str == null ? ScreenType.use_default :
                 ScreenType.valueOf(type_str));
            if(type == ScreenType.use_default) {
                type = default_type;
                fname = pref.getString(
                    MultiPictureSetting.DEFAULT_FILE_KEY, null);
                folder = pref.getString(
                    MultiPictureSetting.DEFAULT_FOLDER_KEY,
                    Environment.getExternalStorageDirectory().getPath());
                bucket = pref.getString(
                    MultiPictureSetting.DEFAULT_BUCKET_KEY, "");
            }

            // allocate info
            PictureInfo info = new PictureInfo();
            info.type = type;

            // background color
            if("use_default".equals(bgcolor)) {
                info.detect_bgcolor = detect_bgcolor;
                info.bgcolor = default_bgcolor;
            }
            else if("auto_detect".equals(bgcolor)) {
                info.detect_bgcolor = true;
            }
            else {
                info.detect_bgcolor = false;
                info.bgcolor = Color.parseColor(bgcolor);
            }

            // load bitmap data
            if(type == ScreenType.file) {
                if(fname != null) {
                    addContentObserver(Uri.parse(fname), false);
                    loadBitmap(info, fname);
                }
            }
            else if(type == ScreenType.folder ||
                    type == ScreenType.buckets) {
                ArrayList<FileInfo> flist = new ArrayList<FileInfo>();
                if(type == ScreenType.folder) {
                    flist = listFolderFile(new File(folder));
                }
                else if(type == ScreenType.buckets) {
                    flist = listBucketPicture(bucket.split(" "));
                }

                if(change_order == OrderType.shuffle) {
                    Collections.shuffle(flist, random);
                }
                else if(change_order != OrderType.random) {
                    Comparator<FileInfo> comparator =
                        FileInfo.getComparator(change_order);
                    Collections.sort(flist, comparator);
                }

                ArrayList<String> uri_list = new ArrayList<String>();
                for(FileInfo fi : flist) {
                    uri_list.add(fi.uri);
                }

                info.file_list = uri_list;
                info.bmp = null;
                info.cur_file_idx = -1;
            }

            return info;
        }

        private boolean loadBitmap(PictureInfo info, String file_uri)
        {
            try {
                if(file_uri == null) {
                    return false;
                }

                Uri file = Uri.parse(file_uri);
                InputStream instream;
                BitmapFactory.Options opt;

                // orientation
                int orientation = getPictureOrientation(file_uri);
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

                instream = resolver.openInputStream(file);
                if(instream == null) {
                    return false;
                }
                try {
                    BitmapFactory.decodeStream(instream, null, opt);
                    if(opt.outWidth < 0 || opt.outHeight < 0) {
                        return false;
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
                instream = resolver.openInputStream(file);
                if(instream == null) {
                    return false;
                }
                try {
                    bmp = BitmapFactory.decodeStream(instream, null, opt);
                    if(bmp == null) {
                        return false;
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
                float bscale = bmax * clip_ratio + bmin * (1 - clip_ratio);

                float cw = ((bw * bscale) - target_width) / bscale;
                float ch = ((bh * bscale) - target_height) / bscale;
                int src_x = (int)(cw < 0 ? 0 : cw / 2);
                int src_y = (int)(ch < 0 ? 0 : ch / 2);
                int src_w = bw - (int)(cw < 0 ? 0 : cw);
                int src_h = bh - (int)(ch < 0 ? 0 : ch);

                float xratio = (cw < 0 ? bw * bscale / target_width : 1);
                float yratio = (ch < 0 ? bh * bscale / target_height : 1);
                if(orientation != 90 && orientation != 270) {
                    info.xratio = xratio;
                    info.yratio = yratio;
                }
                else {
                    info.xratio = yratio;
                    info.yratio = xratio;
                }

                if(bscale < 1 || orientation != 0) {
                    // (down scale or rotate) and clip
                    Matrix mat = new Matrix();
                    if(bscale < 1) {
                        mat.setScale(bscale, bscale);
                    }
                    if(orientation != 0) {
                        mat.preRotate(orientation, bw / 2, bh / 2);
                    }

                    info.bmp = Bitmap.createBitmap(
                        bmp, src_x, src_y, src_w, src_h, mat, true);
                    bmp.recycle();
                }
                else {
                    // clip only
                    info.bmp = Bitmap.createBitmap(
                        bmp, src_x, src_y, src_w, src_h);
                    // do not recycle() for 'bmp'
                }

                // background color
                if(info.detect_bgcolor) {
                    info.bgcolor = detectBackgroundColor(
                        info.bmp, info.xratio, info.yratio);
                }

                return true;
            }
            catch(IOException e) {
                return false;
            }
        }

        private int getPictureOrientation(String file_uri)
        {
            // get from media store
            Cursor cur = resolver.query(
                Uri.parse(file_uri),
                new String[] { MediaStore.Images.ImageColumns.ORIENTATION },
                null, null, null);
            if(cur != null) {
                try {
                    if(cur.moveToFirst()) {
                        return cur.getInt(0);
                    }
                }
                finally {
                    cur.close();
                }
            }

            // get from exif tag
            URI uri = URI.create(file_uri);
            if(ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                try {
                    ExifInterface exif =
                        new ExifInterface(new File(uri).getPath());
                    int ori = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);

                    if(ori == ExifInterface.ORIENTATION_ROTATE_90) {
                        return 90;
                    }
                    if(ori == ExifInterface.ORIENTATION_ROTATE_180) {
                        return 180;
                    }
                    if(ori == ExifInterface.ORIENTATION_ROTATE_270) {
                        return 270;
                    }
                    return 0;
                }
                catch(IOException e) {
                    // ignore
                }
            }

            return 0;
        }

        private int detectBackgroundColor(Bitmap bmp,
                                          float xratio, float yratio)
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

        private boolean isPictureFile(String file_uri)
        {
            try {
                if(file_uri == null) {
                    return false;
                }

                Uri file = Uri.parse(file_uri);
                BitmapFactory.Options opt;

                // just ask size of picture
                opt = new BitmapFactory.Options();
                opt.inJustDecodeBounds = true;

                InputStream instream = resolver.openInputStream(file);
                if(instream == null) {
                    return false;
                }
                try {
                    BitmapFactory.decodeStream(instream, null, opt);
                    if(opt.outWidth < 0 || opt.outHeight < 0) {
                        return false;
                    }
                }
                finally {
                    instream.close();
                }

                return true;
            }
            catch(IOException e) {
                return false;
            }
        }

        private ArrayList<FileInfo> listFolderFile(File folder)
        {
            // observer
            addFolderObserver(folder.getPath());

            // listup
            ArrayList<FileInfo> list = new ArrayList<FileInfo>();

            File[] files = folder.listFiles();
            if(files == null) {
                return list;
            }

            for(File file : files) {
                if(file.isDirectory()) {
                    if(use_recursive) {
                        list.addAll(listFolderFile(file));
                    }
                }
                else if(file.isFile()) {
                    FileInfo fi = new FileInfo();
                    fi.uri = file.toURI().toString();
                    fi.comp_name = file.getPath();
                    fi.date = file.lastModified();
                    if(isPictureFile(fi.uri)) {
                        list.add(fi);
                    }
                }
            }

            return list;
        }

        private ArrayList<FileInfo> listBucketPicture(String[] bucket)
        {
            // observer
            addContentObserver(IMAGE_LIST_URI, true);

            // listup
            ArrayList<FileInfo> list = new ArrayList<FileInfo>();

            Uri uri = IMAGE_LIST_URI;
            for(String id : bucket) {
                Cursor cur = resolver.query(
                    uri,
                    IMAGE_LIST_COLUMNS,
                    IMAGE_LIST_WHERE,
                    new String[] { id },
                    null);
                if(cur == null) {
                    continue;
                }

                try {
                    if(cur.moveToFirst()) {
                        do {
                            FileInfo fi = new FileInfo();
                            fi.uri =
                                ContentUris.withAppendedId(
                                    uri,
                                    cur.getLong(IMAGE_LIST_COL_ID)).toString();
                            fi.comp_name =
                                cur.getString(IMAGE_LIST_COL_BUCKET_NAME) +
                                "/" +
                                cur.getString(IMAGE_LIST_COL_DISPLAY_NAME);
                            fi.date = cur.getLong(IMAGE_LIST_COL_DATE);
                            list.add(fi);
                        } while(cur.moveToNext());
                    }
                }
                finally {
                    cur.close();
                }
            }

            return list;
        }

        private boolean isNeedRotateFolderBitmap()
        {
            if(pic == null) {
                return false;
            }

            for(PictureInfo info : pic) {
                if(info.type == ScreenType.folder ||
                   info.type == ScreenType.buckets) {
                    return true;
                }
            }

            return false;
        }

        private void rotateFolderBitmap()
        {
            if(pic == null) {
                return;
            }

            for(PictureInfo info : pic) {
                rotateFolderBitmap(info);
            }

            postDurationCallback();
        }

        private void rotateFolderBitmap(PictureInfo info)
        {
            if(info != null &&
               (info.type == ScreenType.folder ||
                info.type == ScreenType.buckets)) {
                int fcnt = info.file_list.size();
                if(fcnt < 1) {
                    return;
                }

                int idx_base = (change_order == OrderType.random ?
                                random.nextInt(fcnt) :
                                (info.cur_file_idx + 1) % fcnt);
                int idx_prev = info.cur_file_idx;
                boolean is_retrying = false;

                for(int i = 0; i < fcnt; i++) {
                    int idx = (idx_base + i) % fcnt;
                    if(idx == idx_prev) {
                        continue;
                    }

                    boolean same_exists = false;
                    if(change_order == OrderType.random) {
                        String name = info.file_list.get(idx);

                        for(PictureInfo other_info : pic) {
                            if(other_info == info) {
                                break;
                            }
                            if(other_info.type != ScreenType.file &&
                               other_info.cur_file_idx >= 0 &&
                               name.equals(other_info.file_list.get(
                                               other_info.cur_file_idx))) {
                                same_exists = true;
                            }
                        }
                        if(is_retrying && same_exists) {
                            continue;
                        }
                    }

                    Bitmap prev_bmp = info.bmp;
                    if(loadBitmap(info, info.file_list.get(idx))) {
                        info.cur_file_idx = idx;

                        if(prev_bmp != null) {
                            prev_bmp.recycle();
                        }

                        if(change_order == OrderType.random &&
                           (! is_retrying) && same_exists) {
                            is_retrying = true;
                            continue;
                        }

                        break;
                    }
                }
            }
        }

        private void addFolderObserver(String path)
        {
            if(! reload_extmedia_mounted) {
                return;
            }

            for(PictureFolderObserver observer : folder_observers) {
                if(observer.getPath().equals(path)) {
                    return;
                }
            }

            PictureFolderObserver observer = new PictureFolderObserver(path);
            observer.startWatching();
            folder_observers.add(observer);
        }

        private class PictureFolderObserver extends FileObserver
        {
            private static final int EVENTS =
                CREATE | DELETE | DELETE_SELF | MODIFY |
                MOVED_FROM | MOVED_TO | MOVE_SELF;

            private String path;

            private PictureFolderObserver(String path)
            {
                super(path, EVENTS);
                this.path = path;
            }

            @Override
            public void onEvent(int event, String path)
            {
                if((event & EVENTS) != 0) {
                    postDelayedClearAndReload();
                }
            }

            public String getPath()
            {
                return path;
            }
        }

        private void addContentObserver(Uri uri, boolean is_bucket)
        {
            if(! reload_extmedia_mounted) {
                return;
            }

            for(PictureContentObserver observer : bucket_observers) {
                if(observer.getUri().equals(uri)) {
                    return;
                }
            }

            PictureContentObserver observer = new PictureContentObserver(uri);
            resolver.registerContentObserver(uri, is_bucket, observer);
            bucket_observers.add(observer);
        }

        private class PictureContentObserver extends ContentObserver
        {
            private Uri uri;

            private PictureContentObserver(Uri uri)
            {
                super(handler);
                this.uri = uri;
            }

            @Override
            public void onChange(boolean selfChange)
            {
                postDelayedClearAndReload();
            }

            public Uri getUri()
            {
                return uri;
            }
        }
    }
}
