package org.tamanegi.wallpaper.multipicture;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.provider.OpenableColumns;

public class MultiPictureSetting extends PreferenceActivity
{
    public static final String SCREEN_TYPE_KEY = "screen.%d.type";
    public static final String SCREEN_FILE_KEY = "screen.%d.file";
    public static final String SCREEN_FOLDER_KEY = "screen.%d.folder";

    public static final String DEFAULT_TYPE_KEY = "screen.default.type";
    public static final String DEFAULT_FILE_KEY = "screen.default.file";
    public static final String DEFAULT_FOLDER_KEY = "screen.default.folder";

    private static final int SCREEN_COUNT = 7;

    private static final int REQUEST_CODE_FILE = 1;
    private static final int REQUEST_CODE_FOLDER = 2;

    private SharedPreferences pref;
    private ContentResolver resolver;
    private Handler handler;

    private ListPreference cur_item = null;
    private int cur_idx = -1;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.pref);

        pref = PreferenceManager.getDefaultSharedPreferences(this);
        resolver = getContentResolver();
        handler = new Handler();

        // setup screen-N setting item
        PreferenceGroup cat = (PreferenceGroup)
            getPreferenceManager().findPreference("screen.cat");

        for(int i = 0; i < SCREEN_COUNT; i++) {
            String title = getString(R.string.pref_picture_screen_title, i + 1);
            boolean is_file_val_exist =
                (pref.getString(
                    String.format(SCREEN_FILE_KEY, i), null) != null);

            ListPreference item = new ListPreference(this);
            item.setKey(String.format(SCREEN_TYPE_KEY, i));
            item.setTitle(title);
            item.setDialogTitle(title);
            item.setSummary(
                getString(R.string.pref_picture_screen_base_summary, i + 1));
            item.setEntries(R.array.pref_picture_screen_type_entries);
            item.setEntryValues(R.array.pref_picture_screen_type_entryvalues);
            item.setDefaultValue((is_file_val_exist ? "file" : "use_default"));
            item.setOnPreferenceChangeListener(
                new OnScreenTypeChangeListener(i));

            cat.addPreference(item);

            updateScreenTypeSummary(item, i);
        }

        // setup default type item
        ListPreference item = (ListPreference)
            getPreferenceManager().findPreference(DEFAULT_TYPE_KEY);
        item.setOnPreferenceChangeListener(new OnDefaultTypeChangeListener());
        updateDefaultTypeSummary(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data)
    {
        if(cur_item == null || resultCode == RESULT_CANCELED) {
            return;
        }

        String type_val;
        String data_key;
        String data_val;

        if(requestCode == REQUEST_CODE_FILE) {
            type_val = "file";
            data_key = (cur_idx >= 0 ?
                        String.format(SCREEN_FILE_KEY, cur_idx) :
                        DEFAULT_FILE_KEY);
            data_val = data.getData().toString();
        }
        else if(requestCode == REQUEST_CODE_FOLDER) {
            type_val = "folder";
            data_key = (cur_idx >= 0 ?
                        String.format(SCREEN_FOLDER_KEY, cur_idx) :
                        DEFAULT_FOLDER_KEY);
            data_val = data.getStringExtra(FolderPicker.EXTRA_PATH);
        }
        else {
            return;
        }

        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(data_key, data_val);
        editor.commit();

        cur_item.setValue(type_val);
        if(cur_idx >= 0) {
            updateScreenTypeSummary(cur_item, cur_idx);
        }
        else {
            updateDefaultTypeSummary(cur_item);
        }

        cur_item = null;
        cur_idx = -1;
    }

    private void updateScreenTypeSummary(ListPreference item, int idx)
    {
        CharSequence entry = item.getEntry();
        String type_val = item.getValue();

        StringBuilder summary = new StringBuilder();
        summary.append(
            getString((idx == 0 ?
                       R.string.pref_picture_screen_left_summary :
                       R.string.pref_picture_screen_base_summary),
                      idx + 1));

        if(type_val != null) {
            if("file".equals(type_val)) {
                String file_key = String.format(SCREEN_FILE_KEY, idx);
                String file_val = pref.getString(file_key, "");
                summary.append(
                    getString(R.string.pref_picture_screen_val2_summary,
                              entry, getUriFileName(file_val)));
            }
            else if("folder".equals(type_val)) {
                String folder_key = String.format(SCREEN_FOLDER_KEY, idx);
                String folder_val = pref.getString(folder_key, "");
                summary.append(
                    getString(R.string.pref_picture_screen_val2_summary,
                              entry, folder_val));
            }
            else if("use_default".equals(type_val)) {
                summary.append(
                    getString(R.string.pref_picture_screen_val1_summary,
                              entry));
            }
        }

        item.setSummary(summary);
    }

    private void updateDefaultTypeSummary(ListPreference item)
    {
        CharSequence entry = item.getEntry();
        String type_val = item.getValue();

        if(type_val != null) {
            if("file".equals(type_val)) {
                String file_val = pref.getString(DEFAULT_FILE_KEY, "");
                item.setSummary(
                    getString(R.string.pref_picture_screen_default_val2_summary,
                              entry, getUriFileName(file_val)));
            }
            else if("folder".equals(type_val)) {
                String folder_val = pref.getString(DEFAULT_FOLDER_KEY, "");
                item.setSummary(
                    getString(R.string.pref_picture_screen_default_val2_summary,
                              entry, folder_val));
            }
        }
    }

    private String getUriFileName(String str)
    {
        Uri uri = Uri.parse(str);
        Cursor cur = resolver.query(
            uri,
            new String[] { OpenableColumns.DISPLAY_NAME },
            null, null, null);
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

        return str;
    }

    private void startFilePickerActivity(Preference item, int idx)
    {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");

        startActivityForResult(intent, REQUEST_CODE_FILE);
        cur_item = (ListPreference)item;
        cur_idx = idx;
    }

    private void startFolderPickerActivity(Preference item, int idx,
                                           String folder_val)
    {
        Intent intent = new Intent(getApplicationContext(),
                                   FolderPicker.class);
        if("folder".equals(((ListPreference)item).getValue()) &&
           folder_val != null) {
            intent.putExtra(FolderPicker.EXTRA_INIT_PATH, folder_val);
        }

        startActivityForResult(intent, REQUEST_CODE_FOLDER);
        cur_item = (ListPreference)item;
        cur_idx = idx;
    }

    private class OnScreenTypeChangeListener
        implements Preference.OnPreferenceChangeListener
    {
        private int idx;

        private OnScreenTypeChangeListener(int idx)
        {
            this.idx = idx;
        }

        @Override
        public boolean onPreferenceChange(final Preference item, Object val)
        {
            if("file".equals(val)) {
                startFilePickerActivity(item, idx);
                return false;
            }
            else if("folder".equals(val)) {
                String folder_key = String.format(SCREEN_FOLDER_KEY, idx);
                String folder_val = pref.getString(folder_key, null);

                startFolderPickerActivity(item, idx, folder_val);
                return false;
            }
            else if("use_default".equals(val)) {
                handler.post(new Runnable() {
                        public void run() {
                            updateScreenTypeSummary((ListPreference)item, idx);
                        }
                    });
                return true;
            }
            else {
                return false;
            }
        }
    }

    private class OnDefaultTypeChangeListener
        implements Preference.OnPreferenceChangeListener
    {
        @Override
        public boolean onPreferenceChange(final Preference item, Object val)
        {
            if("file".equals(val)) {
                startFilePickerActivity(item, -1);
                return false;
            }
            else if("folder".equals(val)) {
                String folder_val =
                    pref.getString(DEFAULT_FOLDER_KEY, null);

                startFolderPickerActivity(item, -1, folder_val);
                return false;
            }
            else {
                return false;
            }
        }
    }
}
