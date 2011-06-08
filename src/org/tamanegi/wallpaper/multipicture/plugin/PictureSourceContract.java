package org.tamanegi.wallpaper.multipicture.plugin;

/**
 * The contract between the live wallpaper and plugins.
 */
public final class PictureSourceContract
{
    // to pick lazy picker
    /**
     * Activity Action: Start settings of plugin.
     * <br>
     * Input: {@link #EXTRA_KEY} and {@link #EXTRA_CLEAR_PREVIOUS}<br>
     * Output: {@link #EXTRA_DESCRIPTION} and {@link #EXTRA_SERVICE_NAME}<br>
     * <br>
     * Value: {@value}
     */
    public static final String ACTION_GET_PICTURE_SOURCE =
        "org.tamanegi.wallpaper.multipicture.action.GET_PICTURE_SOURCE";

    /**
     * Extra key of {@link java.lang.String} value to indicate configuration key.
     * <br>
     * This extra value can be used to key of {@link android.content.SharedPreferences}.<br>
     * <br>
     * Value: {@value}
     */
    public static final String EXTRA_KEY = "key";

    /**
     * Extra key of {@code boolean} value to preserve previous settings.
     * <br>
     * {@code true} if settings should reset settings even if previous settings exist.<br>
     * {@code false} if settings should preserve previous settings.<br>
     * <br>
     * Value: {@value}
     */
    public static final String EXTRA_CLEAR_PREVIOUS = "clearPrevious";

    /**
     * Extra key of {@link java.lang.String} value for human readable description.
     * <br>
     * <br>
     * Value: {@value}
     */
    public static final String EXTRA_DESCRIPTION = "description";

    /**
     * Extra key of {@link android.content.ComponentName} value to indicate {@link org.tamanegi.wallpaper.multipicture.plugin.LazyPickService}.
     * <br>
     * <br>
     * Value: {@value}
     */
    public static final String EXTRA_SERVICE_NAME = "serviceName";
}
