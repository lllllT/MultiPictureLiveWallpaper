package org.tamanegi.wallpaper.multipicture;

import java.util.Arrays;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
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
import android.preference.PreferenceScreen;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;

public class MultiPictureSetting extends PreferenceActivity
{
    public static final String SCREEN_TYPE_KEY = "screen.%d.type";
    public static final String SCREEN_FILE_KEY = "screen.%d.file";
    public static final String SCREEN_FOLDER_KEY = "screen.%d.folder";
    public static final String SCREEN_BUCKET_KEY = "screen.%d.bucket";
    public static final String SCREEN_BGCOLOR_KEY = "screen.%d.bgcolor";
    public static final String SCREEN_BGCOLOR_CUSTOM_KEY =
        "screen.%d.bgcolor.custom";
    public static final String SCREEN_CLIP_KEY = "screen.%d.clipratio";
    public static final String SCREEN_ORDER_KEY = "screen.%d.order";

    public static final String DEFAULT_TYPE_KEY = "screen.default.type";
    public static final String DEFAULT_FILE_KEY = "screen.default.file";
    public static final String DEFAULT_FOLDER_KEY = "screen.default.folder";
    public static final String DEFAULT_BUCKET_KEY = "screen.default.bucket";
    public static final String DEFAULT_BGCOLOR_KEY = "screen.default.bgcolor";
    public static final String DEFAULT_BGCOLOR_CUSTOM_KEY =
        "screen.default.bgcolor.custom";
    public static final String DEFAULT_CLIP_KEY = "draw.clipratio";
    public static final String DEFAULT_ORDER_KEY = "folder.order";

    public static final Uri IMAGE_BUCKET_URI =
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI.
        buildUpon().appendQueryParameter("distinct", "true").build();
    public static final String[] IMAGE_BUCKET_COLUMNS = {
        MediaStore.Images.ImageColumns.BUCKET_ID,
        MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME
    };
    public static final String IMAGE_BUCKET_SORT_ORDER =
        "upper(" + MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME +
        ") ASC";

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
        PreferenceGroup group = (PreferenceGroup)
            getPreferenceManager().findPreference("screen.cat");

        for(int i = 0; i < SCREEN_COUNT; i++) {
            // group for each screen
            PreferenceScreen sgroup =
                getPreferenceManager().createPreferenceScreen(this);
            sgroup.setTitle(
                getString(R.string.pref_cat_picture_screen_title, i + 1));
            sgroup.setSummary(
                getString((i == 0 ?
                           R.string.pref_cat_picture_screen_left_summary :
                           R.string.pref_cat_picture_screen_summary), i + 1));
            group.addPreference(sgroup);

            // screen type
            boolean is_file_val_exist =
                (pref.getString(
                    String.format(SCREEN_FILE_KEY, i), null) != null);

            ListPreference type = new ListPreference(this);
            type.setKey(String.format(SCREEN_TYPE_KEY, i));
            type.setTitle(R.string.pref_screen_type_title);
            type.setDialogTitle(type.getTitle());
            type.setSummary(R.string.pref_screen_type_base_summary);
            type.setEntries(R.array.pref_screen_type_entries);
            type.setEntryValues(R.array.pref_screen_type_entryvalues);
            type.setDefaultValue((is_file_val_exist ? "file" : "use_default"));
            type.setOnPreferenceChangeListener(
                new OnScreenTypeChangeListener(i));
            sgroup.addPreference(type);

            updateScreenTypeSummary(type, i);

            // background color
            ListPreference color = new ListPreference(this);
            color.setKey(String.format(SCREEN_BGCOLOR_KEY, i));
            color.setTitle(R.string.pref_screen_bgcolor_title);
            color.setDialogTitle(color.getTitle());
            color.setSummary(R.string.pref_screen_bgcolor_summary);
            color.setEntries(R.array.pref_screen_bgcolor_entries);
            color.setEntryValues(R.array.pref_screen_bgcolor_entryvalues);
            color.setDefaultValue("use_default");
            color.setOnPreferenceChangeListener(
                new OnColorChangeListener(i));
            sgroup.addPreference(color);

            updateColorSummary(color, null);

            // clip ratio
            ListPreference clip = new ListPreference(this);
            String clip_key = String.format(SCREEN_CLIP_KEY, i);
            clip.setKey(clip_key);
            clip.setTitle(R.string.pref_screen_clipratio_title);
            clip.setDialogTitle(clip.getTitle());
            clip.setSummary(R.string.pref_screen_clipratio_summary);
            clip.setEntries(R.array.pref_screen_clipratio_entries);
            clip.setEntryValues(R.array.pref_screen_clipratio_entryvalues);
            clip.setDefaultValue("use_default");
            sgroup.addPreference(clip);
            setupValueSummary(clip_key, R.string.pref_screen_clipratio_summary);

            // selection order
            ListPreference order = new ListPreference(this);
            String order_key = String.format(SCREEN_ORDER_KEY, i);
            order.setKey(order_key);
            order.setTitle(R.string.pref_screen_folder_order_title);
            order.setDialogTitle(order.getTitle());
            order.setSummary(R.string.pref_screen_folder_order_summary);
            order.setEntries(R.array.pref_screen_folder_order_entries);
            order.setEntryValues(R.array.pref_screen_folder_order_entryvalues);
            order.setDefaultValue("use_default");
            sgroup.addPreference(order);
            setupValueSummary(order_key,
                              R.string.pref_screen_folder_order_summary);
        }

