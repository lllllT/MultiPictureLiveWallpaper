package org.tamanegi.wallpaper.multipicture;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;

public class MultiPictureSetting extends PreferenceActivity
    implements Preference.OnPreferenceClickListener
{
    private static final int SCREEN_COUNT = 7;

    private Preference cur_pref = null;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.pref);

        for(int i = 0; i < SCREEN_COUNT; i++) {
            String key = "screen." + i + ".file";
            Preference pref = getPreferenceManager().findPreference(key);
            if(pref != null) {
                pref.setOnPreferenceClickListener(this);
            }
        }
    }

    @Override
    public boolean onPreferenceClick(Preference pref)
    {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, 0);

        cur_pref = pref;
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data)
    {
        if(cur_pref == null || resultCode == RESULT_CANCELED) {
            return;
        }

        String key = cur_pref.getKey();
        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(key, data.getData().toString());
        editor.commit();
    }
}
