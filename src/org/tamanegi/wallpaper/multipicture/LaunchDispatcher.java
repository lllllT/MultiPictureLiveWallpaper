package org.tamanegi.wallpaper.multipicture;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

public class LaunchDispatcher extends Activity
{
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        String msg = null;
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
            PackageManager pm = getPackageManager();

            intent = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
            if(pm.resolveActivity(intent, 0) != null) {
                msg = getString(R.string.chooser_navigation);
            }
            else {
                intent = Intent.createChooser(
                    new Intent(Intent.ACTION_SET_WALLPAPER),
                    getText(R.string.chooser_wallpaper));
                msg = getString(R.string.chooser_live_wallpaper);
            }
        }

        try {
            startActivity(intent);
        }
        catch(ActivityNotFoundException e) {
            if(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER.equals(
                   intent.getAction())) {
                msg = getString(R.string.lwp_not_supported);
            }
            else {
                msg = null;
            }
        }

        if(msg != null) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }

        finish();
    }
}
