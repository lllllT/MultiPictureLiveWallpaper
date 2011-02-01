package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.Random;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.plugin.PictureContentInfo;
import org.tamanegi.wallpaper.multipicture.plugin.ScreenInfo;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.MediaStore;

public class AlbumPickService extends AbstractFileListPickService
{
    private static final int NEXT_LOADING_DELAY = 2000; // msec

    private Observer observer;
    private BroadcastReceiver receiver;

    @Override
    public LazyPicker onCreateLazyPicker()
    {
        return new AlbumLazyPicker();
    }

    private class AlbumLazyPicker extends FileListLazyPicker
    {
        private String[] buckets;
        private OrderType change_order;
        private boolean rescan;

        private int cur_file_idx = -1;

        private int[] idx_list;
        private FileInfo last_file = null;
        private PictureContentInfo next_content = null;

        @Override
        protected void onStart(String key, ScreenInfo hint)
        {
            // read preference
            SharedPreferences pref = PreferenceManager.
                getDefaultSharedPreferences(AlbumPickService.this);
            String bucket = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_BUCKET_KEY, key), "");
            String order = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_ORDER_KEY, key), "");
            rescan = pref.getBoolean(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_RESCAN_KEY, key), true);

            buckets = bucket.split(" ");

            try {
                change_order = OrderType.valueOf(order);
            }
            catch(IllegalArgumentException e) {
                change_order = OrderType.random;
            }

            super.onStart(key, hint);
        }

        @Override
        protected void onLoad()
        {
            next_content = prepareNextContent(true);
        }

        @Override
        protected boolean acceptRescan()
        {
            return rescan;
        }

        @Override
        protected PictureContentInfo getNextContent()
        {
            startLoading(NEXT_LOADING_DELAY);
            return next_content;
        }

        private PictureContentInfo prepareNextContent(boolean use_last)
        {
            ContentResolver resolver = getContentResolver();

            // prepare query params
            String selection = null;
            String selection_arg = null;
            String order_by = null;

            use_last = (use_last && last_file != null);
            if(change_order == OrderType.name_asc) {
                if(use_last) {
                    selection =
                        MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME +
                        " || '/' || " +
                        MediaStore.Images.ImageColumns.DISPLAY_NAME +
                        " > ? OR " +
                        "( " +
                        MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME +
                        " || '/' || " +
                        MediaStore.Images.ImageColumns.DISPLAY_NAME +
                        " = ? AND " +
                        MediaStore.Images.ImageColumns._ID + " > ?" +
                        " )";
                    selection_arg = last_file.getFullName();
                }
                order_by =
                    MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME +
                    " ASC, " +
                    MediaStore.Images.ImageColumns.DISPLAY_NAME + " ASC, " +
                    MediaStore.Images.ImageColumns._ID + " ASC";
            }
            else if(change_order == OrderType.name_desc) {
                if(use_last) {
                    selection =
                        MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME +
                        " || '/' || " +
                        MediaStore.Images.ImageColumns.DISPLAY_NAME +
                        " < ? OR " +
                        "( " +
                        MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME +
                        " || '/' || " +
                        MediaStore.Images.ImageColumns.DISPLAY_NAME +
                        " = ? AND " +
                        MediaStore.Images.ImageColumns._ID + " > ?" +
                        " )";
                    selection_arg = last_file.getFullName();
                }
                order_by =
                    MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME +
                    " DESC, " +
                    MediaStore.Images.ImageColumns.DISPLAY_NAME + " DESC, " +
                    MediaStore.Images.ImageColumns._ID + " ASC";
            }
            else if(change_order == OrderType.date_asc) {
                if(use_last) {
                    selection =
                        MediaStore.Images.ImageColumns.DATE_TAKEN +
                        " > ? OR " +
                        "( " +
                        MediaStore.Images.ImageColumns.DATE_TAKEN +
                        " = ? AND " +
                        MediaStore.Images.ImageColumns._ID + " > ?" +
                        " )";
                    selection_arg = String.valueOf(last_file.getDate());
                }
                order_by =
                    MediaStore.Images.ImageColumns.DATE_TAKEN + " ASC, " +
                    MediaStore.Images.ImageColumns._ID + " ASC";
            }
            else if(change_order == OrderType.date_desc) {
                if(use_last) {
                    selection =
                        MediaStore.Images.ImageColumns.DATE_TAKEN +
                        " < ? OR " +
                        "( " +
                        MediaStore.Images.ImageColumns.DATE_TAKEN +
                        " = ? AND " +
                        MediaStore.Images.ImageColumns._ID + " > ?" +
                        " )";
                    selection_arg = String.valueOf(last_file.getDate());
                }
                order_by =
                    MediaStore.Images.ImageColumns.DATE_TAKEN + " DESC, " +
                    MediaStore.Images.ImageColumns._ID + " ASC";
            }
            else {
                order_by =
                    MediaStore.Images.ImageColumns._ID + " ASC";
            }

            // build query
            StringBuilder bucket_where = new StringBuilder();
            String[] where_args = new String[buckets.length +
                                             (selection != null ? 3 : 0)];

            for(int i = 0; i < buckets.length; i++) {
                bucket_where
                    .append(i == 0 ? "( " : " OR ")
                    .append(MediaStore.Images.ImageColumns.BUCKET_ID)
                    .append(" = ?");
                where_args[i] = buckets[i];
            }
            bucket_where.append(" )");

            if(selection != null) {
                where_args[buckets.length + 0] = selection_arg;
                where_args[buckets.length + 1] = selection_arg;
                where_args[buckets.length + 2] =
                    String.valueOf(ContentUris.parseId(last_file.getUri()));
            }

            // query
            Cursor cur;
            try {
                Uri list_uri = PictureUtils.IMAGE_LIST_URI;
                if(change_order != OrderType.random) {
                    list_uri = list_uri.buildUpon()
                        .appendQueryParameter("limit", "1").build();
                }

                cur = resolver.query(
                    list_uri,
                    PictureUtils.IMAGE_LIST_COLUMNS,
                    bucket_where +
                    (selection != null ? " AND ( " + selection + " )" : ""),
                    where_args,
                    order_by);
            }
            catch(Exception e) {
                return null;
            }
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
                    if(change_order == OrderType.random) {
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
                    if(change_order == OrderType.random && next_idx == 0) {
                        shuffleIndexes();
                    }

                    cur.moveToPosition(idx_list[next_idx]);
                    FileInfo next_file = getFileInfo(cur);

                    if(change_order == OrderType.random &&
                       matchLastUri(next_file.getUri())) {
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

        private FileInfo getFileInfo(Cursor cur)
        {
            FileInfo fi = new FileInfo();

            fi.setUri(
                ContentUris.withAppendedId(
                    PictureUtils.IMAGE_LIST_URI,
                    cur.getLong(PictureUtils.IMAGE_LIST_COL_ID)));
            fi.setFullName(
                cur.getString(PictureUtils.IMAGE_LIST_COL_BUCKET_NAME) +
                "/" +
                cur.getString(PictureUtils.IMAGE_LIST_COL_DISPLAY_NAME));
            fi.setDate(
                cur.getLong(PictureUtils.IMAGE_LIST_COL_DATE));
            fi.setOrientation(
                cur.getInt(PictureUtils.IMAGE_LIST_COL_ORIENTATION));

            return fi;
        }
    }

    @Override
    protected void onAddFirstPicker()
    {
        // content observer
        observer = new Observer();
        try {
            getContentResolver().registerContentObserver(
                PictureUtils.IMAGE_LIST_URI, true, observer);
        }
        catch(Exception e) {
            // ignore
        }

        // receiver for broadcast
        IntentFilter filter;
        receiver = new Receiver();

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        filter.addDataScheme(ContentResolver.SCHEME_FILE);
        registerReceiver(receiver, filter);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addDataScheme(ContentResolver.SCHEME_FILE);
        registerReceiver(receiver, filter);
    }

    @Override
    protected void onRemoveLastPicker()
    {
        // broadcast receiver
        try {
            unregisterReceiver(receiver);
        }
        catch(Exception e) {
            // ignore
        }

        // content observer
        try {
            getContentResolver().unregisterContentObserver(observer);
        }
        catch(Exception e) {
            // ignore
        }
    }

    private class Observer extends ContentObserver
    {
        private Observer()
        {
            super(null);
        }

        @Override
        public boolean deliverSelfNotifications ()
        {
            return true;
        }

        @Override
        public void onChange(boolean selfChange)
        {
            postRescanAllCallback();
        }
    }

    private class Receiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            postRescanAllCallback();
        }
    }
}
