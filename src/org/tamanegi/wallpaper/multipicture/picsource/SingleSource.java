package org.tamanegi.wallpaper.multipicture.picsource;

import java.io.File;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.R;
import org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.widget.Toast;

public class SingleSource extends Activity
{
    private String key;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        key = intent.getStringExtra(PictureSourceContract.EXTRA_KEY);
        if(key == null) {
            finish();
        }

        startFilePickerActivity();
    }

    private void startFilePickerActivity()
    {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");

        try {
            startActivityForResult(intent, 0);
        }
        catch(ActivityNotFoundException e) {
            Toast.makeText(
                this, R.string.gallery_not_found, Toast.LENGTH_SHORT)
                .show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data)
    {
        if(data == null) {
            finish();
            return;
        }

        Uri uri = data.getData();
        if(uri == null) {
            finish();
            return;
        }

        ContentResolver resolver = getContentResolver();
        if(ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try {
                Cursor cur = resolver.query(
                    uri, new String[] { MediaStore.MediaColumns.DATA },
                    null, null, null);
                if(cur != null) {
                    try {
                        if(cur.moveToFirst()) {
                            String path = cur.getString(0);
                            if(path != null) {
                                File file_path = new File(path);
                                if(file_path.isAbsolute()) {
                                    uri = Uri.fromFile(file_path);
                                }
                            }
                        }
                    }
                    finally {
                        cur.close();
                    }
                }
            }
            catch(Exception e) {
                // ignore
            }
        }

        setFilePath(uri);

        Intent result = new Intent();
        result.putExtra(PictureSourceContract.EXTRA_DESCRIPTION,
                        getString(R.string.pref_screen_type_file_desc,
                                  PictureUtils.getUriFileName(resolver, uri)));
        result.putExtra(PictureSourceContract.EXTRA_SERVICE_NAME,
                        new ComponentName(this, SinglePickService.class));

        setResult(RESULT_OK, result);
        finish();
    }

    private void setFilePath(Uri uri)
    {
        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(getPreferenceKey(), uri.toString());
        editor.commit();
    }

    private String getPreferenceKey()
    {
        return MultiPictureSetting.getKey(
            MultiPictureSetting.SCREEN_FILE_KEY, key);
    }
}
