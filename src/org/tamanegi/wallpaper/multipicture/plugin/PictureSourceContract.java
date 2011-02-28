package org.tamanegi.wallpaper.multipicture.plugin;

public final class PictureSourceContract
{
    // to pick lazy picker
    /**
     * Activity Action: Start settings of plugin.
     * <br>
     * Input: {@link #EXTRA_KEY} and {@link #EXTRA_CLEAR_PREVIOUS}<br>
     * Output: {@link #EXTRA_DESCRIPTION} and {@link #EXTRA_SERVICE_NAME}<br>
     * Value: {@value}
     */
    public static final String ACTION_GET_PICTURE_SOURCE =
        "org.tamanegi.wallpaper.multipicture.action.GET_PICTURE_SOURCE";

    /**
     * Extra key of String value to indicate configuration key.
     * This extra value can be used to key of {@link android.content.SharedPreferences}.
     */
    public static final String EXTRA_KEY = "key";

    /**
     * Extra key of boolean value for previous settings.
     * {@code true} if settings should be reset even if previous settings exist.
     * {@code false} if settings should preserve previous settings.
     */
    public static final String EXTRA_CLEAR_PREVIOUS = "clearPrevious";

    public static final String EXTRA_DESCRIPTION = "description";
    public static final String EXTRA_SERVICE_NAME = "serviceName";
}
