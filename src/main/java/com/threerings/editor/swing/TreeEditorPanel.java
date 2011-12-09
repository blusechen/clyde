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

package com.threerings.editor.swing;

import java.awt.Point;

import java.io.File;

import java.lang.reflect.Array;

import java.util.List;

import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import com.samskivert.util.StringUtil;

import com.threerings.config.ConfigReference;
import com.threerings.config.ManagedConfig;
import com.threerings.config.Parameter;
import com.threerings.config.ParameterizedConfig;

import com.threerings.math.Quaternion;
import com.threerings.math.Transform2D;
import com.threerings.math.Transform3D;
import com.threerings.math.Vector2f;
import com.threerings.math.Vector3f;

import com.threerings.opengl.renderer.Color4f;

import com.threerings.editor.Introspector;
import com.threerings.editor.Property;
import com.threerings.editor.util.EditorContext;

/**
 * Allows editing properties of an object in tree mode.
 */
public class TreeEditorPanel extends BaseEditorPanel
{
    /**
     * Creates an empty editor panel.
     */
    public TreeEditorPanel (EditorContext ctx, Property[] ancestors, boolean omitColumns)
    {
        super(ctx, ancestors, omitColumns);

        _tree = new JTree(new Object[0]);
        add(isEmbedded() ? _tree : new JScrollPane(_tree));
    }

    @Override // documentation inherited
    public void setObject (Object object)
    {
        // make sure it's not the same object
        if (object == _object) {
            return;
        }
        super.setObject(object);

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(null);
        addPropertyNodes(root, object);
        _tree.setModel(new DefaultTreeModel(root, true));
    }

    @Override // documentation inherited
    public void update ()
    {

    }

    @Override // documentation inherited
    protected String getMousePath (Point pt)
    {
        pt = _tree.getMousePosition();
        TreePath path = (pt == null) ? null : _tree.getPathForLocation(pt.x, pt.y);
        if (path == null) {
            return "";
        }
        StringBuilder buf = new StringBuilder();
        for (int ii = 1, nn = path.getPathCount(); ii < nn; ii++) {
            NodeObject obj = (NodeObject)((DefaultMutableTreeNode)
                path.getPathComponent(ii)).getUserObject();
            if (obj.comp instanceof Property) {
                buf.append('.').append(((Property)obj.comp).getName());
            } else if (obj.comp instanceof Integer) {
                buf.append('[').append(obj.comp).append(']');
            } else { // obj.comp instanceof String
                buf.append("[\"").append(((String)obj.comp).replace("\"", "\\\"")).append("\"]");
            }
        }
        return buf.toString();
    }

    /**
     * Adds child nodes for the specified object's properties to the specified parent node.
     */
    protected void addPropertyNodes (DefaultMutableTreeNode parent, Object object)
    {
        Property[] properties = Introspector.getProperties(object);
        if (properties.length == 0) {
            return;
        }
        parent.setAllowsChildren(true);
        for (Property property : properties) {
            addNode(parent, getLabel(property), property.get(object),
                property.getSubtypes(), property, property);
        }
    }

    /**
     * Adds a child node for the specified labeled value.
     */
    protected void addNode (
        DefaultMutableTreeNode parent, String label, Object value,
        Class<?>[] subtypes, Property property, Object comp)
    {
        if (value == null || value instanceof Boolean || value instanceof Number ||
                value instanceof Color4f || value instanceof File ||
                value instanceof Quaternion || value instanceof String ||
                value instanceof Transform2D || value instanceof Transform3D ||
                value instanceof Vector2f || value instanceof Vector3f ||
                value instanceof Enum) {
            if (value == null) {
                value = _msgs.get("m.null_value");

            } else if (value instanceof String) {
                value = "\"" + value + "\"";

            } else if (value instanceof Enum) {
                Enum<?> eval = (Enum)value;
                value = getLabel(eval, _msgmgr.getBundle(
                    Introspector.getMessageBundle(eval.getDeclaringClass())));
            }
            parent.add(new DefaultMutableTreeNode(new NodeObject(
                label + ": " + value, comp), false));

        } else if (value instanceof ConfigReference) {
            ConfigReference<?> ref = (ConfigReference)value;
            String name = ref.getName();
            DefaultMutableTreeNode child = new DefaultMutableTreeNode(
                new NodeObject(label + ": " + name, comp), false);
            if (property != null) {
                @SuppressWarnings("unchecked") Class<ManagedConfig> clazz =
                    (Class<ManagedConfig>)property.getArgumentType(ConfigReference.class);
                ManagedConfig config = _ctx.getConfigManager().getConfig(clazz, name);
                if (config instanceof ParameterizedConfig) {
                    ParameterizedConfig pconfig = (ParameterizedConfig)config;
                    if (pconfig.parameters.length > 0) {
                        for (Parameter param : pconfig.parameters) {
                            Property aprop = param.getArgumentProperty(pconfig);
                            if (aprop != null) {
                                child.setAllowsChildren(true);
                                addNode(child, param.name, aprop.get(ref.getArguments()),
                                    aprop.getSubtypes(), aprop, param.name);
                            }
                        }
                    }
                }
            }
            parent.add(child);

        } else if (value instanceof List || value.getClass().isArray()) {
            DefaultMutableTreeNode child = new DefaultMutableTreeNode(new NodeObject(label, comp));
            Class<?>[] componentSubtypes = (property != null) ?
                property.getComponentSubtypes() : new Class[0];
            if (value instanceof List) {
                List<?> list = (List)value;
                for (int ii = 0, nn = list.size(); ii < nn; ii++) {
                    addNode(child, String.valueOf(ii), list.get(ii), componentSubtypes, null, ii);
                }
            } else {
                for (int ii = 0, nn = Array.getLength(value); ii < nn; ii++) {
                    addNode(child, String.valueOf(ii), Array.get(value, ii),
                        componentSubtypes, null, ii);
                }
            }
            parent.add(child);

        } else {
            if (subtypes.length > 1) {
                label = label + ": " + getLabel(value.getClass());
            }
            DefaultMutableTreeNode child = new DefaultMutableTreeNode(
                new NodeObject(label, comp), false);
            addPropertyNodes(child, value);
            parent.add(child);
        }
    }

    /**
     * A user object for a tree node.
     */
    protected static class NodeObject
    {
        /** The object's string representation. */
        public final String label;

        /** Either the {@link Property} for the node, or the array index, or the parameter name. */
        public final Object comp;

        /**
         * Creates a new node object.
         */
        public NodeObject (String label, Object comp)
        {
            this.label = label;
            this.comp = comp;
        }

        @Override // documentation inherited
        public String toString ()
        {
            return label;
        }
    }

    /** The tree component. */
    protected JTree _tree;
}