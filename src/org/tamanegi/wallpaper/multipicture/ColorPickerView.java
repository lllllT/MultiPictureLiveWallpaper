package org.tamanegi.wallpaper.multipicture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.util.FloatMath;
import android.view.MotionEvent;
import android.view.View;

public class ColorPickerView extends View
{
    public interface OnColorChangeListener
    {
        public void onColorChange(int color);
    }

    private int color;

    private float[] hsv = new float[3];
    private TouchingType touching = TouchingType.none;

    private Paint paint_ring;
    private Paint paint_rect;
    private Shader shader_ring;
    private float line_width;

    private OnColorChangeListener change_listener = null;

    private enum TouchingType {
        none, ring, rect
    }

    public ColorPickerView(Context context)
    {
        super(context);
        init(null);
    }

    public ColorPickerView(Context context, AttributeSet attrs)
    {
        super(context, attrs);
        init(attrs);
    }

    public ColorPickerView(Context context, AttributeSet attrs, int defStyle)
    {
        super(context, attrs, defStyle);
        init(attrs);
    }

    private void init(AttributeSet attrs)
    {
        int[] colors = {
            0xffff0000,
            0xffff00ff,
            0xff0000ff,
            0xff00ffff,
            0xff00ff00,
            0xffffff00,
            0xffff0000
        };
        shader_ring = new SweepGradient(0, 0, colors, null);

        line_width = getContext().getResources().getDimension(
            R.dimen.color_ring_width);

        paint_ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint_ring.setStyle(Paint.Style.STROKE);
        paint_ring.setShader(shader_ring);
        paint_ring.setStrokeWidth(line_width);

        paint_rect = new Paint();
        paint_rect.setStyle(Paint.Style.FILL);
        paint_rect.setDither(true);
    }

    @Override
    protected void onDraw(Canvas canvas)
    {
        int w = getWidth();
        int h = getHeight();
        float r = (Math.min(w, h) - line_width) / 2f;

        // ring
        canvas.save();
        canvas.translate(w / 2, h / 2);
        canvas.drawCircle(0, 0, r, paint_ring);
        canvas.restore();

        // rect
        float l = (r - line_width / 2) * FloatMath.sqrt(2) - 2;
        canvas.save();
        canvas.translate((w - l) / 2, (h - l) / 2);
        for(int i = 0; i < 100; i++) {
            float saturation = i / 99f;
            int c0 = Color.HSVToColor(new float[] { hsv[0], saturation, 0f });
            int c1 = Color.HSVToColor(new float[] { hsv[0], saturation, 1f });
            paint_rect.setShader(
                new LinearGradient(0, 0, l, 0, c0, c1, Shader.TileMode.CLAMP));
            canvas.drawRect(0, (1 - (i + 1) / 100f) * l,
                            l, (1 - i / 100f) * l,
                            paint_rect);
        }
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event)
    {
        float w2 = getWidth() / 2f;
        float h2 = getHeight() / 2f;

        float rmax = Math.min(w2, h2);
        float rmin = rmax - line_width;
        float rmin2 = rmin / FloatMath.sqrt(2);

        float x = event.getX() - w2;
        float y = event.getY() - h2;
        float r = FloatMath.sqrt(x * x + y * y);

        switch(event.getAction()) {
          case MotionEvent.ACTION_DOWN:
              if(rmin <= r && r <= rmax) {
                  touching = TouchingType.ring;
              }
              else if(-(rmin2 - 2) <= x && x <= (rmin2 - 2) &&
                      -(rmin2 - 2) <= y && y <= (rmin2 - 2)) {
                  touching = TouchingType.rect;
              }
              else {
                  touching = TouchingType.none;
              }
              pickColor(x, y, rmin2);
              break;

          case MotionEvent.ACTION_MOVE:
              pickColor(x, y, rmin2);
              break;

          case MotionEvent.ACTION_UP:
              pickColor(x, y, rmin2);
              touching = TouchingType.none;
              break;

          default:
              return false;
        }

        return true;
    }

    private void pickColor(float x, float y, float rmin2)
    {
        if(touching == TouchingType.ring) {
            float deg = (float)(Math.atan2(-y, x) / Math.PI);
            hsv[0] = (deg < 0 ? deg + 2 : deg) * 180;
            setColor(Color.HSVToColor(hsv), false);
        }
        else if(touching == TouchingType.rect) {
            hsv[1] = (-y + rmin2) / (rmin2 * 2);
            hsv[2] = (x + rmin2) / (rmin2 * 2);
            setColor(Color.HSVToColor(hsv), false);
        }
    }

    public int getColor()
    {
        return color;
    }

    public void setColor(int color)
    {
        setColor(color, true);
    }

    public void setColor(int color, boolean update_hsv)
    {
        this.color = color;
        if(update_hsv) {
            Color.colorToHSV(color, hsv);
        }

        invalidate();

        if(change_listener != null) {
            change_listener.onColorChange(color);
        }
    }

    public void setOnColorChangeListener(OnColorChangeListener listener)
    {
        this.change_listener = listener;
    }
}
