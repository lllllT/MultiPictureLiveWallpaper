package org.tamanegi.wallpaper.multipicture.plugin;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

/**
 * The base class of plugin service.
 * <br>
 * Plugin should extend this class and implements {@link #onCreateLazyPicker()}.
 */
public abstract class LazyPickService extends Service
{
    // for lazy picker
    /**
     * Plugin service should handle {@link Intent} which has this action.
     * <br>
     * <br>
     * Value: {@value}
     */
    public static final String SERVICE_INTERFACE =
        "org.tamanegi.wallpaper.multipicture.plugin.LazyPickService";

    static final int MSG_CREATE          = 0x00100000;
    static final int MSG_RESULT_CREATE   = 0x00200000;
    static final int MSG_START           = 0x00100010;
    static final int MSG_START_COMPLETED = 0x00200010;
    static final int MSG_STOP            = 0x00100020;
    static final int MSG_STOP_COMPLETED  = 0x00200020;
    static final int MSG_GET_NEXT        = 0x00100030;
    static final int MSG_RESULT_NEXT     = 0x00200030;
    static final int MSG_NOTIFY_CHANGED  = 0x00200040;
    static final int MSG_FINISH          = 0x00200050;

    static final String DATA_KEY = "key";
    static final String DATA_HINT = "hint";

    // for reply of get next content
    static final String DATA_NEXT_CONTENT = "nextContent";

    private Messenger main_messenger;

