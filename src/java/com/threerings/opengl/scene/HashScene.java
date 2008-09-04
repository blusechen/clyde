//
// $Id$

package com.threerings.opengl.scene;

import java.util.ArrayList;
import java.util.HashMap;

import com.google.common.collect.Maps;

import com.samskivert.util.Predicate;

import com.threerings.math.Box;
import com.threerings.math.FloatMath;
import com.threerings.math.Frustum;
import com.threerings.math.Ray;
import com.threerings.math.Vector3f;

import com.threerings.opengl.util.GlContext;
import com.threerings.opengl.util.Intersectable;

/**
 * A scene that uses a hybrid spatial hashing/octree scheme to store scene elements.
 */
public class HashScene extends Scene
{
    /**
     * Creates a new hash scene.
     *
     * @param granularity the size of the top-level cells.
     * @param levels the (maximum) number of octree levels.
     */
    public HashScene (GlContext ctx, float granularity, int levels)
    {
        super(ctx);
        _granularity = granularity;
        _levels = levels;
    }

    // documentation inherited from interface Renderable
    public void enqueue ()
    {
        // make sure it intersects the top-level bounds
        Frustum frustum = _ctx.getCompositor().getCamera().getWorldVolume();
        if (frustum.getIntersectionType(_bounds) == Frustum.IntersectionType.NONE) {
            return;
        }

        // increment the visit counter
        _visit++;

        // visit the intersecting roots
        Box bounds = frustum.getBounds();
        Vector3f min = bounds.getMinimumExtent(), max = bounds.getMaximumExtent();
        float rgran = 1f / _granularity;
        int minx = (int)FloatMath.floor(min.x * rgran);
        int maxx = (int)FloatMath.floor(max.x * rgran);
        int miny = (int)FloatMath.floor(min.y * rgran);
        int maxy = (int)FloatMath.floor(max.y * rgran);
        int minz = (int)FloatMath.floor(min.z * rgran);
        int maxz = (int)FloatMath.floor(max.z * rgran);
        for (int zz = minz; zz <= maxz; zz++) {
            for (int yy = miny; yy <= maxy; yy++) {
                for (int xx = minx; xx <= maxx; xx++) {
                    Node<SceneElement> root = _elements.get(_coord.set(xx, yy, zz));
                    if (root != null) {
                        root.enqueue(frustum);
                    }
                }
            }
        }
    }

    @Override // documentation inherited
    public SceneElement getIntersection (
        Ray ray, Vector3f location, Predicate<SceneElement> filter)
    {
        // get the point of intersection with the top-level bounds
        if (!_bounds.getIntersection(ray, _pt)) {
            return null;
        }

        // increment the visit counter
        _visit++;

        // find the starting cell
        float rgran = 1f / _granularity;
        int xx = (int)FloatMath.floor(_pt.x * rgran);
        int yy = (int)FloatMath.floor(_pt.y * rgran);
        int zz = (int)FloatMath.floor(_pt.z * rgran);

        // determine the integer directions on each axis
        Vector3f dir = ray.getDirection();
        int xdir = (int)Math.signum(dir.x);
        int ydir = (int)Math.signum(dir.y);
        int zdir = (int)Math.signum(dir.z);

        // step through each cell that the ray intersects, returning the first hit or bailing
        // out when we exceed the bounds
        do {
            Node<SceneElement> root = _elements.get(_coord.set(xx, yy, zz));
            if (root != null) {
                SceneElement element = root.getIntersection(ray, location, filter);
                if (element != null) {
                    return element;
                }
            }
            float xt = (xdir == 0) ? Float.MAX_VALUE :
                ((xx + xdir) * _granularity - _pt.x) / dir.x;
            float yt = (ydir == 0) ? Float.MAX_VALUE :
                ((yy + ydir) * _granularity - _pt.y) / dir.y;
            float zt = (zdir == 0) ? Float.MAX_VALUE :
                ((zz + zdir) * _granularity - _pt.z) / dir.z;
            float t = (xt < yt) ? (xt < zt ? xt : zt) : (yt < zt ? yt : zt);
            if (xt == t) {
                xx += xdir;
            }
            if (yt == t) {
                yy += ydir;
            }
            if (zt == t) {
                zz += zdir;
            }
            _pt.addScaledLocal(dir, t);

        } while (_bounds.contains(_pt));

        // no luck
        return null;
    }

