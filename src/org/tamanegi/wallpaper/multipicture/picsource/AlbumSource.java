package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.Arrays;

import org.tamanegi.wallpaper.multipicture.MultiPictureSetting;
import org.tamanegi.wallpaper.multipicture.R;
import org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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

        // prepare
        buckets = PictureUtils.getAvailBuckets(getContentResolver());
        if(buckets == null) {
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
            CheckBoxPreference check = new CheckBoxPreference(this);
            check.setPersistent(false);
            check.setTitle(buckets[i].getName());
            check.setChecked(checked[i]);
            album_group.addPreference(check);
        }

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
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        if(buckets == null) {
            new AlertDialog.Builder(this)
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
        }
    }

    public void onButtonOk(View v)
    {
        if(applyBucketValue()) {
            finish();
        }
    }

    public void onButtonCancel(View v)
    {
        setResult(RESULT_CANCELED);
        finish();
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
            new AlertDialog.Builder(this)
                .setTitle(R.string.pref_album_title)
                .setMessage(R.string.pref_no_bucket_select_msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
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

        // save
        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(MultiPictureSetting.getKey(
                             MultiPictureSetting.SCREEN_BUCKET_KEY, key),
                         bucket_val_str);
        editor.putString(MultiPictureSetting.getKey(
                             MultiPictureSetting.SCREEN_ORDER_KEY, key),
                         order_val);
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
