/**
 * Plugin interfaces for {@linkplain <a href="http://www.tamanegi.org/prog/android-apps/mplwp.html">MultiPicture Live Wallpaper</a>}.
 * <p>The plugin should implement at least following two components.</p>
 * <ul>
 *   <li>Activity that handles {@link org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract#ACTION_GET_PICTURE_SOURCE ACTION_GET_PICTURE_SOURCE}.</li>
 *   <li>Service that extends {@link org.tamanegi.wallpaper.multipicture.plugin.LazyPickService}</li>
 * </ul>
 *
 * <h3>ACTION_GET_PICTURE_SOURCE</h3>
 * {@link org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract#ACTION_GET_PICTURE_SOURCE ACTION_GET_PICTURE_SOURCE} action {@link android.content.Intent} will be called when configurating picture sources.
 * When user chooses picture source option, setting screen lists activities that can handle {@link org.tamanegi.wallpaper.multipicture.plugin.PictureSourceContract#ACTION_GET_PICTURE_SOURCE ACTION_GET_PICTURE_SOURCE}.
 * And it will be called when user select item.
 */
package org.tamanegi.wallpaper.multipicture.plugin;