    @Override // documentation inherited
    public void getElements (Box bounds, ArrayList<SceneElement> results)
    {
        getIntersecting(_elements, bounds, results);
    }

    @Override // documentation inherited
    public void getInfluences (Box bounds, ArrayList<SceneInfluence> results)
    {
        getIntersecting(_influences, bounds, results);
    }

    @Override // documentation inherited
    public void boundsWillChange (SceneElement element)
    {
        removeFromSpatial(element);
    }

    @Override // documentation inherited
    public void boundsDidChange (SceneElement element)
    {
        addToSpatial(element);
    }

    @Override // documentation inherited
    protected void addToSpatial (SceneElement element)
    {
        add(_elements, element);
    }

    @Override // documentation inherited
    protected void removeFromSpatial (SceneElement element)
    {
        remove(_elements, element);
    }

    @Override // documentation inherited
    protected void addToSpatial (SceneInfluence influence)
    {
        add(_influences, influence);
    }

    @Override // documentation inherited
    protected void removeFromSpatial (SceneInfluence influence)
    {
        remove(_influences, influence);
    }

    /**
     * Adds the specified object to the provided map.
     */
    protected <T extends SceneObject> void add (HashMap<Coord, Node<T>> roots, T object)
    {
        Box bounds = object.getBounds();
        int level = getLevel(bounds);
        Vector3f min = bounds.getMinimumExtent(), max = bounds.getMaximumExtent();
        float rgran = 1f / _granularity;
        int minx = (int)FloatMath.floor(min.x * rgran);
        int maxx = (int)FloatMath.floor(max.x * rgran);
        int miny = (int)FloatMath.floor(min.y * rgran);
        int maxy = (int)FloatMath.floor(max.y * rgran);
        int minz = (int)FloatMath.floor(min.z * rgran);
        int maxz = (int)FloatMath.floor(max.z * rgran);
        for (int zz = minz; zz <= maxz; zz++) {
            for (int yy = miny; yy <= maxy; yy++) {
                for (int xx = minx; xx <= maxx; xx++) {
                    Node<T> root = roots.get(_coord.set(xx, yy, zz));
                    if (root == null) {
                        roots.put((Coord)_coord.clone(), root = createRoot(xx, yy, zz));
                        _bounds.addLocal(root.getBounds());
                    }
                    root.add(object, level);
                }
            }
        }
    }

    /**
     * Removes the specified object from the provided map.
     */
    protected <T extends SceneObject> void remove (HashMap<Coord, Node<T>> roots, T object)
    {
        Box bounds = object.getBounds();
        int level = getLevel(bounds);
        Vector3f min = bounds.getMinimumExtent(), max = bounds.getMaximumExtent();
        float rgran = 1f / _granularity;
        int minx = (int)FloatMath.floor(min.x * rgran);
        int maxx = (int)FloatMath.floor(max.x * rgran);
        int miny = (int)FloatMath.floor(min.y * rgran);
        int maxy = (int)FloatMath.floor(max.y * rgran);
        int minz = (int)FloatMath.floor(min.z * rgran);
        int maxz = (int)FloatMath.floor(max.z * rgran);
        for (int zz = minz; zz <= maxz; zz++) {
            for (int yy = miny; yy <= maxy; yy++) {
                for (int xx = minx; xx <= maxx; xx++) {
                    Node<T> root = roots.get(_coord.set(xx, yy, zz));
                    if (root == null) {
                        continue;
                    }
                    root.remove(object, level);
                    if (root.isEmpty()) {
                        roots.remove(_coord);
                        recomputeBounds();
                    }
                }
            }
        }
    }

