package org.tamanegi.wallpaper.multipicture.picsource;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.R;
import org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.view.View;

public class FolderSource extends PreferenceActivity
{
    private String key;
    private boolean need_clear;

    private SharedPreferences pref;
    private Preference path_pref;
    private String path_val;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.button_preference_list);

        Intent intent = getIntent();
        need_clear =
            intent.getBooleanExtra(PictureSourceContract.EXTRA_CLEAR_PREVIOUS,
                                   true);
        key = intent.getStringExtra(PictureSourceContract.EXTRA_KEY);
        if(key == null) {
            finish();
        }

        addPreferencesFromResource(R.xml.folder_pref);
        pref = PreferenceManager.getDefaultSharedPreferences(this);

        // path
        String path_key = MultiPictureSetting.getKey(
            MultiPictureSetting.SCREEN_FOLDER_KEY, key);
        path_val = (need_clear ? null : pref.getString(path_key, null));

        path_pref = getPreferenceManager().findPreference("path");
        path_pref.setOnPreferenceClickListener(
            new Preference.OnPreferenceClickListener() {
                public boolean onPreferenceClick(Preference preference) {
                    startFolderPickerActivity();
                    return true;
                }
            });
        updatePathSummary();

        // order
        String order_key = MultiPictureSetting.getKey(
            MultiPictureSetting.SCREEN_ORDER_KEY, key);
        String order_val = (need_clear ? "" : pref.getString(order_key, ""));
        try {
            OrderType.valueOf(order_val);
        }
        catch(IllegalArgumentException e) {
            order_val = "random";
        }

        ListPreference order = (ListPreference)
            getPreferenceManager().findPreference("order");
        order.setValue(order_val);

        // recursive
        String recursive_key = MultiPictureSetting.getKey(
            MultiPictureSetting.SCREEN_RECURSIVE_KEY, key);
        boolean recursive_val =
            (need_clear ? true : pref.getBoolean(recursive_key, true));

        CheckBoxPreference recursive = (CheckBoxPreference)
            getPreferenceManager().findPreference("recursive");
        recursive.setChecked(recursive_val);

        // rescan
        String rescan_key = MultiPictureSetting.getKey(
            MultiPictureSetting.SCREEN_RESCAN_KEY, key);
        boolean rescan_val =
            (need_clear ? true : pref.getBoolean(rescan_key, true));

        CheckBoxPreference rescan = (CheckBoxPreference)
            getPreferenceManager().findPreference("rescan");
        rescan.setChecked(rescan_val);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data)
    {
        if(resultCode == RESULT_CANCELED || data == null) {
            return;
        }

        String path = data.getStringExtra(FolderPicker.EXTRA_PATH);
        if(path == null) {
            return;
        }

        path_val = path;
        need_clear = false;

        updatePathSummary();
    }

    public void onButtonOk(View v)
    {
        if(applyFolderValue()) {
            finish();
        }
    }

    public void onButtonCancel(View v)
    {
        setResult(RESULT_CANCELED);
        finish();
    }

    private boolean applyFolderValue()
    {
        if(path_val == null) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.pref_folder_title)
                .setMessage(R.string.pref_no_folder_select_msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return false;
        }

        ListPreference order = (ListPreference)
            getPreferenceManager().findPreference("order");
        String order_val = order.getValue();

        CheckBoxPreference recursive = (CheckBoxPreference)
            getPreferenceManager().findPreference("recursive");
        boolean recursive_val = recursive.isChecked();

        CheckBoxPreference rescan = (CheckBoxPreference)
            getPreferenceManager().findPreference("rescan");
        boolean rescan_val = rescan.isChecked();

        // save
        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(MultiPictureSetting.getKey(
                             MultiPictureSetting.SCREEN_FOLDER_KEY, key),
                         path_val);
        editor.putString(MultiPictureSetting.getKey(
                             MultiPictureSetting.SCREEN_ORDER_KEY, key),
                         order_val);
        editor.putBoolean(MultiPictureSetting.getKey(
                              MultiPictureSetting.SCREEN_RECURSIVE_KEY, key),
                          recursive_val);
        editor.putBoolean(MultiPictureSetting.getKey(
                              MultiPictureSetting.SCREEN_RESCAN_KEY, key),
                          rescan_val);
        editor.commit();

        // activity result
        Intent result = new Intent();
        result.putExtra(PictureSourceContract.EXTRA_DESCRIPTION,
                        getString(R.string.pref_screen_type_folder_desc,
                                  path_val));
        result.putExtra(PictureSourceContract.EXTRA_SERVICE_NAME,
                        new ComponentName(this, FolderPickService.class));

        setResult(RESULT_OK, result);
        return true;
    }

    private void startFolderPickerActivity()
    {
        Intent intent = new Intent(getApplicationContext(),
                                   FolderPicker.class);
        if(path_val != null) {
            intent.putExtra(FolderPicker.EXTRA_INIT_PATH, path_val);
        }

        startActivityForResult(intent, 0);
    }

    private void updatePathSummary()
    {
        path_pref.setSummary(
            getString(R.string.pref_folder_path_summary) +
            (path_val != null ?
             getString(R.string.pref_screen_val_summary, path_val) : ""));
    }
}
