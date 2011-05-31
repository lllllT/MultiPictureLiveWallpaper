package org.tamanegi.gles;

import android.opengl.Matrix;

public class GLMatrix
{
    private float[] m;

    public GLMatrix()
    {
        m = new float[16];
        setIdentity();
    }

    public GLMatrix(GLMatrix mat)
    {
        this(mat.get());
    }

    public GLMatrix(float[] m)
    {
        this.m = new float[16];
        for(int i = 0; i < 16; i++) {
            this.m[i] = m[i];
        }
    }

    public float[] get()
    {
        return m;
    }

    public GLMatrix setIdentity()
    {
        Matrix.setIdentityM(m, 0);
        return this;
    }

    public GLMatrix setFrustum(float left, float right,
                               float bottom, float top,
                               float near, float far)
    {
        Matrix.frustumM(m, 0, left, right, bottom, top, near, far);
        return this;
    }

    public GLMatrix concat(GLMatrix mat)
    {
        float[] rm = new float[16];
        Matrix.multiplyMM(rm, 0, m, 0, mat.m, 0);
        m = rm;
        return this;
    }

    public GLMatrix translate(float x, float y, float z)
    {
        Matrix.translateM(m, 0, x, y, z);
        return this;
    }

    public GLMatrix rotate(float a, float x, float y, float z)
    {
        Matrix.rotateM(m, 0, a, x, y, z);
        return this;
    }

    public GLMatrix rotateX(float a)
    {
        return rotate(a, 1, 0, 0);
    }

    public GLMatrix rotateY(float a)
    {
        return rotate(a, 0, 1, 0);
    }

    public GLMatrix rotateZ(float a)
    {
        return rotate(a, 0, 0, 1);
    }

    public GLMatrix scale(float x, float y, float z)
    {
        Matrix.scaleM(m, 0, x, y, z);
        return this;
    }
}
