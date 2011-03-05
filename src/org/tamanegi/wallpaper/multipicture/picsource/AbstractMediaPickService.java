package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.Random;

import org.tamanegi.wallpaper.multipicture.plugin.PictureContentInfo;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;

public abstract class AbstractMediaPickService
    extends AbstractFileListPickService
{
    private static final int NEXT_LOADING_DELAY = 2000; // msec

    private Observer observer;
    private Receiver receiver;

    protected abstract class MediaLazyPicker extends FileListLazyPicker
    {
        private int cur_file_idx = -1;

        private int[] idx_list = null;
        private FileInfo last_file = null;
        private PictureContentInfo next_content = null;

        abstract protected boolean isRandomOrder();
        abstract protected Cursor queryImages(FileInfo last_file);
        abstract protected FileInfo getFileInfo(Cursor cur);

        @Override
        protected void onLoad()
        {
            try {
                next_content = prepareNextContent(true);
            }
            catch(Exception e) {
                e.printStackTrace();
                next_content = null;
            }
        }

        @Override
        protected PictureContentInfo getNextContent()
        {
            startLoading(NEXT_LOADING_DELAY);
            return next_content;
        }

        private PictureContentInfo prepareNextContent(boolean use_last)
        {
            // query
            Cursor cur = queryImages(use_last ? last_file : null);
            if(cur == null) {
                return null;
            }

            try {
                if(cur.getCount() < 1) {
                    if(use_last) {
                        return prepareNextContent(false);
                    }
                    else {
                        return null;
                    }
                }

                // prepare index list
                if(idx_list == null || idx_list.length != cur.getCount()) {
                    if(isRandomOrder()) {
                        int cnt = cur.getCount();
                        idx_list = new int[cnt];

                        for(int i = 0; i < cnt; i++) {
                            idx_list[i] = i;
                        }

                        cur_file_idx = -1;
                    }
                    else {
                        idx_list = new int[] { 0 };
                    }
                }

                // get next
                int retry_saved_idx = -1;
                FileInfo retry_saved_file = null;

                int cnt = idx_list.length;
                for(int i = 0; i < cnt; i++) {
                    int next_idx = (cur_file_idx + i + 1) % cnt;
                    if(isRandomOrder() && next_idx == 0) {
                        shuffleIndexes();
                    }

                    cur.moveToPosition(idx_list[next_idx]);
                    FileInfo next_file = getFileInfo(cur);

                    if(isRandomOrder() && matchLastUri(next_file.getUri())) {
                        if(i == 0) {
                            retry_saved_file = next_file;
                            retry_saved_idx = next_idx;
                        }
                        continue;
                    }

                    cur_file_idx = next_idx;
                    last_file = next_file;
                    return new PictureContentInfo(
                        next_file.getUri(), next_file.getOrientation());
                }

                if(retry_saved_file != null) {
                    cur_file_idx = retry_saved_idx;
                    last_file = retry_saved_file;
                    return new PictureContentInfo(
                        retry_saved_file.getUri(),
                        retry_saved_file.getOrientation());
                }

                return null;
            }
            finally {
                cur.close();
            }
        }

        private void shuffleIndexes()
        {
            Random random = new Random();

            for(int i = 0; i < idx_list.length; i++) {
                int idx = i + random.nextInt(idx_list.length - i);
                int v = idx_list[i];
                idx_list[i] = idx_list[idx];
                idx_list[idx] = v;
            }
        }
    }

    @Override
    protected void onAddFirstPicker()
    {
        // content observer
        observer = new Observer();
        observer.start();

        // receiver for broadcast
        receiver = new Receiver();
        receiver.start();
    }

    @Override
    protected void onRemoveLastPicker()
    {
        receiver.stop();
        observer.stop();
    }

    private class Observer extends ContentObserver
    {
        private Observer()
        {
            super(null);
        }

        private void start()
        {
            try {
                getContentResolver().registerContentObserver(
                    PictureUtils.IMAGE_LIST_URI, true, this);
            }
            catch(Exception e) {
                // ignore
            }
        }

        private void stop()
        {
            try {
                getContentResolver().unregisterContentObserver(this);
            }
            catch(Exception e) {
                // ignore
            }
        }

        @Override
        public boolean deliverSelfNotifications ()
        {
            return true;
        }

        @Override
        public void onChange(boolean selfChange)
        {
            postNotifyChangedAll();
        }
    }

    private class Receiver extends BroadcastReceiver
    {
        private void start()
        {
            IntentFilter filter;

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
            filter.addDataScheme(ContentResolver.SCHEME_FILE);
            registerReceiver(this, filter);

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            filter.addDataScheme(ContentResolver.SCHEME_FILE);
            registerReceiver(this, filter);
        }

        private void stop()
        {
            try {
                unregisterReceiver(this);
            }
            catch(Exception e) {
                // ignore
            }
        }

        @Override
        public void onReceive(Context context, Intent intent)
        {
            postNotifyChangedAll();
        }
    }
}
