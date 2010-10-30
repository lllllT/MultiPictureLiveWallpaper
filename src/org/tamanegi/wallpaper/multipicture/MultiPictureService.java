package org.tamanegi.wallpaper.multipicture;

import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.ListIterator;

import android.service.wallpaper.WallpaperService;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

public class MultiPictureService extends WallpaperService
{
    private LinkedList<WeakReference<MultiPictureRenderer>> renderer_list =
        new LinkedList<WeakReference<MultiPictureRenderer>>();

    @Override
    public Engine onCreateEngine()
    {
        return new MultiPictureEngine();
    }

    @Override
    public void onLowMemory()
    {
        for(ListIterator<WeakReference<MultiPictureRenderer>> i =
                renderer_list.listIterator();
            i.hasNext(); ) {
            WeakReference<MultiPictureRenderer> ref = i.next();
            MultiPictureRenderer elem = ref.get();
            if(elem != null) {
                elem.onLowMemory();
            }
            else {
                i.remove();
            }
        }
    }

    private void addRenderer(MultiPictureRenderer r)
    {
        renderer_list.add(new WeakReference<MultiPictureRenderer>(r));
    }

    private void removeRenderer(MultiPictureRenderer r)
    {
        for(ListIterator<WeakReference<MultiPictureRenderer>> i =
                renderer_list.listIterator();
            i.hasNext(); ) {
            WeakReference<MultiPictureRenderer> ref = i.next();
            MultiPictureRenderer elem = ref.get();
            if((elem != null && r == elem) || (elem == null)) {
                i.remove();
            }
        }
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
            addRenderer(renderer);
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
            removeRenderer(renderer);
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
