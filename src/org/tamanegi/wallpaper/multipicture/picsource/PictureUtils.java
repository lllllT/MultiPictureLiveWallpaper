package org.tamanegi.wallpaper.multipicture.picsource;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

public class PictureUtils
{
    public static final Uri IMAGE_BUCKET_URI =
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI.
        buildUpon().appendQueryParameter("distinct", "true").build();
    private static final String[] IMAGE_BUCKET_COLUMNS = {
        MediaStore.Images.ImageColumns.BUCKET_ID,
        MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME
    };
    private static final int IMAGE_BUCKET_COL_BUCKET_ID = 0;
    private static final int IMAGE_BUCKET_COL_DISPLAY_NAME = 1;
    private static final String IMAGE_BUCKET_SORT_ORDER =
        "upper(" + MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME +
        ") ASC";

    public static final Uri IMAGE_LIST_URI =
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    public static final String[] IMAGE_LIST_COLUMNS = {
        MediaStore.Images.ImageColumns._ID,
        MediaStore.Images.ImageColumns.BUCKET_ID,
        MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
        MediaStore.Images.ImageColumns.DISPLAY_NAME,
        MediaStore.Images.ImageColumns.DATE_TAKEN,
        MediaStore.Images.ImageColumns.ORIENTATION,
    };
    public static final int IMAGE_LIST_COL_ID = 0;
    public static final int IMAGE_LIST_COL_BUCKET_NAME = 2;
    public static final int IMAGE_LIST_COL_DISPLAY_NAME = 3;
    public static final int IMAGE_LIST_COL_DATE = 4;
    public static final int IMAGE_LIST_COL_ORIENTATION = 5;

    public static String getUriFileName(ContentResolver resolver, String str)
    {
        return getUriFileName(resolver, Uri.parse(str));
    }

    public static String getUriFileName(ContentResolver resolver, Uri uri)
    {
        Cursor cur = null;
        try {
            cur = resolver.query(
                uri,
                new String[] { OpenableColumns.DISPLAY_NAME },
                null, null, null);
        }
        catch(Exception e) {
            // ignore
        }
        if(cur != null) {
            try {
                if(cur.moveToFirst()) {
                    return cur.getString(0);
                }
            }
            finally {
                cur.close();
            }
        }

        String name = uri.getLastPathSegment();
        if(name != null) {
            return name;
        }

        return uri.toString();
    }

    public static BucketItem[] getAvailBuckets(ContentResolver resolver)
    {
        Cursor cur;
        try {
            cur = resolver.query(IMAGE_BUCKET_URI,
                                 IMAGE_BUCKET_COLUMNS, null, null,
                                 IMAGE_BUCKET_SORT_ORDER);
        }
        catch(Exception e) {
            return null;
        }

        if(cur == null) {
            return null;
        }

        try {
            int cnt = cur.getCount();
            if(cnt < 1) {
                return null;
            }

            BucketItem[] list = new BucketItem[cnt];
            cur.moveToFirst();
            for(int i = 0; i < cnt; i++) {
                list[i] = new BucketItem();
                list[i].id = cur.getString(IMAGE_BUCKET_COL_BUCKET_ID);
                list[i].name = cur.getString(IMAGE_BUCKET_COL_DISPLAY_NAME);
                cur.moveToNext();
            }

            return list;
        }
        finally {
            cur.close();
        }
    }

    public static String getBucketNames(ContentResolver resolver, String str)
    {
        BucketItem[] buckets = getAvailBuckets(resolver);
        if(buckets == null) {
            return str;
        }

        String[] val_ids = str.split(" ");
        StringBuilder names = new StringBuilder();

        for(int i = 0; i < val_ids.length; i++) {
            if(i != 0) {
                names.append(", ");
            }

            boolean found = false;
            for(BucketItem item : buckets) {
                if(val_ids[i].equals(item.id)) {
                    names.append(item.name);
                    found = true;
                    break;
                }
            }
            if(! found) {
                names.append(val_ids[i]);
            }
        }

        return names.toString();
    }

    public static class BucketItem implements CharSequence
    {
        private String id;
        private String name;

        @Override
        public char charAt(int index)
        {
            return name.charAt(index);
        }

        @Override
        public int length()
        {
            return name.length();
        }

        @Override
        public CharSequence subSequence(int start, int end)
        {
            return name.subSequence(start, end);
        }

        @Override
        public String toString()
        {
            return name;
        }

        public String getId()
        {
            return id;
        }

        public String getName()
        {
            return name;
        }
    }

    public static boolean isPictureFile(String path)
    {
        try {
            BitmapFactory.Options opt;

            // just ask size of picture
            opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;

            BitmapFactory.decodeFile(path, opt);
            if(opt.outWidth < 0 || opt.outHeight < 0) {
                return false;
            }

            return true;
        }
        catch(Exception e) {
            return false;
        }
    }

    public static int getFileOrientation(File file)
    {
        try {
            ExifInterface exif = new ExifInterface(file.getPath());
            int ori = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL);

            if(ori == ExifInterface.ORIENTATION_ROTATE_90) {
                return 90;
            }
            if(ori == ExifInterface.ORIENTATION_ROTATE_180) {
                return 180;
            }
            if(ori == ExifInterface.ORIENTATION_ROTATE_270) {
                return 270;
            }
            return 0;
        }
        catch(IOException e) {
            // ignore
            return 0;
        }
    }

    public static int getContentOrientation(ContentResolver resolver, Uri uri)
    {
        if(ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            // "file:///..."
            return getFileOrientation(new File(uri.getPath()));
        }

        // get from media store
        Cursor cur = null;
        try {
            cur = resolver.query(
                uri,
                new String[] { MediaStore.Images.ImageColumns.ORIENTATION },
                null, null, null);
        }
        catch(Exception e) {
            // ignore
        }
        if(cur != null) {
            try {
                if(cur.moveToFirst()) {
                    return cur.getInt(0);
                }
            }
            finally {
                cur.close();
            }
        }

        return 0;
    }

    public static List<File> listFoldersRecursive(File folder)
    {
        ArrayList<File> list = new ArrayList<File>();
        list.add(folder);

        File[] files = null;
        try {
            files = folder.listFiles();
        }
        catch(SecurityException e) {
            // ignore
        }
        if(files == null) {
            return list;
        }

        for(File file : files) {
            if(file.isDirectory()) {
                list.addAll(listFoldersRecursive(file));
            }
        }

        return list;
    }

    public static ArrayList<FileInfo> listFolderPictures(
        List<File> folders, Map<String, Boolean> path_avail_map)
    {
        ArrayList<FileInfo> list = new ArrayList<FileInfo>();

        for(File folder : folders) {
            File[] files = null;
            try {
                files = folder.listFiles();
            }
            catch(SecurityException e) {
                // ignore
            }
            if(files == null) {
                continue;
            }

            for(File file : files) {
                if(file.isFile()) {
                    String path = file.getPath();
                    boolean avail;

                    synchronized(path_avail_map) {
                        avail = (path_avail_map.containsKey(path) ?
                                 path_avail_map.get(path) :
                                 isPictureFile(path));
                        path_avail_map.put(path, avail);
                    }

                    if(avail) {
                        FileInfo fi = new FileInfo();

                        fi.setUri(Uri.fromFile(file));
                        fi.setFullName(file.getPath());
                        fi.setDate(file.lastModified());
                        fi.setOrientation(getFileOrientation(file));

                        list.add(fi);
                    }
                }
            }
        }

        return list;
    }
}
