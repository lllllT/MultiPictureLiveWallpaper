/**
 * Plugin interfaces for {@linkplain <a href="http://www.tamanegi.org/prog/android-apps/mplwp.html">MultiPicture Live Wallpaper</a>}.
 * <p>The plugin should implement at least following two components.</p>
 * <ul>
 *   <li>{@link android.app.Activity} that handles {@link org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract#ACTION_GET_PICTURE_SOURCE ACTION_GET_PICTURE_SOURCE}.</li>
 *   <li>{@link android.app.Service} that extends {@link org.tamanegi.wallpaper.multipicture.plugin.LazyPickService}</li>
 * </ul>
 *
 * <h3>ACTION_GET_PICTURE_SOURCE</h3>
 * <p>{@link org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract#ACTION_GET_PICTURE_SOURCE ACTION_GET_PICTURE_SOURCE} action {@link android.content.Intent} will be called when user is configurating plugin.</p>
 * <p>
 * When user chooses picture source option, setting screen lists activities that can handle {@link org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract#ACTION_GET_PICTURE_SOURCE ACTION_GET_PICTURE_SOURCE}.
 * And it will be called when user select item.
 * </p>
 * <p>
 * When setting screen starts plugin's setting {@link android.app.Activity}, {@link android.content.Intent} has some extras to specifies which and how configurates plugin.
 * </p>
 * <p>
 * If user confirm to use this plugin, {@link android.app.Activity} should return {@link android.content.ComponentName} of {@link org.tamanegi.wallpaper.multipicture.plugin.LazyPickService} and human readable description that will be show at preference screen.
 * </p>
 *
 * <h3>LazyPickService</h3>
 * <p>{@link org.tamanegi.wallpaper.multipicture.plugin.LazyPickService} will be bind when live wallpaper is shown.</p>
 * <p>
 * The plugin should extend this service and should implement {@link org.tamanegi.wallpaper.multipicture.plugin.LazyPickService#onCreateLazyPicker() onCreateLazyPicker()}.
 * {@code onCreateLazyPicker()} returns lazy picker object that extends {@link org.tamanegi.wallpaper.multipicture.plugin.LazyPickService.LazyPicker}.
 * </p>
 * <p>
 * {@ LazyPicker}
 * </p>
 */
package org.tamanegi.wallpaper.multipicture.plugin;
