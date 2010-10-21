package org.tamanegi.wallpaper.multipicture;

import android.service.wallpaper.WallpaperService;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

public class MultiPictureService extends WallpaperService
{
    @Override
    public Engine onCreateEngine()
    {
        return new MultiPictureEngine();
    }

    private class MultiPictureEngine extends Engine
    {
        private MultiPictureRenderer renderer;
        private GestureDetector gdetector;

        private MultiPictureEngine()
        {
            renderer = new MultiPictureRenderer(getApplicationContext());
        }

        @Override
        public void onCreate(SurfaceHolder sh)
        {
            renderer.onCreate(sh, isPreview());

            // for double tap
            gdetector = new GestureDetector(getApplicationContext(),
                                            new GestureListener());
            setTouchEventsEnabled(true);
        }

        @Override
        public void onDestroy()
        {
            renderer.onDestroy();
        }

        @Override
        public void onOffsetsChanged(float xoffset, float yoffset,
                                     float xstep, float ystep,
                                     int xpixel, int ypixel)
        {
            renderer.onOffsetsChanged(
                xoffset, yoffset, xstep, ystep, xpixel, ypixel);
        }

        @Override
        public void onVisibilityChanged(boolean visible)
        {
            renderer.onVisibilityChanged(visible);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder sh, int format,
                                     int width, int height)
        {
            renderer.onSurfaceChanged(sh, format, width, height);
            renderer.onDraw();
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
                renderer.onDoubleTap();
                return true;
            }
        }
    }
}
