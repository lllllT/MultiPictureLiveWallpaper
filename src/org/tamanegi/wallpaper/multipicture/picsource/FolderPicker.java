package org.tamanegi.wallpaper.multipicture.picsource;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;

import org.tamanegi.wallpaper.multipicture.R;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

public class FolderPicker extends Activity
{
    public static final String EXTRA_INIT_PATH = "extraInitPath";
    public static final String EXTRA_PATH = "extraPath";

    private static final String KEY_CUR_FOLDER =
        "org.tamanegi.wallpaper.multipicture:cur_folder";
    private static final String KEY_HIST =
        "org.tamanegi.wallpaper.multipicture:hist";

    private File cur_folder = null;
    private LinkedList<File> hist = null;

    private TextView folder_name;
    private ListView folder_list;
    private FolderList list_data;
    private FileComparator list_comparator;

    private LayoutInflater inflater;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.folder_picker);

        inflater = (LayoutInflater)getSystemService(LAYOUT_INFLATER_SERVICE);
        list_comparator = new FileComparator();

        if(savedInstanceState != null) {
            // restore from saved state
            String saved_cur_folder =
                savedInstanceState.getString(KEY_CUR_FOLDER);
            String[] saved_hist =
                savedInstanceState.getStringArray(KEY_HIST);

            if(saved_cur_folder != null && saved_hist != null) {
                cur_folder = new File(saved_cur_folder);
                hist = new LinkedList<File>();
                for(String fname : saved_hist) {
                    hist.add(new File(fname));
                }
            }
        }
        if(cur_folder == null || hist == null) {
            // current folder
            String init = getIntent().getStringExtra(EXTRA_INIT_PATH);
            if(init != null) {
                cur_folder = new File(init);
            }
            else {
                cur_folder = Environment.getExternalStorageDirectory();
            }

            // folder history
            hist = new LinkedList<File>();
        }

        // view
        folder_name = (TextView)findViewById(R.id.folder_name);
        folder_list = (ListView)findViewById(R.id.folder_list);

        // folder list
        list_data = new FolderList();
        folder_list.setAdapter(list_data);
        folder_list.setOnItemClickListener(list_data);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        updateFolder();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState)
    {
        super.onSaveInstanceState(outState);

        String[] hist_strs = new String[hist.size()];
        int i = 0;
        for(File dir : hist) {
            hist_strs[i++] = dir.getPath();
        }

        outState.putString(KEY_CUR_FOLDER, cur_folder.getPath());
        outState.putStringArray(KEY_HIST, hist_strs);
    }

    @Override
    public void onBackPressed()
    {
        if(hist.size() == 0) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        cur_folder = hist.removeLast();
        updateFolder();
    }

    public void onButtonOk(View v)
    {
        Intent intent = getIntent();
        intent.putExtra(EXTRA_PATH, cur_folder.toString());
        setResult(RESULT_OK, intent);
        finish();
    }

    public void onButtonCancel(View v)
    {
        setResult(RESULT_CANCELED);
        finish();
    }

    private void updateFolder()
    {
        // show current folder name
        folder_name.setText(cur_folder.getPath());

        // show content of current folder
        list_data.requestUpdateData();
    }

    private class FolderList extends BaseAdapter
        implements AdapterView.OnItemClickListener
    {
        private File[] files = null;
        private AsyncFileList async_list = null;

        private FolderList()
        {
            requestUpdateData();
        }

        @Override
        public int getCount()
        {
            return (files != null ? files.length + 1 : 0);
        }

        @Override
        public Object getItem(int position)
        {
            return (position == 0 ? null : files[position - 1]);
        }

        @Override
        public long getItemId(int position)
        {
            return position;
        }

        @Override
        public View getView(int position, View convert_view, ViewGroup parent)
        {
            View view;
            if(convert_view == null) {
                view = inflater.inflate(
                    R.layout.folder_picker_list_item, parent, false);
            }
            else {
                view = convert_view;
            }

            String val;
            boolean is_dir;
            if(position == 0) {
                val = getString(R.string.folder_up);
                is_dir = true;
            }
            else {
                val = files[position - 1].getName();
                is_dir = files[position - 1].isDirectory();
                if(is_dir) {
                    val += "/";
                }
            }

            view.findViewById(R.id.text_foldername).setVisibility(View.GONE);
            view.findViewById(R.id.text_filename).setVisibility(View.GONE);

            TextView text = (TextView)view.findViewById(
                is_dir ? R.id.text_foldername : R.id.text_filename);
            text.setText(val);
            text.setVisibility(View.VISIBLE);

            return view;
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view,
                                int position, long id)
        {
            File next;

            if(position == 0) {
                next = cur_folder.getParentFile();
                if(next == null) {
                    return;
                }
            }
            else {
                next = files[position - 1];
                if(! next.isDirectory()) {
                    return;
                }
            }

            if(hist.size() != 0 && next.equals(hist.getLast())) {
                hist.removeLast();
            }
            else {
                hist.addLast(cur_folder);
            }

            cur_folder = next;
            updateFolder();
        }

        private void requestUpdateData()
        {
            if(async_list != null) {
                async_list.cancel(false);
            }

            async_list = new AsyncFileList();
            async_list.execute();
        }

        private void updateData(File[] data)
        {
            files = data;
            notifyDataSetChanged();

            folder_list.setSelection(0);

            async_list = null;
        }
    }

    private class AsyncFileList
        extends AsyncTask<Void, Void, File[]>
        implements DialogInterface.OnCancelListener
    {
        private ProgressDialog dlg = null;
        private Handler handler;
        private Runnable progress_starter = new Runnable() {
                public void run() {
                    dlg = ProgressDialog.show(
                        FolderPicker.this,
                        null, getString(R.string.folder_loading),
                        true, true, AsyncFileList.this);
                }
            };

        protected void onPreExecute()
        {
            handler = new Handler();
            handler.postDelayed(progress_starter, 500);
        }

        protected File[] doInBackground(Void... params)
        {
            File[] list = null;
            try {
                list = cur_folder.listFiles();
            }
            catch(SecurityException e) {
                // ignore
            }
            if(list == null) {
                return new File[0];
            }

            Arrays.sort(list, list_comparator);
            return list;
        }

        protected void onPostExecute(File[] result)
        {
            list_data.updateData(result);

            handler.removeCallbacks(progress_starter);
            if(dlg != null) {
                try {
                    dlg.dismiss();
                }
                catch(Exception e) {
                    // ignore
                }
            }
        }

        protected void onCancelled()
        {
            handler.removeCallbacks(progress_starter);
            if(dlg != null) {
                dlg.dismiss();
            }
        }

        public void onCancel(DialogInterface dialog)
        {
            cancel(false);
        }
    }

    private class FileComparator implements Comparator<File>
    {
        @Override
        public int compare(File v1, File v2)
        {
            if(v1.isDirectory() && (! v2.isDirectory())) {
                return -1;
            }
            if(v2.isDirectory() && (! v1.isDirectory())) {
                return +1;
            }

            return String.CASE_INSENSITIVE_ORDER.compare(
                v1.getName(), v2.getName());
        }
    }
}
