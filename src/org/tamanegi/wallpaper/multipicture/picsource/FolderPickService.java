package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.ArrayList;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.plugin.ScreenInfo;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.MediaStore;

public class FolderPickService extends AbstractMediaPickService
{
    @Override
    public LazyPicker onCreateLazyPicker()
    {
        return new FolderLazyPicker();
    }

    private class FolderLazyPicker extends MediaLazyPicker
    {
        private String folder;
        private boolean recursive;
        private OrderType change_order;
        private boolean rescan = true;

        @Override
        protected void onStart(String key, ScreenInfo hint)
        {
            // read preferences
            SharedPreferences pref = PreferenceManager.
                getDefaultSharedPreferences(FolderPickService.this);
            folder = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_FOLDER_KEY, key), "");
            recursive = pref.getBoolean(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_RECURSIVE_KEY, key), true);
            String order = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_ORDER_KEY, key), "");
            rescan = pref.getBoolean(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_RESCAN_KEY, key), true);

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

            // prepare query params
            String selection = null;
            ArrayList<String> selection_args = new ArrayList<String>();
            String order_by = null;

            String last_date = null;
            String last_id = null;
            if(last_file != null) {
                last_date = String.valueOf(last_file.getDate());
                last_id = String.valueOf(
                    ContentUris.parseId(last_file.getUri()));
            }

            if(change_order == OrderType.name_asc) {
                if(last_file != null) {
                    selection = MediaStore.Images.ImageColumns.DATA + " > ?";
                    selection_args.add(last_file.getFullName());
                }
                order_by =
                    MediaStore.Images.ImageColumns.DATA + " ASC";
            }
            else if(change_order == OrderType.name_desc) {
                if(last_file != null) {
                    selection = MediaStore.Images.ImageColumns.DATA + " < ?";
                    selection_args.add(last_file.getFullName());
                }
                order_by =
                    MediaStore.Images.ImageColumns.DATA + " DESC";
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
                    selection_args.add(last_date);
                    selection_args.add(last_date);
                    selection_args.add(last_id);
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
                    selection_args.add(last_date);
                    selection_args.add(last_date);
                    selection_args.add(last_id);
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
            String folder_where;
            if(recursive) {
                folder_where =
                    MediaStore.Images.ImageColumns.DATA + " GLOB " +
                    "? || '/*'";
            }
            else {
                folder_where =
                    MediaStore.Images.ImageColumns.DATA + " = " +
                    "? || '/' || " +
                    MediaStore.Images.ImageColumns.DISPLAY_NAME;
            }
            selection_args.add(folder);

            // query
            Uri list_uri = PictureUtils.IMAGE_LIST_URI;
            if(change_order != OrderType.random) {
                list_uri = list_uri.buildUpon()
                    .appendQueryParameter("limit", "1").build();
            }

            return resolver.query(
                list_uri,
                PictureUtils.IMAGE_LIST_COLUMNS,
                (selection != null ? "( " + selection + " ) AND " : "") +
                folder_where,
                selection_args.toArray(new String[0]),
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
                cur.getString(PictureUtils.IMAGE_LIST_COL_DATA));
            fi.setDate(
                cur.getLong(PictureUtils.IMAGE_LIST_COL_DATE));
            fi.setOrientation(
                cur.getInt(PictureUtils.IMAGE_LIST_COL_ORIENTATION));

            return fi;
        }
    }
}
