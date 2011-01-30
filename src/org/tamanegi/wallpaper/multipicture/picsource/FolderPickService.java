package org.tamanegi.wallpaper.multipicture.picsource;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.plugin.PictureContentInfo;
import org.tamanegi.wallpaper.multipicture.plugin.ScreenInfo;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.FileObserver;
import android.preference.PreferenceManager;

public class FolderPickService extends AbstractFileListPickService
{
    private BroadcastReceiver receiver;
    private HashMap<String, FolderObserver> observer_map;
    private HashMap<String, Boolean> path_avail_map;

    @Override
    public LazyPicker onCreateLazyPicker()
    {
        return new FolderLazyPicker();
    }

    private class FolderLazyPicker extends FileListLazyPicker
    {
        private String key;
        private boolean rescan = true;
        private List<File> folders = null;

        private ArrayList<FileInfo> file_list = null;
        private OrderType change_order;
        private int cur_file_idx = -1;

        @Override
        protected void onStart(String key, ScreenInfo hint)
        {
            this.key = key;
            super.onStart(key, hint);
        }

        @Override
        protected void onStop()
        {
            super.onStop();
            removeFolderObservers(folders, this);
            folders = null;
        }

        @Override
        protected void onLoadFileList()
        {
            // read preferences
            SharedPreferences pref = PreferenceManager.
                getDefaultSharedPreferences(FolderPickService.this);
            String folder = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_FOLDER_KEY, key), "");
            boolean recursive = pref.getBoolean(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_RECURSIVE_KEY, key), true);
            String order = pref.getString(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_ORDER_KEY, key), "");
            rescan = pref.getBoolean(
                MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_RESCAN_KEY, key), true);

            OrderType change_order;
            try {
                change_order = OrderType.valueOf(order);
            }
            catch(IllegalArgumentException e) {
                change_order = OrderType.random;
            }

            // list folders
            List<File> folders;
            if(recursive) {
                folders = PictureUtils.listFoldersRecursive(new File(folder));
            }
            else {
                folders = new ArrayList<File>();
                folders.add(new File(folder));
            }

            // folder observer
            addFolderObservers(folders, this);

            // clear prev observers
            removeFolderObservers(this.folders, this);
            this.folders = null;

            // list picture files
            ArrayList<FileInfo> flist =
                PictureUtils.listFolderPictures(folders, path_avail_map);

            // set file list
            setFileList(flist, change_order);
            this.folders = folders;
        }

        @Override
        protected boolean acceptRescan()
        {
            return rescan;
        }

        @Override
        protected PictureContentInfo getNextContent()
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
                   matchLastUri(next_file.getUri())) {
                    if(i == 0) {
                        retry_saved_idx = next_idx;
                    }
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

        private void setFileList(ArrayList<FileInfo> flist,
                                 OrderType change_order)
        {
            // sort by specified order
            if(change_order == OrderType.random) {
                Collections.shuffle(flist);
            }
            else {
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
    }

    @Override
    protected void onAddFirstPicker()
    {
        // prepare
        observer_map = new HashMap<String, FolderObserver>();
        path_avail_map = new HashMap<String, Boolean>();

        // receiver for broadcast
        IntentFilter filter;
        receiver = new Receiver();

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addDataScheme(ContentResolver.SCHEME_FILE);
        registerReceiver(receiver, filter);
    }

    @Override
    protected void onRemoveLastPicker()
    {
        // receiver
        try {
            unregisterReceiver(receiver);
        }
        catch(Exception e) {
            // ignore
        }

        // clear observers
        if(observer_map != null) {
            for(FolderObserver observer : observer_map.values()) {
                observer.stopWatching();
            }
            observer_map.clear();
        }
    }

    private class Receiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            synchronized(path_avail_map) {
                path_avail_map.clear();
            }
            postRescanAllCallback();
        }
    }

    private void addFolderObservers(List<File> folders, FolderLazyPicker picker)
    {
        synchronized(observer_map) {
            for(File folder : folders) {
                String path = folder.getPath();

                if(observer_map.containsKey(path)) {
                    FolderObserver observer = observer_map.get(path);

                    observer.addPicker(picker);
                }
                else {
                    FolderObserver observer = new FolderObserver(path);

                    observer_map.put(path, observer);
                    observer.addPicker(picker);
                    observer.startWatching();
                }
            }
        }
    }

    private void removeFolderObservers(List<File> folders,
                                       FolderLazyPicker picker)
    {
        if(folders == null) {
            return;
        }

        synchronized(observer_map) {
            for(File folder : folders) {
                String path = folder.getPath();

                FolderObserver observer = observer_map.get(path);
                if(observer == null) {
                    continue;
                }

                observer.removePicker(picker);
                if(observer.pickersCount() == 0) {
                    observer.stopWatching();
                    observer_map.remove(path);
                }
            }
        }
    }

    private class FolderObserver extends FileObserver
    {
        private static final int EVENTS =
            CREATE | DELETE | DELETE_SELF | MODIFY |
            MOVED_FROM | MOVED_TO | MOVE_SELF;

        private ArrayList<FolderLazyPicker> pickers;
        private String base;

        private FolderObserver(String path)
        {
            super(path, EVENTS);
            pickers = new ArrayList<FolderLazyPicker>();
            base = path;
        }

        private void addPicker(FolderLazyPicker picker)
        {
            synchronized(pickers) {
                pickers.add(picker);
            }
        }

        private void removePicker(FolderLazyPicker picker)
        {
            synchronized(pickers) {
                pickers.remove(picker);
            }
        }

        private int pickersCount()
        {
            synchronized(pickers) {
                return pickers.size();
            }
        }

        @Override
        public void onEvent(int event, String path)
        {
            if((event & EVENTS) != 0) {
                synchronized(path_avail_map) {
                    path_avail_map.remove(new File(base, path).getPath());
                }
                synchronized(pickers) {
                    for(FolderLazyPicker picker : pickers) {
                        postRescanCallback(picker);
                    }
                }
            }
        }
    }
}
