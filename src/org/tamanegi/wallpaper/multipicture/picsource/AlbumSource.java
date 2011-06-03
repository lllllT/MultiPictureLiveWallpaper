package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.Arrays;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.R;
import org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.view.View;

public class AlbumSource extends PreferenceActivity
{
    private String key;
    private boolean need_clear;

    private SharedPreferences pref;
    private PreferenceGroup album_group;

    private PictureUtils.BucketItem[] buckets;
    private boolean[] checked;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.button_preference_list);

        Intent intent = getIntent();
        need_clear = intent.getBooleanExtra(
            PictureSourceContract.EXTRA_CLEAR_PREVIOUS, true);
        key = intent.getStringExtra(PictureSourceContract.EXTRA_KEY);
        if(key == null) {
            finish();
        }

        addPreferencesFromResource(R.xml.album_pref);
        pref = PreferenceManager.getDefaultSharedPreferences(this);

        // prepare album list
        new AsyncPrepareList().execute();

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

        // rescan
        String rescan_key = MultiPictureSetting.getKey(
            MultiPictureSetting.SCREEN_RESCAN_KEY, key);
        boolean rescan_val =
            (need_clear ? true : pref.getBoolean(rescan_key, true));

        CheckBoxPreference rescan = (CheckBoxPreference)
            getPreferenceManager().findPreference("rescan");
        rescan.setChecked(rescan_val);
    }

    public void onButtonOk(View v)
    {
        new AsyncApplyBucketValue().execute();
    }

    public void onButtonCancel(View v)
    {
        setResult(RESULT_CANCELED);
        finish();
    }

    private class AsyncPrepareList
        extends AsyncTask<Void, Void, PictureUtils.BucketItem[]>
    {
        private ProgressDialog dlg;

        @Override
        protected void onPreExecute()
        {
            dlg = ProgressDialog.show(
                AlbumSource.this, null, getString(R.string.album_loading),
                true, false);
        }

        @Override
        protected PictureUtils.BucketItem[] doInBackground(Void... params)
        {
            return PictureUtils.getAvailBuckets(getContentResolver());
        }

        @Override
        protected void onPostExecute(PictureUtils.BucketItem[] result)
        {
            try {
                dlg.dismiss();

                buckets = result;
                if(buckets == null) {
                    new AlertDialog.Builder(AlbumSource.this)
                        .setTitle(R.string.pref_album_title)
                        .setMessage(R.string.pref_no_bucket_exist_msg)
                        .setPositiveButton(
                            android.R.string.ok,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                                    int button) {
                                    finish();
                                }
                            })
                        .setOnCancelListener(
                            new DialogInterface.OnCancelListener() {
                                public void onCancel(DialogInterface dialog) {
                                    finish();
                                }
                            })
                        .show();
                    return;
                }

                checked = new boolean[buckets.length];
                String bucket_key = MultiPictureSetting.getKey(
                    MultiPictureSetting.SCREEN_BUCKET_KEY, key);
                String bucket_val =
                    (need_clear ? null : pref.getString(bucket_key, null));
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

                // add album list
                album_group = (PreferenceGroup)
                    getPreferenceManager().findPreference("album_cat");

                for(int i = 0; i < buckets.length; i++) {
                    CheckBoxPreference check =
                        new CheckBoxPreference(AlbumSource.this);
                    check.setPersistent(false);
                    check.setTitle(buckets[i].getName());
                    check.setChecked(checked[i]);
                    album_group.addPreference(check);
                }
            }
            catch(Exception e) {
                // ignore
            }
        }
    }

    private class AsyncApplyBucketValue extends AsyncTask<Void, Void, Boolean>
    {
        private ProgressDialog dlg;

        @Override
        protected void onPreExecute()
        {
            dlg = ProgressDialog.show(
                AlbumSource.this, null, getString(R.string.album_saving),
                true, false);
        }

        @Override
        protected Boolean doInBackground(Void... params)
        {
            return applyBucketValue();
        }

        @Override
        protected void onPostExecute(Boolean result)
        {
            try {
                dlg.dismiss();

                if(result) {
                    finish();
                }
                else {
                    new AlertDialog.Builder(AlbumSource.this)
                        .setTitle(R.string.pref_album_title)
                        .setMessage(R.string.pref_no_bucket_select_msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                }
            }
            catch(Exception e) {
                // ignore
            }
        }
    }

    private boolean applyBucketValue()
    {
        boolean c = false;
        for(int i = 0; i < buckets.length; i++) {
            CheckBoxPreference check =
                (CheckBoxPreference)album_group.getPreference(i);
            checked[i] = check.isChecked();
            c = (c || checked[i]);
        }
        if(! c) {
            return false;
        }

        StringBuilder bucket_val = new StringBuilder();
        for(int i = 0; i < buckets.length; i++) {
            if(checked[i]) {
                bucket_val.append(buckets[i].getId()).append(" ");
            }
        }
        String bucket_val_str = bucket_val.toString().trim();

        ListPreference order = (ListPreference)
            getPreferenceManager().findPreference("order");
        String order_val = order.getValue();

        CheckBoxPreference rescan = (CheckBoxPreference)
            getPreferenceManager().findPreference("rescan");
        boolean rescan_val = rescan.isChecked();

        // save
        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(MultiPictureSetting.getKey(
                             MultiPictureSetting.SCREEN_BUCKET_KEY, key),
                         bucket_val_str);
        editor.putString(MultiPictureSetting.getKey(
                             MultiPictureSetting.SCREEN_ORDER_KEY, key),
                         order_val);
        editor.putBoolean(MultiPictureSetting.getKey(
                              MultiPictureSetting.SCREEN_RESCAN_KEY, key),
                          rescan_val);
        editor.commit();

        // activity result
        Intent result = new Intent();
        result.putExtra(PictureSourceContract.EXTRA_DESCRIPTION,
                        getString(R.string.pref_screen_type_bucket_desc,
                                  PictureUtils.getBucketNames(
                                      getContentResolver(), bucket_val_str)));
        result.putExtra(PictureSourceContract.EXTRA_SERVICE_NAME,
                        new ComponentName(this, AlbumPickService.class));

        setResult(RESULT_OK, result);
        return true;
    }
}