    /**
     * Adds all objects from the provided map that intersect the given bounds to the specified
     * results list.
     */
    protected <T extends SceneObject> void getIntersecting (
        HashMap<Coord, Node<T>> roots, Box bounds, ArrayList<T> results)
    {
        // make sure it intersects the top-level bounds
        if (!_bounds.intersects(bounds)) {
            return;
        }

        // increment the visit counter
        _visit++;

        // visit the intersecting roots
        Vector3f min = bounds.getMinimumExtent(), max = bounds.getMaximumExtent();
        float rgran = 1f / _granularity;
        int minx = (int)FloatMath.floor(min.x * rgran);
        int maxx = (int)FloatMath.floor(max.x * rgran);
        int miny = (int)FloatMath.floor(min.y * rgran);
        int maxy = (int)FloatMath.floor(max.y * rgran);
        int minz = (int)FloatMath.floor(min.z * rgran);
        int maxz = (int)FloatMath.floor(max.z * rgran);
        for (int zz = minz; zz <= maxz; zz++) {
            for (int yy = miny; yy <= maxy; yy++) {
                for (int xx = minx; xx <= maxx; xx++) {
                    Node<T> root = roots.get(_coord.set(xx, yy, zz));
                    if (root != null) {
                        root.get(bounds, results);
                    }
                }
            }
        }
    }

    /**
     * Returns the level for the supplied bounds.
     */
    protected int getLevel (Box bounds)
    {
        int level = Math.round(
            FloatMath.log(bounds.getLongestEdge() / _granularity) / FloatMath.log(0.5f));
        return Math.min(Math.max(level, 0), _levels - 1);
    }

    /**
     * Creates a root node for the specified coordinates.
     */
    protected <T extends SceneObject> Node<T> createRoot (int x, int y, int z)
    {
        Node<T> root = (_levels > 1) ? new InternalNode<T>(_levels - 1) : new LeafNode<T>();
        Box bounds = root.getBounds();
        bounds.getMinimumExtent().set(
            x * _granularity, y * _granularity, z * _granularity);
        bounds.getMaximumExtent().set(
            (x + 1) * _granularity, (y + 1) * _granularity, (z + 1) * _granularity);
        return root;
    }

    /**
     * Recomputes the bounds of the roots.
     */
    protected void recomputeBounds ()
    {
        _bounds.setToEmpty();
        for (Node root : _elements.values()) {
            _bounds.addLocal(root.getBounds());
        }
        for (Node root : _influences.values()) {
            _bounds.addLocal(root.getBounds());
        }
    }

    /**
     * Represents a node in an octree.
     */
    protected abstract class Node<T extends SceneObject>
    {
        /**
         * Returns a reference to the bounds of the node.
         */
        public Box getBounds ()
        {
            return _bounds;
        }

        /**
         * Determines whether the node is empty (that is, has no objects).
         */
        public boolean isEmpty ()
        {
            return _objects.isEmpty();
        }

        /**
         * Adds an object to this node.
         */
        public void add (T object, int level)
        {
            _objects.add(object);
        }

        /**
         * Removes an object from this node.
         */
        public void remove (T object, int level)
        {
            _objects.remove(object);
        }

        /**
         * Enqueues the elements in this node.
         */
        public void enqueue (Frustum frustum)
        {
            Frustum.IntersectionType type = frustum.getIntersectionType(_bounds);
            if (type == Frustum.IntersectionType.CONTAINS) {
                enqueueAll();
            } else if (type == Frustum.IntersectionType.INTERSECTS) {
                enqueueIntersecting(frustum);
            }
        }

        /**
         * Checks for an intersection with this node.
         */
        public T getIntersection (Ray ray, Vector3f location, Predicate<T> filter)
        {
            T closest = null;
            Vector3f origin = ray.getOrigin();
            for (int ii = 0, nn = _objects.size(); ii < nn; ii++) {
                T object = _objects.get(ii);
                if (filter.isMatch(object) && object.updateLastVisit(_visit) &&
                        ((Intersectable)object).getIntersection(ray, _result) &&
                            (closest == null || origin.distanceSquared(_result) <
                                origin.distanceSquared(location))) {
                    closest = object;
                    location.set(_result);
                }
            }
            return closest;
        }

