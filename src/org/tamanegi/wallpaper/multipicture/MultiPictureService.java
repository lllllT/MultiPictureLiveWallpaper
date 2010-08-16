package org.tamanegi.wallpaper.multipicture;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.service.wallpaper.WallpaperService;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

public class MultiPictureService extends WallpaperService
{

    private static final int LEFT_TOP = 1;
    private static final int RIGHT_TOP = 2;
    private static final int LEFT_BOTTOM = 3;
    private static final int RIGHT_BOTTOM = 4;

    private static final int FOLDER_TRANSITION_STEP = 10;
    private static final int FOLDER_TRANSITION_DURATION = 1000; // msec

    private static enum TransitionType
    {
        none, slide, crossfade, fade_inout, zoom_inout
    }

    private static enum ScreenType
    {
        file, folder, use_default
    }

    private static class PictureInfo
    {
        private ScreenType type;
        private Bitmap bmp;
        private Rect rect;

        private Bitmap prev_bmp;
        private Rect prev_rect;

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
        private int width = 0;
        private int height = 0;

        private int xcnt = 1;
        private int ycnt = 1;
        private float xcur = 0f;
        private float ycur = 0f;

        private boolean is_change_pending = false;
        private int folder_trans_progress = -1;

        private PictureInfo pic[];
        private ScreenType default_type;
        private TransitionType screen_transition;
        private TransitionType folder_transition;
        private boolean use_recursive;
        private boolean change_tap;
        private int change_duration;

        private Paint paint;
        private Paint text_paint;

        private Runnable change_duration_callback;
        private Runnable change_step_callback;

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
            change_duration_callback = new ChangeDurationCallback();
            change_step_callback = new ChangeStepCallback();

            // init conf
            pic = null;                         // load later
            loadGlobalSetting();

            // for double tap
            setTouchEventsEnabled(true);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences pref,
                                              String key)
        {
            pic = null;
            loadGlobalSetting();
        }

        @Override
        public void onOffsetsChanged(float xoffset, float yoffset,
                                     float xstep, float ystep,
                                     int xpixel, int ypixel)
        {
            int xn = (xstep <= 0 ? 1 : (int)(1 / xstep) + 1);
            int yn = (ystep <= 0 ? 1 : (int)(1 / ystep) + 1);
            if(xn != xcnt || yn != ycnt) {
                xcnt = xn;
                ycnt = yn;
                pic = null;
            }

            xcur = (xstep <= 0 ? 0 : xoffset / xstep);
            ycur = (ystep <= 0 ? 0 : yoffset / ystep);

            drawMain();
        }

