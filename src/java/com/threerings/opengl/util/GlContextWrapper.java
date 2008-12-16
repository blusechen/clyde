//
// $Id$

package com.threerings.opengl.util;

import com.threerings.config.ConfigManager;
import com.threerings.expr.DynamicScope;
import com.threerings.media.image.ColorPository;
import com.threerings.resource.ResourceManager;
import com.threerings.util.MessageManager;

import com.threerings.openal.ClipProvider;
import com.threerings.openal.SoundManager;
import com.threerings.opengl.GlApp;
import com.threerings.opengl.camera.CameraHandler;
import com.threerings.opengl.compositor.Compositor;
import com.threerings.opengl.renderer.Renderer;

/**
 * Wraps another {@link GlContext}, allowing subclasses to override methods selectively.
 */
public abstract class GlContextWrapper
    implements GlContext
{
    /**
     * Creates a new wrapper to wrap the specified context.
     */
    public GlContextWrapper (GlContext wrapped)
    {
        _wrapped = wrapped;
    }

    // documentation inherited from interface GlContext
    public GlApp getApp ()
    {
        return _wrapped.getApp();
    }

    // documentation inherited from interface AlContext, GlContext
    public DynamicScope getScope ()
    {
        return _wrapped.getScope();
    }

    // documentation inherited from interfaces AlContext, GlContext, EditorContext
    public ConfigManager getConfigManager ()
    {
        return _wrapped.getConfigManager();
    }

    // documentation inherited from interface AlContext
    public SoundManager getSoundManager ()
    {
        return _wrapped.getSoundManager();
    }

    // documentation inherited from interface AlContext
    public ClipProvider getClipProvider ()
    {
        return _wrapped.getClipProvider();
    }

    // documentation inherited from interface GlContext
    public void makeCurrent ()
    {
        _wrapped.makeCurrent();
    }

    // documentation inherited from interface GlContext
    public void setRenderer (Renderer renderer)
    {
        _wrapped.setRenderer(renderer);
    }

    // documentation inherited from interface GlContext
    public Renderer getRenderer ()
    {
        return _wrapped.getRenderer();
    }

    // documentation inherited from interface GlContext
    public Compositor getCompositor ()
    {
        return _wrapped.getCompositor();
    }

    // documentation inherited from interface GlContext
    public CameraHandler getCameraHandler ()
    {
        return _wrapped.getCameraHandler();
    }

    // documentation inherited from interface GlContext
    public ResourceManager getResourceManager ()
    {
        return _wrapped.getResourceManager();
    }

    // documentation inherited from interface GlContext
    public MessageManager getMessageManager ()
    {
        return _wrapped.getMessageManager();
    }

    // documentation inherited from interface GlContext
    public ColorPository getColorPository ()
    {
        return _wrapped.getColorPository();
    }

    // documentation inherited from interface GlContext
    public ImageCache getImageCache ()
    {
        return _wrapped.getImageCache();
    }

    // documentation inherited from interface GlContext
    public ShaderCache getShaderCache ()
    {
        return _wrapped.getShaderCache();
    }

    /** The wrapped context. */
    protected GlContext _wrapped;
}
