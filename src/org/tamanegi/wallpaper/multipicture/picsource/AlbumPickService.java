package org.tamanegi.wallpaper.multipicture.picsource;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.plugin.ScreenInfo;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.MediaStore;

public class AlbumPickService extends AbstractMediaPickService
{
    @Override
    public LazyPicker onCreateLazyPicker()
    {
        return new AlbumLazyPicker();
    }

    private class AlbumLazyPicker extends MediaLazyPicker
    {
        private String[] buckets;
        private OrderType change_order;
        private boolean rescan;

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
                    MultiPictureSetting.SCREEN_RESCAN_KEY, key), false);

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
        protected boolean acceptRescan()
        {
            return rescan;
        }

        @Override
        protected boolean isRandomOrder()
        {
            return (change_order == OrderType.random);
        }

        @Override
        protected Cursor queryImages(FileInfo last_file)
        {
            ContentResolver resolver = getContentResolver();

            // use all buckets if no buckets selected
            if(buckets.length == 1 && buckets[0].equals("")) {
                PictureUtils.BucketItem[] items =
                    PictureUtils.getAvailBuckets(getContentResolver());
                if(items != null) {
                    buckets = new String[items.length];
                    for(int i = 0; i < items.length; i++) {
                        buckets[i] = items[i].getId();
                    }
                }
            }

            // prepare query params
            String selection = null;
            String selection_arg = null;
            String order_by = null;

            if(change_order == OrderType.name_asc) {
                if(last_file != null) {
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
                if(last_file != null) {
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
                if(last_file != null) {
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
                if(last_file != null) {
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
            Uri list_uri = PictureUtils.IMAGE_LIST_URI;
            if(change_order != OrderType.random) {
                list_uri = list_uri.buildUpon()
                    .appendQueryParameter("limit", "1").build();
            }

            return resolver.query(
                list_uri,
                PictureUtils.IMAGE_LIST_COLUMNS,
                bucket_where +
                (selection != null ? " AND ( " + selection + " )" : ""),
                where_args,
                order_by);
        }

        @Override
        protected FileInfo getFileInfo(Cursor cur)
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
}