    @Override
    public void onCreate()
    {
        super.onCreate();

        main_messenger = new Messenger(new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if(msg.what == MSG_CREATE) {
                        handleCreateMessage(msg);
                    }
                    else {
                        super.handleMessage(msg);
                    }
                }
            });
    }

    @Override
    public final IBinder onBind(Intent intent)
    {
        if(SERVICE_INTERFACE.equals(intent.getAction())) {
            return main_messenger.getBinder();
        }

        return null;
    }

    /**
     * Should be implemented to return a new instance of the plugin's lazy picker.
     * <br>
     * Returned instance correspond to each screen one by one.
     * So, if user configures to use same plugin for multiple screens,
     * multiple instance may be active at the same time.
     *
     * @return The LazyPicker object for each screen
     */
    public abstract LazyPicker onCreateLazyPicker();

    /**
     * The main implementation of plugin.
     *
     * <h3>LazyPicker Lifecycle</h3>
     * <p>
     * Following methods will be called from same thread,
     * and the thread is separated for each LazyPicker instance.
     * </p>
     * <ul>
     * <li>{@link #onStart(String,ScreenInfo)} will be called at first and only once.</li>
     * <li>
     * {@link #getNext()} will be called when next picture content required.
     * This may be called multiple times if needed, such as
     * user double taps to reload pictures or time interval of reload.
     * </li>
     * <li>{@link #onStop()} will be called when plugin no longer used.</li>
     * </ul>
     */
    public static abstract class LazyPicker
    {
        private LazyPickManager manager;

        /**
         * Should be implemented to return a picture content to show as wallpaper.
         * <br>
         * The URI of content should be one of following schemes:
         * <ul>
         * <li>content</li>
         * <li>file</li>
         * <li>android.resource</li>
         * </ul>
         *
         * @return The picture content for wallpaper.<br>
         * If returns not {@code null}, content's URI will be used.<br>
         * If returns {@code null}, previously returned content will be used.
         *
         * @see android.content.ContentResolver#openInputStream(Uri)
         */
        public abstract PictureContentInfo getNext();

        /**
         * Called when lazy picker is starting.
         * <br>
         * The plugin can initialize in this method.
         * {@code key} parameter is unique for each settings,
         * and its value is same as {@link PictureSourceContract#EXTRA_KEY} extra value when setting activity called.
         * The plugin can use this {@code key} for {@link android.content.SharedPreferences}'s key.
         *
         * @param key The configuration specific key.
         * @param hint The screen specific hint.
         */
        protected void onStart(String key, ScreenInfo hint) {}

        /**
         * Called when lazy picker is stopping.
         * <br>
         * The plugin should release all resources related to picker.
         */
        protected void onStop() {}

        /**
         * Notify to live wallpaper about picture content is changed.
         * {@link #getNext()} will be called later
         * if plugin calls {@code notifyChanged()}.<br>
         * NOTE: {@code notifyChanged()} and {@code getNext()} may or may not paired.
         * For example, when calling {@code notifyChanged()} twice, {@code getNext()} will be called once, twice or more.
         * Because {@code getNext()} is only called when reloading,
         * and reloading is caused only when wallpaper is visible.
         * The reloading is also caused by user operation.
         */
        public final void notifyChanged()
        {
            if(manager != null) {
                manager.sendNotifyChanged();
            }
        }

        /**
         * Notify to live wallpaper that the lazy picker is no longer need to work.
         * {@link #onStop()} will be called later.
         */
        public final void finish()
        {
            if(manager != null) {
                manager.sendFinish();
            }
        }

        private void doStart(String key, ScreenInfo hint, LazyPickManager mgr)
        {
            this.manager = mgr;
            onStart(key, hint);
        }

        private void doStop()
        {
            onStop();
            manager = null;
        }
    }

    private void handleCreateMessage(Message msg)
    {
        if(msg.replyTo == null) {
            return;
        }

        LazyPickManager mgr = new LazyPickManager(onCreateLazyPicker());

        Message rep = Message.obtain(null, MSG_RESULT_CREATE);
        rep.replyTo = mgr.getMessenger();

        try {
            msg.replyTo.send(rep);
        }
        catch(RemoteException e) {
            // ignore
        }
    }

    private static class LazyPickManager implements Handler.Callback
    {
        private HandlerThread thread;
        private Handler handler;
        private LazyPicker picker;
        private Messenger reply_to = null;

        private LazyPickManager(LazyPicker picker)
        {
            this.picker = picker;

            thread = new HandlerThread("LazyPickManager");
            thread.start();
            handler = new Handler(thread.getLooper(), this);
        }

        private Messenger getMessenger()
        {
            return new Messenger(handler);
        }

        public boolean handleMessage(Message msg)
        {
            switch(msg.what) {
              case MSG_START:
                  synchronized(this) {
                      reply_to = msg.replyTo;
                  }

                  if(picker != null) {
                      Bundle data = msg.getData();
                      String key = data.getString(DATA_KEY);
                      Bundle info = data.getBundle(DATA_HINT);
                      ScreenInfo hint = ScreenInfo.unfoldFromBundle(info);

                      picker.doStart(key, hint, this);
                  }
                  sendStartCompleted();

                  return true;

              case MSG_STOP:
                  synchronized(this) {
                      if(reply_to == null) {
                          // not yet started
                          return true;
                      }
                  }

                  if(picker != null) {
                      picker.doStop();
                      picker = null;
                  }
                  sendStopCompleted();

                  synchronized(this) {
                      reply_to = null;
                  }

                  return true;

              case MSG_GET_NEXT:
                  PictureContentInfo content = null;
                  if(picker != null) {
                      content = picker.getNext();
                  }

                  sendResultNext(content);
                  return true;

              default:
                  return false;
            }
        }

        private synchronized void sendReply(Message msg)
        {
            if(reply_to != null) {
                try {
                    reply_to.send(msg);
                }
                catch(RemoteException e) {
                    // ignore
                }
            }
        }

        private void sendResultNext(PictureContentInfo content)
        {
            Message msg = Message.obtain(null, MSG_RESULT_NEXT);

            Bundle data = new Bundle();
            if(content != null) {
                data.putBundle(DATA_NEXT_CONTENT, content.foldToBundle());
            }
            msg.setData(data);

            sendReply(msg);
        }

        private void sendStartCompleted()
        {
            sendReply(Message.obtain(null, MSG_START_COMPLETED));
        }

        private void sendNotifyChanged()
        {
            sendReply(Message.obtain(null, MSG_NOTIFY_CHANGED));
        }

        private void sendFinish()
        {
            sendReply(Message.obtain(null, MSG_FINISH));
        }

        private void sendStopCompleted()
        {
            sendReply(Message.obtain(null, MSG_STOP_COMPLETED));
        }
    }
}
