package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.ArrayList;
import java.util.HashMap;
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

    private HashMap<Uri, PictureObserver> observer_map;

    @Override
    public void onCreate()
    {
        super.onCreate();

        observer_map = new HashMap<Uri, PictureObserver>();
    }

    @Override
    public LazyPicker onCreateLazyPicker()
    {
        return new SingleLazyPicker();
    }

    private class SingleLazyPicker extends LazyPicker
    {
        private Uri uri;
        private AtomicBoolean content_changed;

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

            addObserver(this);

            receiver = new Receiver(this);
            receiver.start();
        }

        @Override
        protected void onStop()
        {
            receiver.stop();
            removeObserver(this);
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

    private void addObserver(SingleLazyPicker picker)
    {
        synchronized(observer_map) {
            if(! observer_map.containsKey(picker.uri)) {
                PictureObserver observer = new PictureObserver(picker.uri);
                observer_map.put(picker.uri, observer);
                observer.start();
            }

            observer_map.get(picker.uri).addPicker(picker);
        }
    }

    private void removeObserver(SingleLazyPicker picker)
    {
        synchronized(observer_map) {
            PictureObserver observer = observer_map.get(picker.uri);
            if(observer != null) {
                observer.removePicker(picker);
                if(observer.getPickerCount() == 0) {
                    observer_map.remove(observer);
                    observer.stop();
                }
            }
        }
    }

    private class PictureObserver
    {
        private static final int EVENTS =
            FileObserver.DELETE_SELF | FileObserver.MODIFY |
            FileObserver.MOVE_SELF;

        private Uri uri;
        private ArrayList<SingleLazyPicker> picker_list;

        private FileObserver file_observer;
        private ContentObserver content_observer;

        private PictureObserver(Uri uri)
        {
            this.uri = uri;
            picker_list = new ArrayList<SingleLazyPicker>();
        }

        private void addPicker(SingleLazyPicker picker)
        {
            synchronized(picker_list) {
                picker_list.add(picker);
            }
        }

        private void removePicker(SingleLazyPicker picker)
        {
            synchronized(picker_list) {
                picker_list.remove(picker);
            }
        }

        private int getPickerCount()
        {
            return picker_list.size();
        }

        private void start()
        {
            if(ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                file_observer = new FileObserver(uri.getPath(), EVENTS) {
                        @Override 
                        public void onEvent(int event, String path) {
                            if((event & EVENTS) != 0) {
                                synchronized(picker_list) {
                                    for(SingleLazyPicker picker : picker_list) {
                                        picker.postRescanCallback();
                                    }
                                }

                                stop();
                                start();
                            }
                        }
                    };
                file_observer.startWatching();
            }
            else if(ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                content_observer = new ContentObserver(null) {
                        @Override
                        public boolean deliverSelfNotifications ()
                        {
                            return true;
                        }

                        @Override
                        public void onChange(boolean selfChange)
                        {
                            synchronized(picker_list) {
                                for(SingleLazyPicker picker : picker_list) {
                                    picker.postRescanCallback();
                                }
                            }
                        }
                    };
                try {
                    getContentResolver().registerContentObserver(
                        uri, false, content_observer);
                }
                catch(Exception e) {
                    // ignore
                }
            }
        }

        private void stop()
        {
            if(ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                file_observer.stopWatching();
            }
            else if(ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
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
