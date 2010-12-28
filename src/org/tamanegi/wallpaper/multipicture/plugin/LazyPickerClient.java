package org.tamanegi.wallpaper.multipicture.plugin;

import java.util.concurrent.atomic.AtomicBoolean;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

public abstract class LazyPickerClient
{
    private static final int STOP_TIMEOUT = 1000; // msec

    private Context context;
    private ComponentName comp;
    private String key;
    private ScreenInfo hint;

    private HandlerThread thread;
    private Handler handler;
    private Messenger receiver;
    private AtomicBoolean waiting_next;

    private ServiceConnection conn;
    private Messenger send_to;

    protected abstract void onReceiveNext(PictureContentInfo content);
    protected void onNotifyChanged() {}
    protected void onStartCompleted() {}
    protected void onStopCompleted() {}

    private Runnable timeout_stop_callback = new Runnable() {
            public void run() {
                // timed out: force unbind
                doStopCompleted();
            }
        };

    public LazyPickerClient(Context context,
                            ComponentName comp, String key, ScreenInfo hint)
    {
        this(context, comp, key, hint, null);
    }

    public LazyPickerClient(Context context,
                            ComponentName comp, String key, ScreenInfo hint,
                            Looper looper)
    {
        this.context = context;
        this.comp = comp;
        this.key = key;
        this.hint = hint;

        if(looper == null) {
            thread = new HandlerThread("LazyPickerClient");
            thread.start();
            looper = thread.getLooper();
        }
        handler = new Handler(looper, new MsgCallback());
        receiver = new Messenger(handler);
        waiting_next = new AtomicBoolean(false);
    }

    public void start()
    {
        Intent intent = new Intent(LazyPickService.SERVICE_INTERFACE);
        intent.setComponent(comp);
        conn = new Connection();
        context.bindService(intent, conn, Context.BIND_AUTO_CREATE);
    }

    public void sendStop()
    {
        sendMessage(Message.obtain(null, LazyPickService.MSG_STOP));
        handler.postDelayed(timeout_stop_callback, STOP_TIMEOUT);
    }

    public void sendGetNext()
    {
        if(waiting_next.getAndSet(true)) {
            return;
        }

        sendMessage(Message.obtain(null, LazyPickService.MSG_GET_NEXT));
    }

    private void sendMessage(final Message msg)
    {
        handler.post(new Runnable() {
                public void run() {
                    try {
                        if(send_to != null) {
                            send_to.send(msg);
                        }
                    }
                    catch(RemoteException e) {
                        // ignore
                    }
                }
            });
    }

    private void doConnected(final Messenger _send_to)
    {
        handler.post(new Runnable() {
                public void run() {
                    send_to = _send_to;
                    waiting_next.set(false);
                    try {
                        Message msg = Message.obtain(
                            null, LazyPickService.MSG_START);
                        msg.replyTo = receiver;

                        Bundle data = new Bundle();
                        data.putString(LazyPickService.DATA_KEY, key);
                        data.putBundle(LazyPickService.DATA_HINT,
                                       hint.foldToBundle());
                        msg.setData(data);

                        send_to.send(msg);
                    }
                    catch(RemoteException e) {
                        // ignore
                    }
                }
            });
    }

    private void doDisconnected()
    {
        handler.post(new Runnable() {
                public void run() {
                    send_to = null;
                }
            });
    }

    private void doStartCompleted()
    {
        onStartCompleted();
    }

    private void doStopCompleted()
    {
        handler.removeCallbacks(timeout_stop_callback);

        send_to = null;

        if(conn != null) {
            context.unbindService(conn);
            conn = null;
        }

        onStopCompleted();
    }

    private void doReceiveNext(PictureContentInfo content)
    {
        if(waiting_next.getAndSet(false)) {
            onReceiveNext(content);
        }
    }

    private void doNotifyChanged()
    {
        onNotifyChanged();
    }

    private void doFinish()
    {
        sendStop();
    }

    private class Connection implements ServiceConnection
    {
        public void onServiceConnected(ComponentName className, IBinder service)
        {
            doConnected(new Messenger(service));
        }

        public void onServiceDisconnected(ComponentName className)
        {
            doDisconnected();
        }
    }

    private class MsgCallback implements Handler.Callback
    {
        public boolean handleMessage(Message msg)
        {
            switch(msg.what) {
              case LazyPickService.MSG_START_COMPLETED:
                  doStartCompleted();
                  return true;

              case LazyPickService.MSG_STOP_COMPLETED:
                  doStopCompleted();
                  return true;

              case LazyPickService.MSG_RESULT_NEXT:
                  {
                      Bundle data = msg.getData();

                      PictureContentInfo info;
                      if(data != null) {
                          Bundle content = data.getBundle(
                              LazyPickService.DATA_NEXT_CONTENT);
                          info = (content != null ?
                                  PictureContentInfo.unfoldFromBundle(content) :
                                  null);
                      }
                      else {
                          info = null;
                      }
                      doReceiveNext(info);

                      return true;
                  }

              case LazyPickService.MSG_NOTIFY_CHANGED:
                  doNotifyChanged();
                  return true;

              case LazyPickService.MSG_FINISH:
                  doFinish();
                  return true;

              default:
                  return false;
            }
        }
    }
}