        /**
         * Retrieves all objects intersecting the provided bounds.
         */
        public void get (Box bounds, ArrayList<T> results)
        {
            if (bounds.contains(_bounds)) {
                getAll(results);
            } else if (bounds.intersects(_bounds)) {
                getIntersecting(bounds, results);
            }
        }

        /**
         * Enqueues all elements in this node.
         */
        protected void enqueueAll ()
        {
            for (int ii = 0, nn = _objects.size(); ii < nn; ii++) {
                T object = _objects.get(ii);
                if (object.updateLastVisit(_visit)) {
                    HashScene.this.enqueue((SceneElement)object);
                }
            }
        }

        /**
         * Enqueues the elements intersecting the given frustum.
         */
        protected void enqueueIntersecting (Frustum frustum)
        {
            for (int ii = 0, nn = _objects.size(); ii < nn; ii++) {
                T object = _objects.get(ii);
                if (object.updateLastVisit(_visit) &&
                        frustum.getIntersectionType(object.getBounds()) !=
                            Frustum.IntersectionType.NONE) {
                    HashScene.this.enqueue((SceneElement)object);
                }
            }
        }

        /**
         * Gets all objects in this node.
         */
        protected void getAll (ArrayList<T> results)
        {
            for (int ii = 0, nn = _objects.size(); ii < nn; ii++) {
                T object = _objects.get(ii);
                if (object.updateLastVisit(_visit)) {
                    results.add(object);
                }
            }
        }

        /**
         * Gets all objects in this node intersecting the provided bounds.
         */
        protected void getIntersecting (Box bounds, ArrayList<T> results)
        {
            for (int ii = 0, nn = _objects.size(); ii < nn; ii++) {
                T object = _objects.get(ii);
                if (object.updateLastVisit(_visit) && object.getBounds().intersects(bounds)) {
                    results.add(object);
                }
            }
        }

        /** The bounds of the node. */
        public Box _bounds = new Box();

        /** The objects in the node. */
        public ArrayList<T> _objects = new ArrayList<T>(4);
    }

    /**
     * An internal node with (up to) eight children.
     */
    protected class InternalNode<T extends SceneObject> extends Node<T>
    {
        public InternalNode (int levels)
        {
            _levels = levels;
        }

        @Override // documentation inherited
        public boolean isEmpty ()
        {
            if (!super.isEmpty()) {
                return false;
            }
            for (Node child : _children) {
                if (child != null) {
                    return false;
                }
            }
            return true;
        }

        @Override // documentation inherited
        public void add (T object, int level)
        {
            if (level == 0) {
                super.add(object, level);
                return;
            }
            level--;
            Box bounds = object.getBounds();
            for (int ii = 0; ii < _children.length; ii++) {
                Node<T> child = _children[ii];
                if (child == null) {
                    getChildBounds(ii, _box);
                    if (_box.intersects(bounds)) {
                        _children[ii] = child = (_levels > 1) ?
                            new InternalNode<T>(_levels - 1) : new LeafNode<T>();
                        child.getBounds().set(_box);
                        child.add(object, level);
                    }
                } else if (child.getBounds().intersects(bounds)) {
                    child.add(object, level);
                }
            }
        }

        @Override // documentation inherited
        public void remove (T object, int level)
        {
            if (level == 0) {
                super.remove(object, level);
                return;
            }
            level--;
            Box bounds = object.getBounds();
            for (int ii = 0; ii < _children.length; ii++) {
                Node<T> child = _children[ii];
                if (child != null && child.getBounds().intersects(bounds)) {
                    child.remove(object, level);
                    if (child.isEmpty()) {
                        _children[ii] = null;
                    }
                }
            }
        }

        @Override // documentation inherited
        public T getIntersection (Ray ray, Vector3f location, Predicate<T> filter)
        {
            T closest = super.getIntersection(ray, location, filter);
            Vector3f origin = ray.getOrigin();
            Vector3f result = new Vector3f();
            for (Node<T> child : _children) {
                if (child == null || !child.getBounds().intersects(ray)) {
                    continue;
                }
                T object = child.getIntersection(ray, result, filter);
                if (object != null && (closest == null ||
                        origin.distanceSquared(result) < origin.distanceSquared(location))) {
                    closest = object;
                    location.set(result);
                }
            }
            return closest;
        }

