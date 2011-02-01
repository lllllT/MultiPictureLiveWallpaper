package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.tamanegi.wallpaper.multipicture.plugin.LazyPickService;
import org.tamanegi.wallpaper.multipicture.plugin.PictureContentInfo;
import org.tamanegi.wallpaper.multipicture.plugin.ScreenInfo;

import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;

public abstract class AbstractFileListPickService extends LazyPickService
{
    private static final int RESCAN_DELAY = 5000; // msec

    private static final int LAST_URI_CNT_FACTOR = 2;

    private static final int MSG_RESCAN_ALL = 1;
    private static final int MSG_LOAD = 2;

    private ArrayList<FileListLazyPicker> picker_list;
    private LinkedList<Uri> last_uris;
    private HandlerThread worker_thread;
    private Handler handler;

    protected abstract void onAddFirstPicker();
    protected abstract void onRemoveLastPicker();

    @Override
    public void onCreate()
    {
        super.onCreate();

        picker_list = new ArrayList<FileListLazyPicker>();
        last_uris = new LinkedList<Uri>();

        worker_thread = new HandlerThread(
            "AbstractFileListPickService.worker",
            Process.THREAD_PRIORITY_BACKGROUND);
        worker_thread.start();
        handler = new Handler(worker_thread.getLooper(), new WorkerCallback());
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
        }
        worker_thread.quit();
    }

    protected void postRescanAllCallback()
    {
        handler.removeMessages(MSG_RESCAN_ALL);

        synchronized(picker_list) {
            for(FileListLazyPicker picker : picker_list) {
                picker.need_rescan.set(true);
            }
        }
        handler.sendEmptyMessageDelayed(MSG_RESCAN_ALL, RESCAN_DELAY);
    }

    protected void postRescanCallback(FileListLazyPicker picker)
    {
        handler.removeMessages(MSG_RESCAN_ALL);

        picker.need_rescan.set(true);
        handler.sendEmptyMessageDelayed(MSG_RESCAN_ALL, RESCAN_DELAY);
    }

    private class WorkerCallback implements Handler.Callback
    {
        public boolean handleMessage(Message msg)
        {
            switch(msg.what) {
              case MSG_RESCAN_ALL:
                  synchronized(picker_list) {
                      for(FileListLazyPicker picker : picker_list) {
                          picker.rescan();
                      }
                  }
                  break;

              case MSG_LOAD:
                  {
                      FileListLazyPicker picker = (FileListLazyPicker)msg.obj;
                      picker.onLoad();
                      synchronized(picker) {
                          picker.is_loading = false;
                          picker.notifyAll();
                      }
                  }
                  break;

              default:
                  return false;
            }

            return true;
        }
    }

    public abstract class FileListLazyPicker extends LazyPicker
    {
        private AtomicBoolean need_rescan = new AtomicBoolean(false);
        private boolean is_loading = false;

        protected abstract void onLoad();
        protected abstract PictureContentInfo getNextContent();

        @Override
        protected void onStart(String key, ScreenInfo hint)
        {
            addPicker(this);
            startLoading();
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
                if(is_loading) {
                    try {
                        wait();
                    }
                    catch(InterruptedException e) {
                        // ignore
                    }
                }

                info = getNextContent();
            }
            if(info != null) {
                addLastUri(info.getUri());
            }

            return info;
        }

        public void startLoading()
        {
            startLoading(0);
        }

        public void startLoading(long delay)
        {
            synchronized(this) {
                if(is_loading) {
                    return;
                }

                is_loading = true;
                handler.sendMessageDelayed(
                    handler.obtainMessage(MSG_LOAD, this), delay);
            }
        }

        protected boolean acceptRescan()
        {
            return true;
        }

        private void rescan()
        {
            if(need_rescan.getAndSet(false) && acceptRescan()) {
                startLoading();
                notifyChanged();
            }
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
                handler.removeMessages(MSG_RESCAN_ALL);
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
}
