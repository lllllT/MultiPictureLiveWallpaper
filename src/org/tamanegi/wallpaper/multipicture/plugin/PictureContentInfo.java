package org.tamanegi.wallpaper.multipicture.plugin;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

public class PictureContentInfo
{
    private static final String DATA_URI = "uri";
    private static final String DATA_ORIENTATION = "orientation";

    private Uri uri;
    private int orientation;

    public PictureContentInfo()
    {
        this(null, 0);
    }

    public PictureContentInfo(Uri uri)
    {
        this(uri, 0);
    }

    public PictureContentInfo(Uri uri, int orientation)
    {
        this.uri = uri;
        this.orientation = orientation;
    }

    public Uri getUri()
    {
        return uri;
    }

    public void setUri(Uri uri)
    {
        this.uri = uri;
    }

    public int getOrientation()
    {
        return orientation;
    }

    public void setOrientation(int orientation)
    {
        this.orientation = orientation;
    }

    Bundle foldToBundle()
    {
        Bundle data = new Bundle();

        data.putParcelable(DATA_URI, uri);
        data.putInt(DATA_ORIENTATION, orientation);

        return data;
    }

    static PictureContentInfo unfoldFromBundle(Bundle data)
    {
        PictureContentInfo info = new PictureContentInfo();

        Parcelable uri = data.getParcelable(DATA_URI);
        if(uri != null && (uri instanceof Uri)) {
            info.uri = (Uri)uri;
        }
        info.orientation = data.getInt(DATA_ORIENTATION, 0);

        return info;
    }
}
