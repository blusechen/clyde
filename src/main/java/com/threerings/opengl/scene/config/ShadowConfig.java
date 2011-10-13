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

package com.threerings.opengl.scene.config;

import java.util.ArrayList;

import com.threerings.config.ConfigReference;
import com.threerings.editor.Editable;
import com.threerings.editor.EditorTypes;
import com.threerings.export.Exportable;
import com.threerings.expr.Scope;
import com.threerings.expr.Scoped;
import com.threerings.expr.Updater;
import com.threerings.math.Box;
import com.threerings.math.FloatMath;
import com.threerings.math.Matrix3f;
import com.threerings.math.Matrix4f;
import com.threerings.math.Quaternion;
import com.threerings.math.Transform3D;
import com.threerings.math.Vector3f;
import com.threerings.math.Vector4f;
import com.threerings.util.DeepObject;

import com.threerings.opengl.compositor.RenderScheme;
import com.threerings.opengl.material.Projection;
import com.threerings.opengl.material.config.MaterialConfig;
import com.threerings.opengl.material.config.TechniqueConfig;
import com.threerings.opengl.renderer.Light;
import com.threerings.opengl.renderer.config.ColorStateConfig;
import com.threerings.opengl.scene.LightInfluence;
import com.threerings.opengl.scene.SceneInfluence;
import com.threerings.opengl.util.GlContext;

/**
 * Represents a means of generating shadows from a light.
 */
@EditorTypes({ ShadowConfig.SilhouetteTexture.class })
public abstract class ShadowConfig extends DeepObject
    implements Exportable
{
    /**
     * Generates shadows by rendering the silhouettes of shadow casters from the perspective
     * of the light into a texture and projecting that texture onto shadow receivers.
     */
    public static class SilhouetteTexture extends ShadowConfig
    {
        /** The distance to the near clip plane. */
        @Editable(min=0.0, step=0.01, hgroup="f")
        public float near = 1f;

        /** The distance to the far clip plane. */
        @Editable(min=0.0, step=0.01, hgroup="f")
        public float far = 100f;

        /** The projection material. */
        @Editable(nullable=true)
        public ConfigReference<MaterialConfig> material;

        /** The color state for the projection. */
        @Editable(nullable=true)
        public ColorStateConfig colorState = new ColorStateConfig();

        @Override // documentation inherited
        public SceneInfluence createInfluence (
            final GlContext ctx, Scope scope, final Light light, ArrayList<Updater> updaters)
        {
            MaterialConfig mconfig = ctx.getConfigManager().getConfig(
                MaterialConfig.class, material);
            Light.Type lightType = light.getType();
            TechniqueConfig technique = (mconfig == null) ?
                null : mconfig.getTechnique(ctx, getProjectionScheme(lightType));
            if (technique == null) {
                return new LightInfluence(light);
            }
            final Projection projection = new Projection(technique,
                (colorState == null) ? null : colorState.getState()) {
                @Scoped protected Light _light = light;
                @Scoped protected float _near = near;
                @Scoped protected float _far = far;
            };
            if (lightType == Light.Type.DIRECTIONAL) {
                updaters.add(new Updater() {
                    public void update () {
                        // project the corners of the frustum onto the light plane
                        // and find their extents in s/t
                        Vector4f pos = light.position;
                        _rot.fromVectorFromNegativeZ(-pos.x, -pos.y, -pos.z).invertLocal();
                        _mat.setToRotation(_rot);
                        ctx.getCompositor().getCamera().getLocalVolume().getBoundsUnderRotation(
                            _mat, _box);
                        Vector3f min = _box.getMinimumExtent();
                        Vector3f max = _box.getMaximumExtent();
                        float ss = 1f / (max.x - min.x);
                        projection.getGenPlaneS().set(
                            ss*_mat.m00, ss*_mat.m10, ss*_mat.m20, -ss*min.x);
                        float ts = 1f / (max.y - min.y);
                        projection.getGenPlaneT().set(
                            ts*_mat.m01, ts*_mat.m11, ts*_mat.m21, -ts*min.y);
                    }
                    protected Quaternion _rot = new Quaternion();
                    protected Matrix3f _mat = new Matrix3f();
                    protected Box _box = new Box();
                });
            } else if (lightType == Light.Type.POINT) {
                updaters.add(new Updater() {
                    public void update () {
                        Vector4f pos = light.position;
                        projection.getGenPlaneS().set(1f, 0f, 0f, -pos.x);
                        projection.getGenPlaneT().set(0f, 1f, 0f, -pos.y);
                        projection.getGenPlaneR().set(0f, 0f, 1f, -pos.z);
                    }
                });
            } else { // lightType == Light.Type.SPOT
                updaters.add(new Updater() {
                    public void update () {
                        Vector4f pos = light.position;
                        _viewTransformInv.getTranslation().set(pos.x, pos.y, pos.z);
                        _viewTransformInv.getRotation().fromVectorFromNegativeZ(
                            light.spotDirection);
                        _viewTransformInv.invertLocal().update(Transform3D.AFFINE);
                        Matrix4f mat = _viewTransformInv.getMatrix();
                        Vector4f gpq = projection.getGenPlaneQ();
                        gpq.set(-mat.m02, -mat.m12, -mat.m22, -mat.m32);
                        float ss = 0.5f / FloatMath.tan(FloatMath.toRadians(light.spotCutoff));
                        projection.getGenPlaneS().set(
                            ss*mat.m00 + 0.5f*gpq.x, ss*mat.m10 + 0.5f*gpq.y,
                            ss*mat.m20 + 0.5f*gpq.z, ss*mat.m30 + 0.5f*gpq.w);
                        projection.getGenPlaneT().set(
                            ss*mat.m01 + 0.5f*gpq.x, ss*mat.m11 + 0.5f*gpq.y,
                            ss*mat.m21 + 0.5f*gpq.z, ss*mat.m31 + 0.5f*gpq.w);
                    }
                    protected Transform3D _viewTransformInv = new Transform3D(Transform3D.RIGID);
                });
            }
            return new LightInfluence(light) {
                @Override public Projection getProjection () {
                    return projection;
                }
            };
        }
    }

    /**
     * Creates the scene influence corresponding to this config.
     *
     * @param updaters a list to populate with required updaters.
     */
    public abstract SceneInfluence createInfluence (
        GlContext ctx, Scope scope, Light light, ArrayList<Updater> updaters);

    /**
     * Returns the render scheme to use for the projection of the specified light type.
     */
    protected static String getProjectionScheme (Light.Type type)
    {
        switch (type) {
            case DIRECTIONAL: return RenderScheme.ORTHOGRAPHIC_PROJECTION;
            case POINT: return RenderScheme.VOLUME_PROJECTION;
            case SPOT: return RenderScheme.PERSPECTIVE_PROJECTION;
            default: return null;
        }
    }
}