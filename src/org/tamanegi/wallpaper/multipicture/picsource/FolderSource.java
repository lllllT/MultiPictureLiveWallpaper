package org.tamanegi.wallpaper.multipicture.picsource;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.R;
import org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;

public class FolderSource extends Activity
{
    private String key;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        boolean need_clear =
            intent.getBooleanExtra(PictureSourceContract.EXTRA_CLEAR_PREVIOUS,
                                   true);
        key = intent.getStringExtra(PictureSourceContract.EXTRA_KEY);
        if(key == null) {
            finish();
        }

        startFolderPickerActivity(need_clear);
    }

    private void startFolderPickerActivity(boolean need_clear)
    {
        Intent intent = new Intent(getApplicationContext(),
                                   FolderPicker.class);

        if(! need_clear) {
            String folder_val = getFolderPath();
            if(folder_val != null) {
                intent.putExtra(FolderPicker.EXTRA_INIT_PATH, folder_val);
            }
        }

        startActivityForResult(intent, 0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data)
    {
        if(resultCode == RESULT_CANCELED || data == null) {
            finish();
            return;
        }

        String path = data.getStringExtra(FolderPicker.EXTRA_PATH);
        if(path == null) {
            finish();
            return;
        }

        setFolderPath(path);

        Intent result = new Intent();
        result.putExtra(PictureSourceContract.EXTRA_DESCRIPTION,
                        getString(R.string.pref_screen_type_folder_desc,
                                  path));
        result.putExtra(PictureSourceContract.EXTRA_SERVICE_NAME,
                        new ComponentName(this, FolderPickService.class));

        setResult(RESULT_OK, result);
        finish();
    }

    private String getFolderPath()
    {
        String val =
            PreferenceManager.getDefaultSharedPreferences(this).getString(
                getPreferenceKey(), null);
        return val;
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
