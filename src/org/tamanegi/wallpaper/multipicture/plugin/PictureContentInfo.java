package org.tamanegi.wallpaper.multipicture.plugin;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

/**
 * To specify content of picture.
 * @see LazyPickService.LazyPicker#getNext()
 */
public class PictureContentInfo
{
    private static final String DATA_URI = "uri";
    private static final String DATA_ORIENTATION = "orientation";

    private Uri uri;
    private int orientation;

    /**
     * Synonim of {@code PictureContentInfo(null, 0)}
     */
    public PictureContentInfo()
    {
        this(null, 0);
    }

    /**
     * Synonim of {@code PictureContentInfo(uri, 0)}
     * @param uri URI of picture content.
     */
    public PictureContentInfo(Uri uri)
    {
        this(uri, 0);
    }

    /**
     * Create with URI and orientation.
     * @param uri The URI of picture content.
     * @param orientation The orientation of picture.
     * It must be 0, 90, 180 or 270.
     */
    public PictureContentInfo(Uri uri, int orientation)
    {
        this.uri = uri;
        this.orientation = orientation;
    }

    /** Returns URI of picture content. */
    public Uri getUri()
    {
        return uri;
    }

    /** Set URI of picture content. */
    public void setUri(Uri uri)
    {
        this.uri = uri;
    }

    /** Returns orientation of picture content. */
    public int getOrientation()
    {
        return orientation;
    }

    /** Set orientation of picture content. */
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
