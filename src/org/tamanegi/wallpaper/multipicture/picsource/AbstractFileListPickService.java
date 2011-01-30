package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.tamanegi.wallpaper.multipicture.plugin.LazyPickService;
import org.tamanegi.wallpaper.multipicture.plugin.PictureContentInfo;
import org.tamanegi.wallpaper.multipicture.plugin.ScreenInfo;

import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;

public abstract class AbstractFileListPickService extends LazyPickService
{
    private static final int RESCAN_DELAY = 5000; // msec

    private static final int LAST_URI_CNT_FACTOR = 2;

    private ArrayList<FileListLazyPicker> picker_list;
    private LinkedList<Uri> last_uris;
    private Handler handler;
    private Runnable rescan_callback;

    protected abstract void onAddFirstPicker();
    protected abstract void onRemoveLastPicker();

    @Override
    public void onCreate()
    {
        super.onCreate();

        picker_list = new ArrayList<FileListLazyPicker>();
        last_uris = new LinkedList<Uri>();
        handler = new Handler();
        rescan_callback = new Runnable() {
                public void run() {
                    rescanAll();
                }
            };
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();

        synchronized(picker_list) {
            if(picker_list.size() != 0) {
                onRemoveLastPicker();
                picker_list.clear();
            }

            handler.removeCallbacks(rescan_callback);
        }
    }

    protected void postRescanAllCallback()
    {
        handler.removeCallbacks(rescan_callback);

        synchronized(picker_list) {
            for(FileListLazyPicker picker : picker_list) {
                picker.need_rescan.set(true);
            }
        }
        handler.postDelayed(rescan_callback, RESCAN_DELAY);
    }

    protected void postRescanCallback(FileListLazyPicker picker)
    {
        handler.removeCallbacks(rescan_callback);

        picker.need_rescan.set(true);
        handler.postDelayed(rescan_callback, RESCAN_DELAY);
    }

    public abstract class FileListLazyPicker extends LazyPicker
    {
        private AtomicBoolean need_rescan = new AtomicBoolean(false);

        protected abstract void onLoadFileList();
        protected abstract PictureContentInfo getNextContent();

        @Override
        protected void onStart(String key, ScreenInfo hint)
        {
            addPicker(this);
            loadSettings();
        }

        @Override
        protected void onStop()
        {
            removePicker(this);
        }

        @Override
        public PictureContentInfo getNext()
        {
            PictureContentInfo info;
            synchronized(this) {
                info = getNextContent();
            }
            if(info != null) {
                addLastUri(info.getUri());
            }

            return info;
        }

        protected boolean acceptRescan()
        {
            return true;
        }

        private void rescan()
        {
            if(need_rescan.getAndSet(false) && acceptRescan()) {
                loadSettings();

                // always notify, even if current Uri is not changed.
                // (maybe changed inner content)
                notifyChanged();
            }
        }

        private synchronized void loadSettings()
        {
            onLoadFileList();
        }
    }

    private void addPicker(FileListLazyPicker picker)
    {
        synchronized(picker_list) {
            if(picker_list.size() == 0) {
                onAddFirstPicker();
            }

            picker_list.add(picker);
        }
    }

    private void removePicker(FileListLazyPicker picker)
    {
        synchronized(picker_list) {
            picker_list.remove(picker);
            adjustLastUri();

            if(picker_list.size() == 0) {
                onRemoveLastPicker();
                handler.removeCallbacks(rescan_callback);
            }
        }
    }

    protected void addLastUri(Uri uri)
    {
        synchronized(picker_list) {
            last_uris.addLast(uri);
            adjustLastUri();
        }
    }

    protected boolean matchLastUri(Uri uri)
    {
        synchronized(picker_list) {
            return last_uris.contains(uri);
        }
    }

    private void adjustLastUri()
    {
        while(last_uris.size() > picker_list.size() * LAST_URI_CNT_FACTOR) {
            last_uris.removeFirst();
        }
    }

    private void rescanAll()
    {
        synchronized(picker_list) {
            for(FileListLazyPicker picker : picker_list) {
                new AsyncRescan().execute(picker);
            }
        }
    }

    // just to use system allocated thread pool
    private class AsyncRescan extends AsyncTask<FileListLazyPicker, Void, Void>
    {
        @Override
        protected Void doInBackground(FileListLazyPicker... pickers)
        {
            pickers[0].rescan();
            return null;
        }
    }
}