        @Override // documentation inherited
        protected void enqueueAll ()
        {
            super.enqueueAll();
            for (Node<T> child : _children) {
                if (child != null) {
                    child.enqueueAll();
                }
            }
        }

        @Override // documentation inherited
        protected void enqueueIntersecting (Frustum frustum)
        {
            super.enqueueIntersecting(frustum);
            for (Node<T> child : _children) {
                if (child != null) {
                    child.enqueue(frustum);
                }
            }
        }

        @Override // documentation inherited
        protected void getAll (ArrayList<T> results)
        {
            super.getAll(results);
            for (Node<T> child : _children) {
                if (child != null) {
                    child.getAll(results);
                }
            }
        }

        @Override // documentation inherited
        protected void getIntersecting (Box bounds, ArrayList<T> results)
        {
            super.getIntersecting(bounds, results);
            for (Node<T> child : _children) {
                if (child != null) {
                    child.getIntersecting(bounds, results);
                }
            }
        }

        /**
         * Populates the specified box with the bounds of the indexed child.
         */
        protected void getChildBounds (int idx, Box box)
        {
            Vector3f pmin = _bounds.getMinimumExtent(), pmax = _bounds.getMaximumExtent();
            Vector3f cmin = box.getMinimumExtent(), cmax = box.getMaximumExtent();
            float hsize = (pmax.x - pmin.x) * 0.5f;
            if ((idx & (1 << 2)) == 0) {
                cmin.x = pmin.x;
                cmax.x = pmin.x + hsize;
            } else {
                cmin.x = pmin.x + hsize;
                cmax.x = pmax.x;
            }
            if ((idx & (1 << 1)) == 0) {
                cmin.y = pmin.y;
                cmax.y = pmin.y + hsize;
            } else {
                cmin.y = pmin.y + hsize;
                cmax.y = pmax.y;
            }
            if ((idx & (1 << 0)) == 0) {
                cmin.z = pmin.z;
                cmax.z = pmin.z + hsize;
            } else {
                cmin.z = pmin.z + hsize;
                cmax.z = pmax.z;
            }
        }

        /** The number of levels under this node. */
        public int _levels;

        /** The children of the node. */
        @SuppressWarnings("unchecked")
        public Node<T>[] _children = (Node<T>[])new Node[8];
    }

    /**
     * A leaf node.
     */
    protected class LeafNode<T extends SceneObject> extends Node<T>
    {
    }

    /**
     * The coordinates of a hash cell.
     */
    protected static class Coord
        implements Cloneable
    {
        /** The coordinates of the cell. */
        public int x, y, z;

        /**
         * Sets the fields of the coord.
         *
         * @return a reference to this coord, for chaining.
         */
        public Coord set (int x, int y, int z)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override // documentation inherited
        public Object clone ()
        {
            try {
                return super.clone();
            } catch (CloneNotSupportedException e) {
                return null; // won't happen
            }
        }

        @Override // documentation inherited
        public int hashCode ()
        {
            return x + 31*(y + 31*z);
        }

        @Override // documentation inherited
        public boolean equals (Object other)
        {
            Coord ocoord = (Coord)other;
            return x == ocoord.x && y == ocoord.y && z == ocoord.z;
        }
    }

    /** The size of the root nodes. */
    protected float _granularity;

    /** The (maximum) number of tree levels. */
    protected int _levels;

    /** The top level element nodes. */
    protected HashMap<Coord, Node<SceneElement>> _elements = Maps.newHashMap();

    /** The top level influence nodes. */
    protected HashMap<Coord, Node<SceneInfluence>> _influences = Maps.newHashMap();

    /** The bounds of the roots. */
    protected Box _bounds = new Box();

    /** The visit counter. */
    protected int _visit;

    /** A reusable coord object for queries. */
    protected Coord _coord = new Coord();

    /** A reusable box. */
    protected Box _box = new Box();

    /** Reusable location vector. */
    protected Vector3f _pt = new Vector3f();
}