        // setup default type item
        ListPreference def_type = (ListPreference)
            getPreferenceManager().findPreference(DEFAULT_TYPE_KEY);
        def_type.setOnPreferenceChangeListener(
            new OnScreenTypeChangeListener(-1));
        updateScreenTypeSummary(def_type, -1);

        ListPreference def_color = (ListPreference)
            getPreferenceManager().findPreference(DEFAULT_BGCOLOR_KEY);
        def_color.setOnPreferenceChangeListener(
            new OnColorChangeListener(-1));
        updateColorSummary(def_color, null);

        setupValueSummary(DEFAULT_CLIP_KEY,
                          R.string.pref_screen_clipratio_summary);
        setupValueSummary(DEFAULT_ORDER_KEY,
                          R.string.pref_screen_folder_order_summary);

        // backward compatibility
        boolean is_random = pref.getBoolean("folder.random", true);
        String order_val = pref.getString("folder.order", null);
        if(order_val == null) {
            ListPreference order = (ListPreference)
                getPreferenceManager().findPreference("folder.order");
            order_val = (is_random ? "random" : "name_asc");
            order.setValue(order_val);
        }

        String duration_min_str = pref.getString("folder.duration", null);
        String duration_sec_str = pref.getString("folder.duration_sec", null);
        if(duration_sec_str == null) {
            int duration_sec = (duration_min_str == null ? 60 * 60 :
                                Integer.parseInt(duration_min_str) * 60);
            ListPreference duration = (ListPreference)
                getPreferenceManager().findPreference("folder.duration_sec");
            duration.setValue(Integer.toString(duration_sec));
        }
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
        updateScreenTypeSummary(cur_item, cur_idx);

