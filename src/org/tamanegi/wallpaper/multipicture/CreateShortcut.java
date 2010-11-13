package org.tamanegi.wallpaper.multipicture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class CreateShortcut extends Activity
{
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Intent launch_intent = new Intent(
            getApplication(), LaunchDispatcher.class);

        Intent data = new Intent();
        data.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch_intent);
        data.putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.app_name));
        data.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                      Intent.ShortcutIconResource.fromContext(
                          getApplication(), R.drawable.icon));
        setResult(RESULT_OK, data);

        finish();
    }
}
