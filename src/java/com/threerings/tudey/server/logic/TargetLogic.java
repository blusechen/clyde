//
// $Id$
//
// Clyde library - tools for developing networked games
// Copyright (C) 2005-2009 Three Rings Design, Inc.
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

package com.threerings.tudey.server.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;

import com.google.common.collect.Lists;

import com.samskivert.util.CollectionUtil;
import com.samskivert.util.QuickSort;
import com.samskivert.util.RandomUtil;

import com.threerings.math.Vector2f;

import com.threerings.tudey.config.TargetConfig;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.data.TudeySceneModel.Entry;
import com.threerings.tudey.server.TudeySceneManager;
import com.threerings.tudey.shape.Shape;
import com.threerings.tudey.space.SpaceElement;

import static com.threerings.tudey.Log.*;

/**
 * Handles the resolution of targets.
 */
public abstract class TargetLogic extends Logic
{
    /**
     * Refers to the action source.
     */
    public static class Source extends TargetLogic
    {
        @Override // documentation inherited
        public void resolve (Logic activator, Collection<Logic> results)
        {
            results.add(_source.resolveTarget());
        }
    }

    /**
     * Refers to the actor that triggered the action.
     */
    public static class Activator extends TargetLogic
    {
        @Override // documentation inherited
        public void resolve (Logic activator, Collection<Logic> results)
        {
            results.add(activator);
        }
    }

    /**
     * Refers to an entity or entities bearing a certain tag.
     */
    public static class Tagged extends TargetLogic
    {
        @Override // documentation inherited
        public void resolve (Logic activator, Collection<Logic> results)
        {
            TargetConfig.Tagged config = (TargetConfig.Tagged)_config;
            ArrayList<Logic> tagged = _scenemgr.getTagged(config.tag);
            if (tagged != null) {
                results.addAll(tagged);
            }
        }
    }

    /**
     * Refers to entities of a certain logic class.
     */
    public static class InstanceOf extends TargetLogic
    {
        @Override // documentation inherited
        public void resolve (Logic activator, Collection<Logic> results)
        {
            ArrayList<Logic> instances = _scenemgr.getInstances(_logicClass);
            if (instances != null) {
                results.addAll(instances);
            }
        }

        @Override // documentation inherited
        protected void didInit ()
        {
            try {
                String cname = ((TargetConfig.InstanceOf)_config).logicClass;
                @SuppressWarnings("unchecked") Class<? extends Logic> clazz =
                    (Class<? extends Logic>)Class.forName(cname);
                _logicClass = clazz;

            } catch (ClassNotFoundException e) {
                log.warning("Missing logic class for InstanceOf target.", e);
                _logicClass = Logic.class;
            }
        }

        /** The logic class. */
        protected Class<? extends Logic> _logicClass;
    }

    /**
     * Refers to the entities intersecting a reference entity.
     */
    public static class Intersecting extends TargetLogic
    {
        @Override // documentation inherited
        public void resolve (Logic activator, Collection<Logic> results)
        {
            _region.resolve(activator, _shapes);
            TargetConfig.Intersecting config = (TargetConfig.Intersecting)_config;
            for (int ii = 0, nn = _shapes.size(); ii < nn; ii++) {
                Shape shape = _shapes.get(ii);
                if (config.actors) {
                    @SuppressWarnings("unchecked") ArrayList<SpaceElement> elements =
                        (ArrayList<SpaceElement>)_results;
                    _scenemgr.getActorSpace().getIntersecting(shape, elements);
                    for (int jj = 0, mm = elements.size(); jj < mm; jj++) {
                        results.add((ActorLogic)elements.get(jj).getUserObject());
                    }
                    elements.clear();
                }
                if (config.entries) {
                    @SuppressWarnings("unchecked") ArrayList<Entry> entries =
                        (ArrayList<Entry>)_results;
                    TudeySceneModel model = (TudeySceneModel)_scenemgr.getScene().getSceneModel();
                    model.getEntries(shape, entries);
                    for (int jj = 0, mm = entries.size(); jj < mm; jj++) {
                        EntryLogic logic = _scenemgr.getEntryLogic(entries.get(jj).getKey());
                        if (logic != null) {
                            results.add(logic);
                        }
                    }
                    entries.clear();
                }
            }
            _shapes.clear();
        }

        @Override // documentation inherited
        protected void didInit ()
        {
            _region = createRegion(((TargetConfig.Intersecting)_config).region, _source);
        }

        /** The region of interest. */
        protected RegionLogic _region;

        /** Holds the shapes during processing. */
        protected ArrayList<Shape> _shapes = Lists.newArrayList();

        /** Holds elements/entries during processing. */
        protected ArrayList<?> _results = Lists.newArrayList();
    }

    /**
     * Base class for targets limited to a sized subset.
     */
    public static abstract class Subset extends TargetLogic
    {
        @Override // documentation inherited
        public void resolve (Logic activator, Collection<Logic> results)
        {
            _target.resolve(activator, _targets);
            int size = ((TargetConfig.Subset)_config).size;
            if (_targets.size() <= size) {
                results.addAll(_targets);
            } else {
                selectSubset(size, activator, results);
            }
            _targets.clear();
        }

        @Override // documentation inherited
        protected void didInit ()
        {
            _target = createTarget(((TargetConfig.Subset)_config).target, _source);
        }

