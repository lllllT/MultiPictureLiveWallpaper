package org.tamanegi.gles;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.opengl.GLU;
import android.opengl.GLUtils;
import android.view.SurfaceHolder;

public class GLCanvas
{
    private static final int EGL_CONFIG_ATTRS[] = {
        EGL10.EGL_DEPTH_SIZE, 16,               // depth: at least 16bits
        EGL10.EGL_NONE,                         // end of list
    };

    private static final int FLOAT_SIZE = Float.SIZE / Byte.SIZE;
    private static final int SHORT_SIZE = Short.SIZE / Byte.SIZE;

    private SurfaceHolder holder;
    private float wratio = 1;
    private float rect_width = 1;
    private float rect_height = 1;

    private EGL10 egl = null;
    private EGLDisplay egl_display = null;
    private EGLConfig egl_config = null;
    private EGLContext egl_context = null;
    private EGLSurface egl_surface = null;

    private GL10 gl = null;

    private FloatBuffer vertex_list;
    private FloatBuffer tex_list;
    private ShortBuffer index_list;

    public GLCanvas()
    {
        vertex_list = ByteBuffer.allocateDirect(FLOAT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        tex_list = ByteBuffer.allocateDirect(FLOAT_SIZE * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        index_list = ByteBuffer.allocateDirect(SHORT_SIZE * 3 * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer();
    }

    public void setSurface(SurfaceHolder holder, int width, int height)
    {
        this.holder = holder;

        wratio = (float)width / height;
        rect_width = (width - 1f) / width;
        rect_height = (height - 1f) / height;

        if(egl != null) {
            destroyGLContext();
            initGLContext();
        }
        else {
            initGL();
        }

        if(gl != null) {
            initState();
            gl.glViewport(0, 0, width, height);
        }
    }

    private void initGL()
    {
        egl = (EGL10)EGLContext.getEGL();

        egl_display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if(egl_display == EGL10.EGL_NO_DISPLAY) {
            egl_display = null;
            return;
        }

        if(! egl.eglInitialize(egl_display, null)) {
            return;
        }

        int nconf[] = new int[1];
        if(! egl.eglChooseConfig(egl_display, EGL_CONFIG_ATTRS,
                                 null, 0, nconf)) {
            return;
        }

        int conf_cnt = nconf[0];
        if(conf_cnt <= 0) {
            return;
        }

        EGLConfig configs[] = new EGLConfig[conf_cnt];
        if(! egl.eglChooseConfig(egl_display, EGL_CONFIG_ATTRS,
                                 configs, conf_cnt, nconf)) {
            return;
        }

        egl_config = configs[0];

        initGLContext();
    }

    private int getConfigAttrib(EGLConfig config, int attr, int defval)
    {
        int val[] = new int[1];
        if(egl.eglGetConfigAttrib(egl_display, config, attr, val)) {
            return val[0];
        }

        return defval;
    }

    private void initGLContext()
    {
        egl_context = egl.eglCreateContext(
            egl_display, egl_config, EGL10.EGL_NO_CONTEXT, null);
        if(egl_context == EGL10.EGL_NO_CONTEXT) {
            egl_context = null;
            return;
        }

        try {
            egl_surface = egl.eglCreateWindowSurface(
                egl_display, egl_config, holder, null);
        }
        catch(Exception e) {
            // ignore
            egl_surface = EGL10.EGL_NO_SURFACE;
        }
        if(egl_surface == EGL10.EGL_NO_SURFACE) {
            egl_surface = null;
            return;
        }

        if(! egl.eglMakeCurrent(
               egl_display, egl_surface, egl_surface, egl_context)) {
            return;
        }

        gl = (GL10)egl_context.getGL();
    }

    private void initState()
    {
        if(gl == null) {
            return;
        }

        gl.glDisable(GL10.GL_TEXTURE_2D);
        gl.glDisable(GL10.GL_CULL_FACE);

        gl.glDisable(GL10.GL_DEPTH_TEST);
        gl.glDepthMask(false);

        gl.glEnable(GL10.GL_DITHER);
        gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST);
        gl.glShadeModel(GL10.GL_SMOOTH);

        gl.glEnable(GL10.GL_LINE_SMOOTH);
        gl.glHint(GL10.GL_LINE_SMOOTH_HINT, GL10.GL_NICEST);

        gl.glEnable(GL10.GL_BLEND);
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);

        // projection matrix
        gl.glMatrixMode(GL10.GL_PROJECTION);
        gl.glLoadIdentity();
        gl.glFrustumf(-wratio / 4, wratio / 4,
                      -1f / 4, 1f / 4,
                      1, 128);

        // model-view matrix
        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glLoadIdentity();
        GLU.gluLookAt(gl,
                      0, 0, 4,
                      0, 0, 0,
                      0, 1, 0);

        // texture matrix
        gl.glMatrixMode(GL10.GL_TEXTURE);
        gl.glLoadIdentity();

        // array of vertex, coord, index
        float[] vertex_coords = {
            //    x,   y, z,
            -wratio, +1f, 0,
            +wratio, +1f, 0,
            +wratio, -1f, 0,
            -wratio, -1f, 0,
        };
        float[] tex_coords = {
            // u, v,
            0, 0,
            1, 0,
            1, 1,
            0, 1,
        };
        short[] indexes = {
            0, 1, 2,
            2, 3, 0,
        };

        vertex_list.put(vertex_coords).position(0);
        tex_list.put(tex_coords).position(0);
        index_list.put(indexes).position(0);
    }

    public void terminateGL()
    {
        if(egl_display != null) {
            destroyGLContext();

            egl.eglTerminate(egl_display);
            egl_display = null;
        }
    }

    private void destroyGLContext()
    {
        egl.eglMakeCurrent(egl_display,
                           EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE,
                           EGL10.EGL_NO_CONTEXT);

        if(egl_surface != null) {
            egl.eglDestroySurface(egl_display, egl_surface);
            egl_surface = null;
        }

        if(egl_context != null) {
            egl.eglDestroyContext(egl_display, egl_context);
            egl_context = null;
        }

        gl = null;
    }

    public int genTexture(Bitmap bmp)
    {
        if(gl == null) {
            return 0;
        }

        gl.glEnable(GL10.GL_TEXTURE_2D);

        int[] textures = new int[1];
        gl.glGenTextures(1, textures, 0);
        int tex_id = textures[0];

        gl.glActiveTexture(GL10.GL_TEXTURE0);
        gl.glBindTexture(GL10.GL_TEXTURE_2D, tex_id);

        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER,
                           GL10.GL_LINEAR);
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER,
                           GL10.GL_LINEAR);

        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S,
                           GL10.GL_CLAMP_TO_EDGE);
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T,
                           GL10.GL_CLAMP_TO_EDGE);

        gl.glTexEnvf(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_MODE,
                     GL10.GL_REPLACE);

        GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, bmp, 0);

        gl.glDisable(GL10.GL_TEXTURE_2D);

        return tex_id;
    }

    public void deleteTexture(int tex_id)
    {
        if(gl == null) {
            return;
        }

        int[] textures = { tex_id };
        gl.glDeleteTextures(1, textures, 0);
    }

    public void setClipRect(GLMatrix mat, RectF clip_rect)
    {
        if(gl == null) {
            return;
        }

        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glTranslatef(clip_rect.centerX(), clip_rect.centerY(), 0);
        gl.glScalef(Math.abs(clip_rect.width()) / (2 * wratio),
                    Math.abs(clip_rect.height()) / 2, 1);
        gl.glMultMatrixf(mat.get(), 0);
        gl.glTranslatef(0, 0, 0.01f);

        // using depth instead of stencil: some devices has no stencil??
        gl.glEnable(GL10.GL_DEPTH_TEST);

        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vertex_list);

        gl.glColorMask(false, false, false, false);
        gl.glDepthMask(true);
        gl.glDepthFunc(GL10.GL_LESS);

        gl.glClear(GL10.GL_DEPTH_BUFFER_BIT);
        gl.glColor4f(1, 1, 1, 1);
        gl.glDrawElements(GL10.GL_TRIANGLES, 6,
                          GL10.GL_UNSIGNED_SHORT, index_list);

        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);

        gl.glColorMask(true, true, true, true);
        gl.glDepthMask(false);
        gl.glDepthFunc(GL10.GL_GEQUAL);

        gl.glPopMatrix();
    }

    public void clearClipRect()
    {
        if(gl == null) {
            return;
        }

        gl.glDisable(GL10.GL_DEPTH_TEST);
    }

    public void drawColor(GLColor color)
    {
        if(gl == null) {
            return;
        }

        gl.glClearColor(color.red, color.green, color.blue, color.alpha);
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT);
    }

    public void drawRect(GLMatrix mat, GLColor fill_color, GLColor border_color)
    {
        if(gl == null) {
            return;
        }

        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glMultMatrixf(mat.get(), 0);

        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vertex_list);

        // fill
        if(fill_color != null) {
            gl.glColor4f(
                fill_color.red, fill_color.green, fill_color.blue,
                fill_color.alpha);
            gl.glDrawElements(GL10.GL_TRIANGLES, 6,
                              GL10.GL_UNSIGNED_SHORT, index_list);
        }

        // border
        if(border_color != null) {
            gl.glScalef(rect_width, rect_height, 0);
            gl.glColor4f(
                border_color.red, border_color.green, border_color.blue,
                border_color.alpha);
            gl.glDrawArrays(GL10.GL_LINE_LOOP, 0, 4);
        }

        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);

        gl.glPopMatrix();
    }

    public void drawTexture(GLMatrix mat, int tex_id,
                            float sratio, float tratio, float alpha, float fade)
    {
        if(gl == null) {
            return;
        }

        gl.glMatrixMode(GL10.GL_TEXTURE);
        gl.glPushMatrix();
        gl.glScalef(sratio, tratio, 1);

        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glMultMatrixf(mat.get(), 0);

        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vertex_list);

        gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
        gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, tex_list);

        // draw texture
        gl.glEnable(GL10.GL_TEXTURE_2D);
        gl.glTexEnvx(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_MODE,
                     GL10.GL_MODULATE);
        gl.glActiveTexture(GL10.GL_TEXTURE0);
        gl.glBindTexture(GL10.GL_TEXTURE_2D, tex_id);
        gl.glColor4f(fade, fade, fade, alpha);

        gl.glDrawElements(GL10.GL_TRIANGLES, 6,
                          GL10.GL_UNSIGNED_SHORT, index_list);

        gl.glDisable(GL10.GL_TEXTURE_2D);
        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY);

        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glPopMatrix();

        gl.glMatrixMode(GL10.GL_TEXTURE);
        gl.glPopMatrix();
    }

    public boolean swap()
    {
        if(egl == null || egl_display == null || egl_surface == null) {
            return true;
        }

        if(! egl.eglSwapBuffers(egl_display, egl_surface)) {
            if(egl.eglGetError() == EGL11.EGL_CONTEXT_LOST) {
                destroyGLContext();
                initGLContext();
                return false;
            }
        }

        return true;
    }

    public int getMaxTextureSize()
    {
        if(gl == null) {
            return 2;
        }

        int val[] = new int[1];
        val[0] = 2;
        gl.glGetIntegerv(GL10.GL_MAX_TEXTURE_SIZE, val, 0);
        return val[0];
    }
}
