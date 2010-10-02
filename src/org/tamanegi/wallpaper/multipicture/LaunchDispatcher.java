package org.tamanegi.wallpaper.multipicture;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public class LaunchDispatcher extends Activity
{
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Intent intent;
        WallpaperInfo info =
            WallpaperManager.getInstance(this).getWallpaperInfo();
        if(info != null &&
           getPackageName().equals(info.getPackageName()) &&
           info.getSettingsActivity() != null) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(info.getPackageName(),
                                info.getSettingsActivity());
        }
        else {
            intent = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
        }

        try {
            startActivity(intent);
        }
        catch(ActivityNotFoundException e) {
            if(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER.equals(
                   intent.getAction())) {
                Toast.makeText(this, R.string.lwp_not_supported,
                               Toast.LENGTH_SHORT)
                    .show();
            }
        }

        finish();
    }
}
