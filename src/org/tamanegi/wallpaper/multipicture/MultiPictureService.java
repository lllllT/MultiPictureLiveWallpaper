package org.tamanegi.wallpaper.multipicture;

import java.io.FileNotFoundException;
import java.io.InputStream;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public class MultiPictureService extends WallpaperService
{

    private static final int LEFT_TOP = 1;
    private static final int RIGHT_TOP = 2;
    private static final int LEFT_BOTTOM = 3;
    private static final int RIGHT_BOTTOM = 4;

    private static enum TransitionType
    {
        SLIDE, CROSSFADE,
    }

    @Override
    public Engine onCreateEngine()
    {
        return new MultiPictureEngine();
    }

    private class PictureInfo
    {
        private Bitmap bmp;
        private Rect rect;
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

        private PictureInfo pic[] = null;
        private TransitionType transition = TransitionType.SLIDE;

        private Paint paint;
        private Paint text_paint;

        private SharedPreferences pref;

        private MultiPictureEngine()
        {
            paint = new Paint();
            paint.setFilterBitmap(true);
            paint.setColor(0xff000000);

            text_paint = new Paint();
            text_paint.setColor(0xffffffff);
            text_paint.setTextAlign(Paint.Align.CENTER);

            pref = PreferenceManager.getDefaultSharedPreferences(
                MultiPictureService.this);
            pref.registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences pref,
                                              String key)
        {
            pic = null;

            String trans_str = pref.getString("draw.transition", "slide");
            if("slide".equals(trans_str)) {
                transition = TransitionType.SLIDE;
            }
            else if("crossfade".equals(trans_str)) {
                transition = TransitionType.CROSSFADE;
            }
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
            if(visible) {
                drawMain();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder sh, int format,
                                     int width, int height)
        {
            super.onSurfaceChanged(sh, format, width, height);

            if(this.width != width || this.height != height) {
                this.width = width;
                this.height = height;
                //screen_rect = new Rect(0, 0, width, height);
                pic = null;
            }

            drawMain();
        }

        private void drawMain()
        {
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

            if(pic == null) {
                pic = new PictureInfo[xcnt * ycnt];
            }
            if(pic[idx] == null) {
                try {
                    pic[idx] = getBitmap(idx);
                }
                catch(Exception e) {
                    pic[idx] = null;
                }
            }

            Rect screen_rect = null;
            int alpha = 255;

            if(transition == TransitionType.SLIDE) {
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
            else if(transition == TransitionType.CROSSFADE) {
                screen_rect = new Rect(0, 0, width, height);
                alpha = (int)(xf * yf * 255);
            }

            if(pic[idx] == null) {
                text_paint.setAlpha(alpha);
                c.drawText(getString(R.string.str_pic_not_set, idx + 1),
                           (screen_rect.left + screen_rect.right) / 2,
                           (screen_rect.top + screen_rect.bottom) / 2,
                           text_paint);
            }
            else {
                paint.setAlpha(alpha);
                c.drawBitmap(pic[idx].bmp, pic[idx].rect, screen_rect, paint);
            }
        }

        private PictureInfo getBitmap(int idx) throws FileNotFoundException
        {
            ContentResolver resolver = getContentResolver();
            String key = "screen." + idx + ".file";
            String fname = pref.getString(key, null);
            Uri file = Uri.parse(fname);
            InputStream instream;
            BitmapFactory.Options opt;

            opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;

            instream = resolver.openInputStream(file);
            BitmapFactory.decodeStream(instream, null, opt);
            int xr = opt.outWidth / width;
            int yr = opt.outHeight / height;
            int ratio = Math.min(xr, yr);

            opt = new BitmapFactory.Options();
            opt.inDither = true;
            opt.inPurgeable = true;
            opt.inInputShareable = true;
            opt.inSampleSize = ratio;

            instream = resolver.openInputStream(file);
            Bitmap bmp = BitmapFactory.decodeStream(instream, null, opt);
            if(bmp == null) {
                return null;
            }

            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            double bxs = (double)width / bw;
            double bys = (double)height / bh;
            double bscale = Math.max(bxs, bys);

            PictureInfo info = new PictureInfo();
            info.bmp = bmp;
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

            return info;
        }
    }

}
