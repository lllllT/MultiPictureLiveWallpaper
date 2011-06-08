package com.example.mplwp.sample;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

import org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract;

public class SamplePluginSource extends Activity
{
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        // get parameters from Intent
        Intent intent = getIntent();

        // configuration key for SharedPreferences
        String key = intent.getStringExtra(PictureSourceContract.EXTRA_KEY);

        // clear or preserve previous settings
        boolean clear_prev = intent.getBooleanExtra(
            PictureSourceContract.EXTRA_CLEAR_PREVIOUS, true);

        // show some setting screen

        // when returning from Activity, set result
        Intent result = new Intent();

        // description
        result.putExtra(PictureSourceContract.EXTRA_DESCRIPTION,
                        "setting description to show user");

        // service name of main picker
        result.putExtra(PictureSourceContract.EXTRA_SERVICE_NAME,
                        new ComponentName(this, SamplePluginService.class));

        // set RESULT_OK to apply settings, RESULT_CANCELED to cancel
        setResult(RESULT_OK, result);
        finish();
    }
}