        /**
         * Selects a subset of the specified size and places the objects in the results.
         */
        protected abstract void selectSubset (
            int size, Logic activator, Collection<Logic> results);

        /** The contained target. */
        protected TargetLogic _target;

        /** Holds the targets during processing. */
        protected ArrayList<Logic> _targets = Lists.newArrayList();
    }

    /**
     * Limits targets to a random subset.
     */
    public static class RandomSubset extends Subset
    {
        @Override // documentation inherited
        protected void selectSubset (int size, Logic activator, Collection<Logic> results)
        {
            if (size == 1) {
                results.add(RandomUtil.pickRandom(_targets));
            } else {
                results.addAll(CollectionUtil.selectRandomSubset(_targets, size));
            }
        }
    }

    /**
     * Superclass of the distance-based subsets.
     */
    public static abstract class DistanceSubset extends Subset
        implements Comparator<Logic>
    {
        @Override // documentation inherited
        protected void didInit ()
        {
            super.didInit();
            _location = createTarget(((TargetConfig.DistanceSubset)_config).location, _source);
        }

        @Override // documentation inherited
        protected void selectSubset (int size, Logic activator, Collection<Logic> results)
        {
            // average the locations
            _location.resolve(activator, _locations);
            int nlocs = _locations.size();
            _reference.set(Vector2f.ZERO);
            for (int ii = 0; ii < nlocs; ii++) {
                _reference.addLocal(_locations.get(ii).getTranslation());
            }
            _reference.multLocal(1f / nlocs);
            _locations.clear();

            // sort
            QuickSort.sort(_targets, this);

            // add first size elements
            results.addAll(_targets.subList(0, size));
        }

        /** The reference location. */
        protected TargetLogic _location;

        /** Holds the locations during processing. */
        protected ArrayList<Logic> _locations = Lists.newArrayList();

        /** Holds the reference point. */
        protected Vector2f _reference = new Vector2f();
    }

    /**
     * Limits targets to the nearest subset.
     */
    public static class NearestSubset extends DistanceSubset
    {
        // documentation inherited from interface Comparator
        public int compare (Logic l1, Logic l2)
        {
            return Float.compare(
                l1.getTranslation().distance(_reference),
                l2.getTranslation().distance(_reference));
        }
    }

    /**
     * Limits targets to the farthest subset.
     */
    public static class FarthestSubset extends DistanceSubset
    {
        // documentation inherited from interface Comparator
        public int compare (Logic l1, Logic l2)
        {
            return Float.compare(
                l2.getTranslation().distance(_reference),
                l1.getTranslation().distance(_reference));
        }
    }

    /**
     * Limits targets to those satisfying a condition.
     */
    public static class Conditional extends TargetLogic
    {
        @Override // documentation inherited
        public void resolve (Logic activator, Collection<Logic> results)
        {
            _target.resolve(activator, _targets);
            for (int ii = 0, nn = _targets.size(); ii < nn; ii++) {
                Logic target = _targets.get(ii);
                if (_condition.isSatisfied(target)) {
                    results.add(target);
                }
            }
            _targets.clear();
        }

        @Override // documentation inherited
        protected void didInit ()
        {
            TargetConfig.Conditional config = (TargetConfig.Conditional)_config;
            _condition = createCondition(config.condition, _source);
            _target = createTarget(config.target, _source);
        }

        /** The condition to evaluate for each potential target. */
        protected ConditionLogic _condition;

        /** The contained target. */
        protected TargetLogic _target;

        /** Holds the targets during processing. */
        protected ArrayList<Logic> _targets = Lists.newArrayList();
    }

    /**
     * Refers to multiple targets.
     */
    public static class Compound extends TargetLogic
    {
        @Override // documentation inherited
        public void resolve (Logic activator, Collection<Logic> results)
        {
            for (TargetLogic target : _targets) {
                target.resolve(activator, results);
            }
        }

        @Override // documentation inherited
        protected void didInit ()
        {
            ArrayList<TargetLogic> list = Lists.newArrayList();
            for (TargetConfig config : ((TargetConfig.Compound)_config).targets) {
                TargetLogic target = createTarget(config, _source);
                if (target != null) {
                    list.add(target);
                }
            }
            _targets = list.toArray(new TargetLogic[list.size()]);
        }

        /** The component targets. */
        protected TargetLogic[] _targets;
    }

    /**
     * Initializes the logic.
     */
    public void init (TudeySceneManager scenemgr, TargetConfig config, Logic source)
    {
        super.init(scenemgr);
        _config = config;
        _source = source;

        // give subclasses a chance to initialize
        didInit();
    }

    /**
     * Resolves the list of targets, placing the results in the supplied collection.
     *
     * @param activator the entity that triggered the action.
     */
    public abstract void resolve (Logic activator, Collection<Logic> results);

    @Override // documentation inherited
    public boolean isActive ()
    {
        return _source.isActive();
    }

    @Override // documentation inherited
    public Vector2f getTranslation ()
    {
        return _source.getTranslation();
    }

    @Override // documentation inherited
    public float getRotation ()
    {
        return _source.getRotation();
    }

    /**
     * Override to perform custom initialization.
     */
    protected void didInit ()
    {
        // nothing by default
    }

    /** The target configuration. */
    protected TargetConfig _config;

    /** The action source. */
    protected Logic _source;
}
