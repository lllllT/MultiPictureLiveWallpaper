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

public abstract class LazyPickService extends Service
{
    // for lazy picker
    public static final String SERVICE_INTERFACE =
        "org.tamanegi.wallpaper.multipicture.plugin.LazyPickService";

    public static final int MSG_CREATE          = 0x00100000;
    public static final int MSG_RESULT_CREATE   = 0x00200000;
    public static final int MSG_START           = 0x00100010;
    public static final int MSG_START_COMPLETED = 0x00200010;
    public static final int MSG_STOP            = 0x00100020;
    public static final int MSG_STOP_COMPLETED  = 0x00200020;
    public static final int MSG_GET_NEXT        = 0x00100030;
    public static final int MSG_RESULT_NEXT     = 0x00200030;
    public static final int MSG_NOTIFY_CHANGED  = 0x00200040;
    public static final int MSG_FINISH          = 0x00200050;

    public static final String DATA_KEY = "key";
    public static final String DATA_HINT = "hint";

    // for reply of get next content
    public static final String DATA_NEXT_CONTENT = "nextContent";

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

    public abstract LazyPicker onCreateLazyPicker();

    public static abstract class LazyPicker
    {
        private LazyPickManager manager;

        public abstract PictureContentInfo getNext();

        protected void onStart(String key, ScreenInfo hint) {}
        protected void onStop() {}

        public final void notifyChanged()
        {
            if(manager != null) {
                manager.sendNotifyChanged();
            }
        }

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
                  {
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
                  }

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
