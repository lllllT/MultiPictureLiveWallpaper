package com.example.mplwp.sample;

import android.net.Uri;

import org.tamanegi.wallpaper.multipicture.plugin.LazyPickService;
import org.tamanegi.wallpaper.multipicture.plugin.PictureContentInfo;

public class SamplePluginService extends LazyPickService
{
    @Override
    public LazyPicker onCreateLazyPicker()
    {
        // create new instance of lazy picker for each screen
        return new SamplePluginLazyPicker();
    }

    // subclass of LazyPicker to pick picture content
    private static class SamplePluginLazyPicker extends LazyPicker
    {
        @Override
        public PictureContentInfo getNext()
        {
            // called when loading picture for each time

            // URI of picture
            Uri uri = Uri.parse("android.resource://android/drawable/platlogo");

            // orientation of picture: 0, 90, 180 or 270
            int orientation = 0;

            // return PictureContentInfo or null
            return new PictureContentInfo(uri, orientation);
        }
    }
}