        @Override
        public void onVisibilityChanged(boolean visible)
        {
            if(is_change_pending) {
                rotateFolderBitmap();
                is_change_pending = false;
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
                pic = null;
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
                    rotateFolderBitmap();
                    drawMain();
                }

                return true;
            }
        }

        private class ChangeDurationCallback implements Runnable
        {
            @Override
            public void run()
            {
                if(isVisible()) {
                    rotateFolderBitmap();
                    drawMain();
                }
                else {
                    is_change_pending = true;
                }
            }
        }

        private void postDurationCallback()
        {
            handler.removeCallbacks(change_duration_callback);

            if(change_duration > 0) {
                handler.postDelayed(change_duration_callback,
                                    change_duration * 1000 * 60);
            }
        }

        private class ChangeStepCallback implements Runnable
        {
            @Override
            public void run()
            {
                if(isVisible()) {
                    folder_trans_progress -= 1;
                    postStepCallback();

                    if(folder_trans_progress < 0) {
                        clearFolderTransition();
                    }

                    drawMain();
                }
                else {
                    clearFolderTransition();
                }
            }
        }

        private void postStepCallback()
        {
            handler.removeCallbacks(change_step_callback);

            if(folder_transition != TransitionType.none &&
               folder_trans_progress >= 0) {
                handler.postDelayed(
                    change_step_callback,
                    FOLDER_TRANSITION_DURATION / FOLDER_TRANSITION_STEP);
            }
        }

        private void clearFolderTransition()
        {
            folder_trans_progress = -1;

            if(pic != null) {
                int cnt = xcnt * ycnt;
                for(int i = 0; i < cnt; i++) {
                    if(pic[i].prev_bmp != null) {
                        pic[i].prev_bmp.recycle();
                        pic[i].prev_bmp = null;
                    }
                    pic[i].prev_rect = null;
                }
            }
        }

        private void drawMain()
        {
            if(! isVisible()) {
                return;
            }

            if(pic == null) {
                loadPictureSetting();
            }

            SurfaceHolder holder = getSurfaceHolder();

            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if(c != null) {
                    c.drawColor(0xff000000);
                    drawPicture(c);
                }
            }
            finally {
                if(c != null) {
                    holder.unlockCanvasAndPost(c);
                }
            }
        }

        private void drawPicture(Canvas c)
        {
            int xn = (int)xcur;
            int yn = (int)ycur;
            float xf = xcur - xn;
            float yf = ycur - yn;

            // (xn  ,yn)   * (1-xf) * (1-yf)
            // (xn+1,yn)   * xf     * (1-yf)
            // (xn  ,yn+1) * (1-xf) * yf
            // (xn+1,yn+1) * xf     * yf

            drawPicture(c, xn, yn, 1 - xf, 1 - yf, LEFT_TOP);
            if(xf > 0) {
                drawPicture(c, xn + 1, yn, xf, 1 - yf, RIGHT_TOP);
            }
            if(yf > 0) {
                drawPicture(c, xn, yn + 1, 1 - xf, yf, LEFT_BOTTOM);
            }
            if(xf > 0 && yf > 0) {
                drawPicture(c, xn + 1, yn + 1, xf, yf, RIGHT_BOTTOM);
            }
        }

        private void drawPicture(Canvas c,
                                 int xn, int yn, float xf, float yf,
                                 int pos)
        {
            int idx = xcnt * yn + xn;

            Rect screen_rect = null;
            int alpha = 255;

            if(screen_transition == TransitionType.none) {
                if(xf * yf < 0.5) {
                    return;
                }
                screen_rect = new Rect(0, 0, width, height);
            }
            else if(screen_transition == TransitionType.crossfade) {
                screen_rect = new Rect(0, 0, width, height);
                alpha = (int)(xf * yf * 255);
            }
            else if(screen_transition == TransitionType.fade_inout) {
                screen_rect = new Rect(0, 0, width, height);
                if(xf * yf < 0.5) {
                    alpha = 0;
                }
                else {
                    alpha = (int)(xf * yf * 255 * 2);
                }
            }
            else if(screen_transition == TransitionType.slide) {
                int sx = 0;
                int sy = 0;
                switch(pos) {
                case LEFT_TOP:
                    sx = (int)(-width * (1 - xf));
                    sy = (int)(-height * (1 - yf));
                    break;

                case RIGHT_TOP:
                    sx = (int)(width * (1 - xf));
                    sy = (int)(-height * (1 - yf));
                    break;

                case LEFT_BOTTOM:
                    sx = (int)(-width * (1 - xf));
                    sy = (int)(height * (1 - yf));
                    break;

                case RIGHT_BOTTOM:
                    sx = (int)(width * (1 - xf));
                    sy = (int)(height * (1 - yf));
                    break;
                }
                screen_rect = new Rect(sx, sy, width + sx, height + sy);
                alpha = 255;
            }
            else if(screen_transition == TransitionType.zoom_inout) {
                float frac = xf * yf;
                int sx = 0;
                int sy = 0;
                int sw = (int)(width * frac);
                int sh = (int)(height * frac);
                switch(pos) {
                case LEFT_TOP:
                    if(yf == 1f) {
                        sx = 0;
                        sy = (height - sh) / 2;
                    }
                    else if(xf == 1f) {
                        sx = (width - sw) / 2;
                        sy = 0;
                    }
                    else {
                        sx = 0;
                        sy = 0;
                    }
                    break;

                case RIGHT_TOP:
                    if(yf == 1f) {
                        sx = width - sw;
                        sy = (height - sh) / 2;
                    }
                    else if(xf == 1f) {
                        sx = (width - sw) / 2;
                        sy = 0;
                    }
                    else {
                        sx = width - sw;
                        sy = 0;
                    }
                    break;

                case LEFT_BOTTOM:
                    if(yf == 1f) {
                        sx = 0;
                        sy = (height - sh) / 2;
                    }
                    else if(xf == 1f) {
                        sx = (width - sw) / 2;
                        sy = height - sh;
                    }
                    else {
                        sx = 0;
                        sy = height - sh;
                    }
                    break;

                case RIGHT_BOTTOM:
                    if(yf == 1f) {
                        sx = width - sw;
                        sy = (height - sh) / 2;
                    }
                    else if(xf == 1f) {
                        sx = (width - sw) / 2;
                        sy = height - sh;
                    }
                    else {
                        sx = width - sw;
                        sy = height - sh;
                    }
                    break;
                }
                screen_rect = new Rect(sx, sy, sx + sw, sy + sh);
                alpha = 255;
            }

            if(pic[idx] == null || pic[idx].bmp == null) {
                text_paint.setAlpha(alpha);
                c.save();
                c.translate(screen_rect.left, screen_rect.top);
                c.scale((float)(screen_rect.right - screen_rect.left) / width,
                        (float)(screen_rect.bottom - screen_rect.top) / height);
                c.drawText(getString(R.string.str_pic_not_set, idx + 1),
                           width / 2, height / 2, text_paint);
                c.restore();
            }
            else {
                int alpha_cur = alpha;
                int alpha_prev = 0;

                if(pic[idx].prev_bmp != null &&
                   folder_trans_progress >= 0) {
                    if(folder_transition == TransitionType.crossfade) {
                        alpha_cur = alpha *
                            (FOLDER_TRANSITION_STEP - folder_trans_progress) /
                            FOLDER_TRANSITION_STEP;
                        alpha_prev = alpha *
                            folder_trans_progress / FOLDER_TRANSITION_STEP;
                    }
                    else if(folder_transition == TransitionType.fade_inout) {
                        alpha_cur = alpha *
                            (FOLDER_TRANSITION_STEP / 2 -
                             folder_trans_progress) /
                            FOLDER_TRANSITION_STEP;
                        alpha_prev = alpha *
                            (folder_trans_progress -
                             FOLDER_TRANSITION_STEP / 2) /
                            FOLDER_TRANSITION_STEP;

                        if(alpha_cur < 0) {
                            alpha_cur = 0;
                        }
                        if(alpha_prev < 0) {
                            alpha_prev = 0;
                        }
                    }

                    paint.setAlpha(alpha_prev);
                    c.drawBitmap(pic[idx].prev_bmp,
                                 pic[idx].prev_rect, screen_rect, paint);
                }

                paint.setAlpha(alpha_cur);
                c.drawBitmap(pic[idx].bmp, pic[idx].rect, screen_rect, paint);
            }
        }

        private void loadGlobalSetting()
        {
            default_type = ScreenType.valueOf(
                pref.getString("screen.default.type", "file"));

            screen_transition = TransitionType.valueOf(
                pref.getString("draw.transition", "slide"));

            folder_transition = TransitionType.valueOf(
                pref.getString("folder.transition", "fade_inout"));
            use_recursive = pref.getBoolean("folder.recursive", true);
            change_tap = pref.getBoolean("folder.changetap", true);
            change_duration = Integer.parseInt(
                pref.getString("folder.duration", "60"));
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

                int xr = opt.outWidth / width;
                int yr = opt.outHeight / height;
                int ratio = Math.min(xr, yr);

                // read picture
                opt = new BitmapFactory.Options();
                opt.inDither = true;
                opt.inPurgeable = true;
                opt.inInputShareable = true;
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
                double bxs = (double)width / bw;
                double bys = (double)height / bh;
                double bscale = Math.max(bxs, bys);

                if(bxs > bys) {
                    int ch = (int)((bh * bscale) - height);
                    int ch2 = (int)(ch / 2 / bscale);
                    info.rect = new Rect(0, ch2, bw, bh - ch2);
                }
                else {
                    int cw = (int)(bw * bscale) - width;
                    int cw2 = (int)(cw / 2 / bscale);
                    info.rect = new Rect(cw2, 0, bw - cw2, bh);
                }

                info.bmp = bmp;
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

        private void rotateFolderBitmap()
        {
            if(pic == null) {
                return;
            }

            int cnt = xcnt * ycnt;
            for(int i = 0; i < cnt; i++) {
                if(pic[i] != null && pic[i].type == ScreenType.folder) {
                    int fcnt = pic[i].file_list.size();
                    int idx_base = (int)(Math.random() * fcnt);
                    for(int j = 0; j < fcnt; j++) {
                        int idx = (idx_base + j) % fcnt;
                        if(idx == pic[i].cur_file_idx) {
                            continue;
                        }

                        Bitmap prev_bmp = pic[i].bmp;
                        Rect prev_rect = pic[i].rect;
                        if(loadBitmap(pic[i], pic[i].file_list.get(idx))) {
                            pic[i].cur_file_idx = idx;

                            if(pic[i].prev_bmp != null) {
                                pic[i].prev_bmp.recycle();
                            }

                            if(folder_transition != TransitionType.none) {
                                pic[i].prev_bmp = prev_bmp;
                                pic[i].prev_rect = prev_rect;
                            }
                            else {
                                if(prev_bmp != null) {
                                    prev_bmp.recycle();
                                }
                                pic[i].prev_bmp = null;
                                pic[i].prev_rect = null;
                            }

                            break;
                        }
                    }
                }
            }

            folder_trans_progress = FOLDER_TRANSITION_STEP;
            postDurationCallback();
            postStepCallback();

            // force GC to free unused bitmaps
            System.gc();
        }
    }
}
