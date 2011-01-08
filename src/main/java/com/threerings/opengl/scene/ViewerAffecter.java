//
// $Id$
//
// Clyde library - tools for developing networked games
// Copyright (C) 2005-2011 Three Rings Design, Inc.
// http://code.google.com/p/clyde/
//
// Redistribution and use in source and binary forms, with or without modification, are permitted
// provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this list of
//    conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright notice, this list of
//    conditions and the following disclaimer in the documentation and/or other materials provided
//    with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES,
// INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
// PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT,
// INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
// TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package com.threerings.opengl.scene;

import com.threerings.expr.Bound;
import com.threerings.expr.Scope;
import com.threerings.expr.Scoped;
import com.threerings.math.Box;
import com.threerings.math.Transform3D;

import com.threerings.opengl.model.Model;
import com.threerings.opengl.renderer.Color4f;
import com.threerings.opengl.scene.config.ViewerAffecterConfig;
import com.threerings.opengl.util.DebugBounds;
import com.threerings.opengl.util.GlContext;

/**
 * A model implementation that exerts an effect on the viewer.
 */
public class ViewerAffecter extends Model.Implementation
{
    /**
     * Creates a new affecter implementation.
     */
    public ViewerAffecter (GlContext ctx, Scope parentScope, ViewerAffecterConfig config)
    {
        super(parentScope);
        _ctx = ctx;
        setConfig(config);
    }

    /**
     * Sets the configuration of this model.
     */
    public void setConfig (ViewerAffecterConfig config)
    {
        _config = config;
        updateFromConfig();
    }

    @Override // documentation inherited
    public boolean hasCompleted ()
    {
        return _effect.hasCompleted();
    }

    @Override // documentation inherited
    public void reset ()
    {
        _effect.reset();
    }

    @Override // documentation inherited
    public int getInfluenceFlags ()
    {
        return _influenceFlags;
    }

    @Override // documentation inherited
    public Box getBounds ()
    {
        return _bounds;
    }

    @Override // documentation inherited
    public void updateBounds ()
    {
        // update the world transform
        if (_parentWorldTransform == null) {
            _worldTransform.set(_localTransform);
        } else {
            _parentWorldTransform.compose(_localTransform, _worldTransform);
        }

        // and the world bounds
        _config.extent.transformBounds(_worldTransform, _nbounds);
        if (!_bounds.equals(_nbounds)) {
            ((Model)_parentScope).boundsWillChange(this);
            _bounds.set(_nbounds);
            ((Model)_parentScope).boundsDidChange(this);

            // update the effect bounds if we're in a scene
            Scene scene = ((Model)_parentScope).getScene(this);
            if (scene != null) {
                scene.boundsWillChange(_effect);
            }
            _effect.getBounds().set(_nbounds);
            if (scene != null) {
                scene.boundsDidChange(_effect);
            }
        }
    }

    @Override // documentation inherited
    public void drawBounds ()
    {
        DebugBounds.draw(_bounds, Color4f.WHITE);
    }

    @Override // documentation inherited
    public void wasAdded ()
    {
        ((Model)_parentScope).getScene(this).add(_effect);
    }

    @Override // documentation inherited
    public void willBeRemoved ()
    {
        ((Model)_parentScope).getScene(this).remove(_effect);
    }

    /**
     * Updates the model to match its new or modified configuration.
     */
    protected void updateFromConfig ()
    {
        // remove the old effect, if any
        Scene scene = ((Model)_parentScope).getScene(this);
        if (scene != null && _effect != null) {
            scene.remove(_effect);
        }

        // update the influence flags
        _influenceFlags = _config.influences.getFlags();

        // create the effect
        _effect = _config.effect.getViewerEffect(_ctx, this, _effect);
        _effect.getBounds().set(_bounds);

        // add to scene if we're in one
        if (scene != null) {
            scene.add(_effect);
        }

        // update the bounds
        updateBounds();
    }

    /** The application context. */
    protected GlContext _ctx;

    /** The model configuration. */
    protected ViewerAffecterConfig _config;

    /** The effect. */
    protected ViewerEffect _effect;

    /** The parent world transform. */
    @Bound("worldTransform")
    protected Transform3D _parentWorldTransform;

    /** The local transform. */
    @Bound
    protected Transform3D _localTransform;

    /** The world transform. */
    @Scoped
    protected Transform3D _worldTransform = new Transform3D();

    /** Flags indicating which influences can affect the model. */
    protected int _influenceFlags;

    /** The bounds of the system. */
    @Scoped
    protected Box _bounds = new Box();

    /** Holds the bounds of the model when updating. */
    protected Box _nbounds = new Box();
}