        cur_item = null;
        cur_idx = -1;
    }

    private void updateScreenTypeSummary(ListPreference item, int idx)
    {
        CharSequence entry = item.getEntry();
        String type_val = item.getValue();

        StringBuilder summary = new StringBuilder();
        summary.append(getString(R.string.pref_screen_type_base_summary));

        if(type_val != null) {
            if("file".equals(type_val)) {
                String file_key = (idx >= 0 ?
                                   String.format(SCREEN_FILE_KEY, idx) :
                                   DEFAULT_FILE_KEY);
                String file_val = pref.getString(file_key, "");
                summary.append(
                    getString(R.string.pref_screen_type_val2_summary,
                              entry, getUriFileName(file_val)));
            }
            else if("folder".equals(type_val)) {
                String folder_key = (idx >= 0 ?
                                     String.format(SCREEN_FOLDER_KEY, idx) :
                                     DEFAULT_FOLDER_KEY);
                String folder_val = pref.getString(folder_key, "");
                summary.append(
                    getString(R.string.pref_screen_type_val2_summary,
                              entry, folder_val));
            }
            else if("buckets".equals(type_val)) {
                String bucket_key = (idx >= 0 ?
                                     String.format(SCREEN_BUCKET_KEY, idx) :
                                     DEFAULT_BUCKET_KEY);
                String bucket_val = pref.getString(bucket_key, "");
                summary.append(
                    getString(R.string.pref_screen_type_val2_summary,
                              entry, getBucketNames(bucket_val)));
            }
            else if("use_default".equals(type_val)) {
                summary.append(
                    getString(R.string.pref_screen_type_val1_summary,
                              entry));
            }
        }

        item.setSummary(summary);
    }

    private void updateColorSummary(ListPreference item, String val)
    {
        updateValueSummary(item, R.string.pref_screen_bgcolor_summary, val);
    }

    private void updateValueSummary(ListPreference item, int res_id, String val)
    {
        if(val == null) {
            val = item.getValue();
        }

        item.setSummary(
            getString(res_id) +
            getString(R.string.pref_screen_val_summary,
                      item.getEntries()[item.findIndexOfValue(val)]));
    }

    private void setupValueSummary(String key, int res_id)
    {
        ListPreference item = (ListPreference)
            getPreferenceManager().findPreference(key);
        item.setOnPreferenceChangeListener(new OnValueChangeListener(res_id));
        updateValueSummary(item, res_id, null);
    }

    private String getUriFileName(String str)
    {
        Uri uri = Uri.parse(str);
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

        return str;
    }

    private String getBucketNames(String str)
    {
        BucketItem[] buckets = getBuckets();
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

    private BucketItem[] getBuckets()
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
                list[i].id = cur.getString(0);
                list[i].name = cur.getString(1);
                cur.moveToNext();
            }

            return list;
        }
        finally {
            cur.close();
        }
    }

    private void startBucketPickerDialog(final Preference item, final int idx,
                                         String bucket_val)
    {
        final BucketItem[] buckets = getBuckets();
        if(buckets == null) {
            showWarnMessage(R.string.pref_screen_bucket_title,
                            R.string.pref_screen_no_bucket_exist_msg);
            return;
        }

        final boolean[] checked = new boolean[buckets.length];
        if("buckets".equals(((ListPreference)item).getValue()) &&
           bucket_val != null) {
            String[] val_ids = bucket_val.split(" ");
            for(int i = 0; i < buckets.length; i++) {
                for(String val : val_ids) {
                    if(val.equals(buckets[i].id)) {
                        checked[i] = true;
                        break;
                    }
                }
            }
        }
        else {
            Arrays.fill(checked, true);
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.pref_screen_bucket_title)
            .setMultiChoiceItems(
                buckets, checked,
                new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface dialog,
                                        int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
            .setPositiveButton(
                android.R.string.yes,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int button) {
                        dialog.dismiss();
                        applyBucketValue((ListPreference)item, idx,
                                         buckets, checked);
                    }
                })
            .setNegativeButton(
                android.R.string.no,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int button) {
                        dialog.dismiss();
                    }
                })
            .show();
    }

    private void applyBucketValue(ListPreference item, int idx,
                                  BucketItem[] buckets, boolean[] checked)
    {
        boolean c = false;
        for(boolean check : checked) {
            c = (c || check);
        }
        if(! c) {
            showWarnMessage(R.string.pref_screen_bucket_title,
                            R.string.pref_screen_no_bucket_select_msg);
            return;
        }

        String type_val = "buckets";
        String data_key = (idx >= 0 ?
                           String.format(SCREEN_BUCKET_KEY, idx) :
                           DEFAULT_BUCKET_KEY);

        StringBuilder data_val = new StringBuilder();
        for(int i = 0; i < buckets.length; i++) {
            if(checked[i]) {
                data_val.append(buckets[i].id).append(" ");
            }
        }

        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(data_key, data_val.toString().trim());
        editor.commit();

        item.setValue(type_val);
        updateScreenTypeSummary(item, idx);
    }

    private void startColorPickerDialog(final Preference item, final int idx)
    {
        int color = pref.getInt((idx >= 0 ?
                                 String.format(SCREEN_BGCOLOR_CUSTOM_KEY, idx) :
                                 DEFAULT_BGCOLOR_CUSTOM_KEY),
                                0xff000000);

        View view = getLayoutInflater().inflate(R.layout.color_picker, null);
        final ColorPickerView picker =
            (ColorPickerView)view.findViewById(R.id.color_picker_picker);
        final View expl = view.findViewById(R.id.color_picker_explain);

        picker.setOnColorChangeListener(
            new ColorPickerView.OnColorChangeListener() {
                @Override public void onColorChange(int color) {
                    expl.setBackgroundColor(color);
                }
            });
        picker.setColor(color);

        new AlertDialog.Builder(this)
            .setTitle(R.string.pref_screen_bgcolor_title)
            .setView(view)
            .setPositiveButton(
                android.R.string.yes,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int button) {
                        applyCustomColor((ListPreference)item, idx,
                                         picker.getColor());
                    }
                })
            .setNegativeButton(android.R.string.no, null)
            .show();
    }

    private void applyCustomColor(ListPreference item, int idx, int color)
    {
        String val = "custom";

        updateColorSummary(item, val);
        item.setValue(val);

        SharedPreferences.Editor editor = pref.edit();
        editor.putInt((idx < 0 ?
                       DEFAULT_BGCOLOR_CUSTOM_KEY :
                       String.format(SCREEN_BGCOLOR_CUSTOM_KEY, idx)),
                      color);
        editor.commit();
    }

    private void showWarnMessage(int title_id, int msg_id)
    {
        new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(title_id)
            .setMessage(msg_id)
            .setPositiveButton(
                android.R.string.ok,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int button) {
                        dialog.dismiss();
                    }
                })
            .show();
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
                String folder_key = (idx >= 0 ?
                                     String.format(SCREEN_FOLDER_KEY, idx) :
                                     DEFAULT_FOLDER_KEY);
                String folder_val = pref.getString(folder_key, null);

                startFolderPickerActivity(item, idx, folder_val);
                return false;
            }
            else if("buckets".equals(val)) {
                String bucket_key = (idx >= 0 ?
                                     String.format(SCREEN_BUCKET_KEY, idx) :
                                     DEFAULT_BUCKET_KEY);
                String bucket_val = pref.getString(bucket_key, null);

                startBucketPickerDialog(item, idx, bucket_val);
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

    private class OnColorChangeListener
        implements Preference.OnPreferenceChangeListener
    {
        private int idx;

        private OnColorChangeListener(int idx)
        {
            this.idx = idx;
        }

        @Override
        public boolean onPreferenceChange(final Preference item, Object val)
        {
            if("custom".equals(val)) {
                startColorPickerDialog(item, idx);
                return false;
            }
            else {
                updateColorSummary((ListPreference)item, (String)val);
                return true;
            }
        }
    }

    private class OnValueChangeListener
        implements Preference.OnPreferenceChangeListener
    {
        private int res_id;

        private OnValueChangeListener(int res_id)
        {
            this.res_id = res_id;
        }

        @Override
        public boolean onPreferenceChange(Preference item, Object val)
        {
            updateValueSummary((ListPreference)item, res_id, (String)val);
            return true;
        }
    }

    private class BucketItem implements CharSequence
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
    }
}
