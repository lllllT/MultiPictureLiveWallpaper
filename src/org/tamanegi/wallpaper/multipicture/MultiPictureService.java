package org.tamanegi.wallpaper.multipicture;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.service.wallpaper.WallpaperService;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

public class MultiPictureService extends WallpaperService
{
    private static final int RELOAD_STEP = 10;
    private static final int RELOAD_DURATION = 500; // msec

    private static final int TRANSITION_RANDOM_TIMEOUT = 500; // msec

    private static final String ACTION_CHANGE_PICTURE =
        "org.tamanegi.wallpaper.multipicture.CHANGE_PICTURE";

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
        file, folder, use_default
    }

    private static class PictureInfo
    {
        private ScreenType type;
        private Bitmap bmp;

        private ArrayList<String> file_list;
        private int cur_file_idx;
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

        private boolean is_duration_pending = false;

        private int reload_progress = -1;       // [-1...RELOAD_STEP]
        private int rotate_progress = -1;       // [-1...RELOAD_STEP]
        private int fadein_progress = 0;        // [ 0...RELOAD_STEP]
        private boolean clear_setting_required = false;
        private long transition_prev_time = 0;
        private boolean is_in_transition = false;
        private TransitionType cur_transition;

        private PictureInfo pic[];
        private ScreenType default_type;
        private TransitionType screen_transition;
        private boolean use_recursive;
        private boolean change_tap;
        private int change_duration;
        private boolean enable_workaround_htcsense;
        private boolean reload_extmedia_mounted;

        private Paint paint;
        private Paint text_paint;

        private Runnable draw_picture_callback;
        private Runnable fadein_callback;

        private BroadcastReceiver receiver;

        private SharedPreferences pref;
        private GestureDetector gdetector;
        private ContentResolver resolver;

        private Handler handler;

        private MultiPictureEngine()
        {
            paint = new Paint();
            paint.setFilterBitmap(true);
            paint.setColor(0xff000000);

            text_paint = new Paint();
            text_paint.setColor(0xffffffff);
            text_paint.setTextAlign(Paint.Align.CENTER);
            text_paint.setTextSize(text_paint.getTextSize() * 1.5f);

            resolver = getContentResolver();

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

            // init conf
            clearPictureSetting();
            loadGlobalSetting();

            // for double tap
            setTouchEventsEnabled(true);

            // prepare to receive broadcast
            IntentFilter filter;
            receiver = new Receiver();

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
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
                else if(Intent.ACTION_MEDIA_MOUNTED.equals(
                            intent.getAction())) {
                    if(reload_extmedia_mounted) {
                        // reload by media mounted
                        clearPictureSetting();
                        drawMain();
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
                    int duration_msec = change_duration * 1000 * 60;
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

                if(reload_progress >= 0 || rotate_progress >= 0) {
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
            c.drawColor(0xff000000);

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
                    (int)(Math.random() * random_transition.length)];
            }

            if(dx != 0 || dy != 0) {
                is_in_transition = true;
            }
            else {
                is_in_transition = false;
                transition_prev_time = SystemClock.elapsedRealtime();
            }

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
            }
            else if(cur_transition == TransitionType.zoom_inout) {
                float fact = (1 - Math.abs(dx)) * (1 - Math.abs(dy));
                matrix.postScale(fact, fact, width / 2f, height / 2f);
                matrix.postTranslate(width * dx / 2, width * dy / 2);
                alpha = 255;
            }
            else if(cur_transition == TransitionType.card) {
                int sx = (dx < 0 ? 0 : (int)(width * dx));
                int sy = (dy < 0 ? 0 : (int)(height * dy));
                matrix.postTranslate(sx, sy);
                alpha = 255;
            }
            else if(cur_transition == TransitionType.slide_3d) {
                Camera camera = new Camera();
                final float ratio = 0.8f;
                camera.translate(dx * width * ratio, dy * height * ratio,
                                 (dx + dy) * -1000);
                camera.getMatrix(matrix);

                final float center = 0.3f;
                matrix.preTranslate(-width * center, -height * center);
                matrix.postTranslate(width * center, height * center);
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
                camera.rotateX(dy * 180);
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

                // real picture
                paint.setAlpha(alpha);
                matrix.preScale((float)width / bmp.getWidth(),
                                (float)height / bmp.getHeight());
                c.drawBitmap(bmp, matrix, paint);

                // mirrored picture
                paint.setAlpha(alpha / 4);
                matrix.preScale(1, -1, 0, bmp.getHeight());
                c.drawBitmap(bmp, matrix, paint);
            }
        }

        private void loadGlobalSetting()
        {
            // screen type
            default_type = ScreenType.valueOf(
                pref.getString("screen.default.type", "file"));

            // draw setting
            screen_transition = TransitionType.valueOf(
                pref.getString("draw.transition", "slide"));
            cur_transition = screen_transition;

            // folder setting
            use_recursive = pref.getBoolean("folder.recursive", true);
            change_tap = pref.getBoolean("folder.changetap", true);
            change_duration = Integer.parseInt(
                pref.getString("folder.duration", "60"));

            // workaround
            enable_workaround_htcsense = pref.getBoolean(
                "workaround.htcsense", true);

            // external media
            reload_extmedia_mounted = pref.getBoolean(
                "reload.extmedia.mounted", true);
        }

        private void clearPictureSetting()
        {
            if(reload_progress >= 0 || rotate_progress >= 0) {
                clear_setting_required = true;
                return;
            }
            clear_setting_required = false;

            if(pic == null) {
                return;
            }

            for(PictureInfo info : pic) {
                if(info == null) {
                    continue;
                }

                if(info.bmp != null) {
                    info.bmp.recycle();
                }
            }

            pic = null;
        }

        private abstract class AsyncTransitionDraw
            extends AsyncTask<Void, Void, Void>
        {
            private Bitmap cur_screen = null;

            protected void saveCurrentScreen()
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

            protected void drawCurrentScreen(int progress)
            {
                SurfaceHolder holder = getSurfaceHolder();
                Canvas c = null;
                try {
                    c = holder.lockCanvas();
                    if(c != null) {
                        c.drawColor(0xff000000);
                        if(cur_screen != null) {
                            paint.setAlpha(0xff);
                            c.drawBitmap(cur_screen, null,
                                         new Rect(0, 0, width, height), paint);
                            c.drawColor((0xff * (RELOAD_STEP - progress) /
                                         RELOAD_STEP) << 24);
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

        private class AsyncLoadPictureSetting extends AsyncTransitionDraw
        {
            @Override
            protected void onPreExecute()
            {
                if(reload_progress >= 0 || rotate_progress >= 0) {
                    cancel(false);
                    return;
                }

                reload_progress = 0;
                rotate_progress = -1;
                fadein_progress = 0;

                handler.removeCallbacks(fadein_callback);
                drawCurrentScreen(0);
            }

            @Override
            protected Void doInBackground(Void... args)
            {
                loadPictureSetting();
                return null;
            }

            @Override
            protected void onPostExecute(Void result)
            {
                startRedraw();
            }

            private void startRedraw()
            {
                reload_progress = -1;

                if(clear_setting_required) {
                    clearPictureSetting();
                }

                startFadeInDraw();
            }
        }

        private class AsyncRotateFolderBitmap
            extends AsyncTransitionDraw
            implements Runnable
        {
            private boolean is_complete = false;

            @Override
            protected void onPreExecute()
            {
                if(reload_progress >= 0 || rotate_progress >= 0) {
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
                rotate_progress -= 1;
                if(rotate_progress > 0) {
                    handler.postDelayed(this, RELOAD_DURATION / RELOAD_STEP);
                }

                drawCurrentScreen(rotate_progress);

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
            }

            PictureInfo info = new PictureInfo();
            info.type = type;

            if(type == ScreenType.file) {
                loadBitmap(info, fname);
            }
            else if(type == ScreenType.folder) {
                info.file_list = listFolderFile(new File(folder));
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

                int target_width = width;
                int target_height = height;

                Uri file = Uri.parse(file_uri);
                InputStream instream;
                BitmapFactory.Options opt;

                // ask size of picture
                opt = new BitmapFactory.Options();
                opt.inJustDecodeBounds = true;

                instream = resolver.openInputStream(file);
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
                int ratio = Math.min(xr, yr);

                // read picture
                opt = new BitmapFactory.Options();
                opt.inDither = true;
                opt.inSampleSize = ratio;

                Bitmap bmp;
                instream = resolver.openInputStream(file);
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
                float bscale = Math.max(bxs, bys);

                int src_x, src_y, src_w, src_h;
                if(bxs > bys) {
                    int ch = (int)(((bh * bscale) - target_height) / bscale);
                    src_x = 0;
                    src_y = ch / 2;
                    src_w = bw;
                    src_h = bh - ch;
                }
                else {
                    int cw = (int)(((bw * bscale) - target_width) / bscale);
                    src_x = cw / 2;
                    src_y = 0;
                    src_w = bw - cw;
                    src_h = bh;
                }

                if(bscale < 1) {
                    Matrix mat = new Matrix();
                    mat.setScale(bscale, bscale);

                    info.bmp = Bitmap.createBitmap(
                        bmp, src_x, src_y, src_w, src_h, mat, true);
                    bmp.recycle();
                }
                else {
                    info.bmp = Bitmap.createBitmap(
                        bmp, src_x, src_y, src_w, src_h);
                    // do not recycle() for 'bmp'
                }

                return true;
            }
            catch(IOException e) {
                return false;
            }
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

        private ArrayList<String> listFolderFile(File folder)
        {
            ArrayList<String> list = new ArrayList<String>();

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
                    String file_uri =
                        ContentResolver.SCHEME_FILE + "://" + file.getPath();
                    if(isPictureFile(file_uri)) {
                        list.add(file_uri);
                    }
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
                if(info.type == ScreenType.folder) {
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
            if(info != null && info.type == ScreenType.folder) {
                int fcnt = info.file_list.size();
                int idx_base = (int)(Math.random() * fcnt);
                for(int i = 0; i < fcnt; i++) {
                    int idx = (idx_base + i) % fcnt;
                    if(idx == info.cur_file_idx) {
                        continue;
                    }

                    Bitmap prev_bmp = info.bmp;
                    if(loadBitmap(info, info.file_list.get(idx))) {
                        info.cur_file_idx = idx;

                        if(prev_bmp != null) {
                            prev_bmp.recycle();
                        }

                        break;
                    }
                }
            }
        }
    }
}
