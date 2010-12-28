package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    protected void postRescanAllCallback()
    {
        handler.removeCallbacks(rescan_callback);
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
        private ArrayList<FileInfo> file_list = null;
        private OrderType change_order;
        private int cur_file_idx = -1;

        private AtomicBoolean need_rescan = new AtomicBoolean(false);

        protected abstract void onLoadFileList();

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
            PictureContentInfo info = getNextContent();
            if(info != null) {
                addLastUri(info.getUri());
            }

            return info;
        }

        public synchronized PictureContentInfo getNextContent()
        {
            if(file_list == null) {
                return null;
            }

            int retry_saved_idx = -1;

            int cnt = file_list.size();
            for(int i = 0; i < cnt; i++) {
                int next_idx = (cur_file_idx + i + 1) % cnt;
                if(change_order == OrderType.random && next_idx == 0) {
                    Collections.shuffle(file_list);
                }

                FileInfo next_file = file_list.get(next_idx);
                if(change_order == OrderType.random &&
                   i == 0 &&
                   matchLastUri(next_file.getUri())) {
                    retry_saved_idx = next_idx;
                    continue;
                }

                cur_file_idx = next_idx;
                return new PictureContentInfo(
                    next_file.getUri(), next_file.getOrientation());
            }

            if(retry_saved_idx >= 0) {
                cur_file_idx = retry_saved_idx;
                FileInfo next_file = file_list.get(cur_file_idx);
                return new PictureContentInfo(
                    next_file.getUri(), next_file.getOrientation());
            }

            return null;
        }

        protected void setFileList(ArrayList<FileInfo> flist,
                                   OrderType change_order)
        {
            // sort by specified order
            if(change_order == OrderType.shuffle) {
                Collections.shuffle(flist);
            }
            else if(change_order != OrderType.random) {
                Comparator<FileInfo> comparator =
                    FileInfo.getComparator(change_order);
                Collections.sort(flist, comparator);
            }

            // preserve current index if same content exist
            if(file_list != null && cur_file_idx >= 0) {
                FileInfo cur_file = file_list.get(cur_file_idx);
                cur_file_idx = -1;

                int cnt = flist.size();
                for(int i = 0; i < cnt; i++) {
                    if(flist.get(i).equalsUri(cur_file)) {
                        cur_file_idx = i;
                        break;
                    }
                }
            }
            else {
                cur_file_idx = -1;
            }

            // replace list
            this.file_list = flist;
            this.change_order = change_order;
        }

        private void rescan()
        {
            if(need_rescan.getAndSet(false)) {
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

    private void addLastUri(Uri uri)
    {
        synchronized(picker_list) {
            last_uris.addLast(uri);
            adjustLastUri();
        }
    }

    private boolean matchLastUri(Uri uri)
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
