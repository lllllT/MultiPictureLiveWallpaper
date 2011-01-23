package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.ArrayList;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.plugin.ScreenInfo;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.preference.PreferenceManager;

public class AlbumPickService extends AbstractFileListPickService
{
    private Observer observer;
    private BroadcastReceiver receiver;

    @Override
    public LazyPicker onCreateLazyPicker()
    {
        return new AlbumLazyPicker();
    }

    private class AlbumLazyPicker extends FileListLazyPicker
    {
        private String key;

        @Override
        protected void onStart(String key, ScreenInfo hint)
        {
            this.key = key;
            super.onStart(key, hint);
        }

        @Override
        protected void onLoadFileList()
        {
            // read preferences
            SharedPreferences pref = PreferenceManager.
                getDefaultSharedPreferences(AlbumPickService.this);
            String bucket = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_BUCKET_KEY, key), "");
            String order = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_ORDER_KEY, key), "");

            OrderType change_order;
            try {
                change_order = OrderType.valueOf(order);
            }
            catch(IllegalArgumentException e) {
                change_order = OrderType.random;
            }

            // read content list
            ArrayList<FileInfo> flist =
                PictureUtils.listBucketPictures(getContentResolver(),
                                                bucket.split(" "));

            // set file list
            setFileList(flist, change_order);
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
