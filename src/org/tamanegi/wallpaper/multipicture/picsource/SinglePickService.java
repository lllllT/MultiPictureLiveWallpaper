package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.concurrent.atomic.AtomicBoolean;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.plugin.LazyPickService;
import org.tamanegi.wallpaper.multipicture.plugin.PictureContentInfo;
import org.tamanegi.wallpaper.multipicture.plugin.ScreenInfo;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.FileObserver;
import android.os.Handler;
import android.preference.PreferenceManager;

public class SinglePickService extends LazyPickService
{
    private static final int RESCAN_DELAY = 5000; // msec

    @Override
    public LazyPicker onCreateLazyPicker()
    {
        return new SingleLazyPicker();
    }

    private class SingleLazyPicker extends LazyPicker
    {
        private Uri uri;
        private AtomicBoolean content_changed;

        private PictureObserver observer;
        private Receiver receiver;

        private Handler handler;
        private Runnable rescan_callback;

        @Override
        protected void onStart(String key, ScreenInfo hint)
        {
            handler = new Handler();

            // read preference
            SharedPreferences pref = PreferenceManager.
                getDefaultSharedPreferences(SinglePickService.this);
            String fname = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_FILE_KEY, key), "");
            uri = Uri.parse(fname);

            content_changed = new AtomicBoolean(true);

            // picture file observer, receiver
            rescan_callback = new Runnable() {
                    @Override
                    public void run() {
                        content_changed.set(true);
                        notifyChanged();
                    }
                };

            observer = new PictureObserver(this);
            observer.start();

            receiver = new Receiver(this);
            receiver.start();
        }

        @Override
        protected void onStop()
        {
            observer.stop();
            receiver.stop();
            handler.removeCallbacks(rescan_callback);
        }

        @Override
        public PictureContentInfo getNext()
        {
            if(! content_changed.getAndSet(false)) {
                return null;
            }

            int orientation = PictureUtils.getContentOrientation(
                getContentResolver(), uri);
            return new PictureContentInfo(uri, orientation);
        }

        private void postRescanCallback()
        {
            handler.removeCallbacks(rescan_callback);
            handler.postDelayed(rescan_callback, RESCAN_DELAY);
        }
    }

    private class PictureObserver
    {
        private static final int EVENTS =
            FileObserver.DELETE_SELF | FileObserver.MODIFY |
            FileObserver.MOVE_SELF;

        private SingleLazyPicker picker;

        private FileObserver file_observer;
        private ContentObserver content_observer;

        private PictureObserver(SingleLazyPicker picker)
        {
            this.picker = picker;
        }

        private void start()
        {
            if(ContentResolver.SCHEME_FILE.equals(
                   picker.uri.getScheme())) {
                file_observer = new FileObserver(picker.uri.getPath()) {
                        @Override 
                        public void onEvent(int event, String path) {
                            if((event & EVENTS) != 0) {
                                picker.postRescanCallback();
                            }
                        }
                    };
                file_observer.startWatching();
            }
            else if(ContentResolver.SCHEME_CONTENT.equals(
                        picker.uri.getScheme())) {
                content_observer = new ContentObserver(null) {
                        @Override
                        public boolean deliverSelfNotifications ()
                        {
                            return true;
                        }

                        @Override
                        public void onChange(boolean selfChange)
                        {
                            picker.postRescanCallback();
                        }
                    };
                try {
                    getContentResolver().registerContentObserver(
                        picker.uri, false, content_observer);
                }
                catch(Exception e) {
                    // ignore
                }
            }
        }

        private void stop()
        {
            if(ContentResolver.SCHEME_FILE.equals(
                   picker.uri.getScheme())) {
                file_observer.stopWatching();
            }
            else if(ContentResolver.SCHEME_CONTENT.equals(
                        picker.uri.getScheme())) {
                try {
                    getContentResolver().unregisterContentObserver(
                        content_observer);
                }
                catch(Exception e) {
                    // ignore
                }
            }
        }
    }

    private class Receiver extends BroadcastReceiver
    {
        private SingleLazyPicker picker;

        private Receiver(SingleLazyPicker picker)
        {
            this.picker = picker;
        }

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
            picker.postRescanCallback();
        }
    }
}
