//
// $Id$

package com.threerings.opengl.renderer.config;

import org.lwjgl.opengl.GL11;

import com.threerings.editor.Editable;
import com.threerings.export.Exportable;
import com.threerings.util.DeepObject;

import com.threerings.opengl.renderer.state.CullState;

/**
 * Configurable cull state.
 */
public class CullStateConfig extends DeepObject
    implements Exportable
{
    /** Cull face constants. */
    public enum Face
    {
        DISABLED(-1),
        FRONT(GL11.GL_FRONT),
        BACK(GL11.GL_BACK),
        FRONT_AND_BACK(GL11.GL_FRONT_AND_BACK);

        public int getConstant ()
        {
            return _constant;
        }

        Face (int constant)
        {
            _constant = constant;
        }

        protected int _constant;
    }

    /** The cull face. */
    @Editable
    public Face face = Face.DISABLED;

    /**
     * Returns the corresponding color state.
     */
    public CullState getState ()
    {
        return CullState.getInstance(face.getConstant());
    }
}
