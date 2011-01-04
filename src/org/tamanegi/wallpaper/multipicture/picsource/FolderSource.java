package org.tamanegi.wallpaper.multipicture.picsource;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.R;
import org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.widget.Toast;

public class FolderSource extends PreferenceActivity
{
    private String key;
    private boolean need_clear;

    private SharedPreferences pref;
    private Preference path_pref;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

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
        order.setKey(order_key);
        order.setPersistent(true);
        order.setValue(order_val);

        // recursive
        String recursive_key = MultiPictureSetting.getKey(
            MultiPictureSetting.SCREEN_RECURSIVE_KEY, key);
        boolean recursive_val =
            (need_clear ? true : pref.getBoolean(recursive_key, true));

        CheckBoxPreference recursive = (CheckBoxPreference)
            getPreferenceManager().findPreference("recursive");
        recursive.setKey(recursive_key);
        recursive.setPersistent(true);
        recursive.setChecked(recursive_val);
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

        setFolderPath(path);
        need_clear = false;

        updatePathSummary();
    }

    @Override
    public void onBackPressed()
    {
        String path = getFolderPath();

        if(path != null) {
            Intent result = new Intent();
            result.putExtra(PictureSourceContract.EXTRA_DESCRIPTION,
                            getString(R.string.pref_screen_type_folder_desc,
                                      path));
            result.putExtra(PictureSourceContract.EXTRA_SERVICE_NAME,
                            new ComponentName(this, FolderPickService.class));

            setResult(RESULT_OK, result);
        }
        else {
            Toast.makeText(
                this, R.string.pref_no_folder_select_msg,
                Toast.LENGTH_LONG)
                .show();
            setResult(RESULT_CANCELED);
        }

        finish();
    }

    private void startFolderPickerActivity()
    {
        Intent intent = new Intent(getApplicationContext(),
                                   FolderPicker.class);

        String folder_val = getFolderPath();
        if(folder_val != null) {
            intent.putExtra(FolderPicker.EXTRA_INIT_PATH, folder_val);
        }

        startActivityForResult(intent, 0);
    }

    private void updatePathSummary()
    {
        String path_val = getFolderPath();
        path_pref.setSummary(
            getString(R.string.pref_folder_path_summary) +
            (path_val != null ?
             getString(R.string.pref_screen_val_summary, path_val) : ""));
    }

    private String getFolderPath()
    {
        return (need_clear ? null : pref.getString(getPreferenceKey(), null));
    }

    private void setFolderPath(String val)
    {
        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(getPreferenceKey(), val);
        editor.commit();
    }

    private String getPreferenceKey()
    {
        return String.format(MultiPictureSetting.SCREEN_FOLDER_KEY, key);
    }
}
