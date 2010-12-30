package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.Arrays;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.R;
import org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.widget.Toast;

public class AlbumSource extends Activity
{
    private String key;
    private boolean need_clear;

    private PictureUtils.BucketItem[] buckets;
    private boolean[] checked;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        need_clear = intent.getBooleanExtra(
            PictureSourceContract.EXTRA_CLEAR_PREVIOUS, true);
        key = intent.getStringExtra(PictureSourceContract.EXTRA_KEY);
        if(key == null) {
            finish();
        }
    }

    @Override
    protected void onResume()
    {
        startBucketPickerDialog(need_clear);
    }

    private void startBucketPickerDialog(boolean need_clear)
    {
        buckets = PictureUtils.getAvailBuckets(getContentResolver());
        if(buckets == null) {
            Toast.makeText(
                this, R.string.pref_screen_no_bucket_exist_msg,
                Toast.LENGTH_LONG)
                .show();
            finish();
            return;
        }

        checked = new boolean[buckets.length];
        String bucket_val = getBuckets();
        if(! need_clear && bucket_val != null) {
            String[] val_ids = bucket_val.split(" ");
            for(int i = 0; i < buckets.length; i++) {
                for(String val : val_ids) {
                    if(val.equals(buckets[i].getId())) {
                        checked[i] = true;
                        break;
                    }
                }
            }
        }
        else {
            Arrays.fill(checked, true);
        }

        AlertDialog dlg = new AlertDialog.Builder(this)
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
                        applyBucketValue();
                        dialog.dismiss();
                    }
                })
            .setNegativeButton(
                android.R.string.no,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int button) {
                        dialog.dismiss();
                    }
                })
            .create();
        dlg.setOnDismissListener(
            new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface dialog) {
                    finish();
                }
            });
        dlg.show();
    }

    private void applyBucketValue()
    {
        boolean c = false;
        for(boolean check : checked) {
            c = (c || check);
        }
        if(! c) {
            Toast.makeText(
                this, R.string.pref_screen_no_bucket_select_msg,
                Toast.LENGTH_LONG)
                .show();
            return;
        }

        StringBuilder data_val = new StringBuilder();
        for(int i = 0; i < buckets.length; i++) {
            if(checked[i]) {
                data_val.append(buckets[i].getId()).append(" ");
            }
        }

        String val_str = data_val.toString().trim();
        setBuckets(val_str);

        // todo: save order

        Intent result = new Intent();
        result.putExtra(PictureSourceContract.EXTRA_DESCRIPTION,
                        getString(R.string.pref_screen_type_bucket_desc,
                                  PictureUtils.getBucketNames(
                                      getContentResolver(), val_str)));
        result.putExtra(PictureSourceContract.EXTRA_SERVICE_NAME,
                        new ComponentName(this, AlbumPickService.class));

        setResult(RESULT_OK, result);
    }

    private String getBuckets()
    {
        String val =
            PreferenceManager.getDefaultSharedPreferences(this).getString(
                getPreferenceKey(), null);
        return val;
    }

    private void setBuckets(String val)
    {
        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(getPreferenceKey(), val);
        // todo: persist for select order
        editor.commit();
    }

    private String getPreferenceKey()
    {
        return String.format(MultiPictureSetting.SCREEN_BUCKET_KEY, key);
    }
}
