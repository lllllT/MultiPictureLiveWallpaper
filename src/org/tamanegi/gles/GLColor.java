package org.tamanegi.gles;

import android.graphics.Color;

public class GLColor
{
    public float red;
    public float green;
    public float blue;
    public float alpha;

    public GLColor()
    {
        this(0, 0, 0, 1);
    }

    public GLColor(GLColor color)
    {
        this(color.red, color.green, color.blue, color.alpha);
    }

    public GLColor(float red, float green, float blue, float alpha)
    {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    public GLColor(int argb)
    {
        this(Color.red(argb) / (float)0xff,
             Color.green(argb) / (float)0xff,
             Color.blue(argb) / (float)0xff,
             Color.alpha(argb) / (float)0xff);
    }

    public GLColor setAlpha(float a)
    {
        alpha = a;
        return this;
    }
}
