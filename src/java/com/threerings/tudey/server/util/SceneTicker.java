//
// $Id$
//
// Clyde library - tools for developing networked games
// Copyright (C) 2005-2010 Three Rings Design, Inc.
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

package com.threerings.tudey.server.util;

import java.util.Arrays;
import java.util.List;

import com.google.common.collect.Lists;

import com.samskivert.util.Interval;
import com.samskivert.util.LoopingThread;
import com.samskivert.util.RunQueue;

import com.threerings.media.util.TrailingAverage;

import com.threerings.tudey.server.TudeySceneManager;

import static com.threerings.tudey.Log.*;

/**
 * Ticks some number of scene managers.
 */
public abstract class SceneTicker
{
    /**
     * Ticks the scenes on the event thread.
     */
    public static class EventThread extends SceneTicker
    {
        /**
         * Creates a new event thread ticker.
         */
        public EventThread (RunQueue runQueue, int targetInterval)
        {
            super(targetInterval);
            _runQueue = runQueue;
        }

        @Override // documentation inherited
        protected void start ()
        {
            (_interval = new Interval(_runQueue) {
                @Override public void expired () {
                    long remaining = tick();
                    if (_interval != null) {
                        _interval.schedule(Math.max(remaining, 1L));
                    }
                }
            }).schedule(_targetInterval);
        }

        @Override // documentation inherited
        protected void stop ()
        {
            if (_interval != null) {
                _interval.cancel();
                _interval = null;
            }
        }

        /** The object manager. */
        protected RunQueue _runQueue;

        /** The ticker interval. */
        protected Interval _interval;
    }

    /**
     * Ticks the scenes on a dedicated thread.
     */
    public static class DedicatedThread extends SceneTicker
    {
        /**
         * Creates a new dedicated thread ticker.
         */
        public DedicatedThread (int targetInterval)
        {
            super(targetInterval);
        }

        @Override // documentation inherited
        protected void start ()
        {
            (_thread = new LoopingThread("sceneTicker") {
                @Override protected void iterate () {
                    try {
                        Thread.sleep(_remaining);
                    } catch (InterruptedException e) {
                        return;
                    }
                    _remaining = tick();
                }
                @Override protected void kick () {
                    interrupt();
                }
                protected long _remaining = _targetInterval;
            }).start();
        }

        @Override // documentation inherited
        protected void stop ()
        {
            if (_thread != null) {
                _thread.shutdown();
                _thread = null;
            }
        }

        /** The thread on which we run. */
        protected LoopingThread _thread;
    }

    /**
     * Creates a new scene ticker.
     */
    public SceneTicker (int targetInterval)
    {
        _targetInterval = _actualInterval = targetInterval;
    }

    /**
     * Sets the target interval.
     */
    public void setTargetInterval (int interval)
    {
        _targetInterval = interval;
    }

    /**
     * Returns the average actual interval.
     */
    public int getActualInterval ()
    {
        return _actualInterval;
    }

    /**
     * Adds a scene manager to be ticked.
     */
    public void add (TudeySceneManager scenemgr)
    {
        synchronized (_scenemgrs) {
            _scenemgrs.add(scenemgr);
            if (_scenemgrs.size() == 1) {
                _lastTick = System.currentTimeMillis();
                start();
            }
        }
    }

    /**
     * Removes a scene manager.
     */
    public void remove (TudeySceneManager scenemgr)
    {
        synchronized (_scenemgrs) {
            if (_scenemgrs.remove(scenemgr) && _scenemgrs.isEmpty()) {
                stop();
            }
        }
    }

    /**
     * Checks whether the specified scene manager is being ticked.
     */
    public boolean contains (TudeySceneManager scenemgr)
    {
        synchronized (_scenemgrs) {
            return _scenemgrs.contains(scenemgr);
        }
    }

    /**
     * Starts ticking.
     */
    protected abstract void start ();

    /**
     * Stops ticking.
     */
    protected abstract void stop ();

    /**
     * Ticks the scene managers.
     */
    protected long tick ()
    {
        // compute the elapsed time since the last tick
        long now = System.currentTimeMillis();
        int elapsed = (int)(now - _lastTick);
        _lastTick = now;
        _intervalAverage.record(elapsed);
        _actualInterval = _intervalAverage.value();

        // tick the scene managers
        synchronized (_scenemgrs) {
            _sarray = _scenemgrs.toArray(_sarray);
        }
        for (TudeySceneManager scenemgr : _sarray) {
            if (scenemgr == null) {
                break;
            }
            try {
                scenemgr.tick();
            } catch (Exception e) {
                log.warning("Exception thrown in scene tick.", "where", scenemgr.where(), e);
            }
        }
        Arrays.fill(_sarray, null);

        // return the amount of time remaining until the next tick
        return _targetInterval - (System.currentTimeMillis() - _lastTick);
    }

    /** The target interval. */
    protected volatile int _targetInterval;

    /** The average actual interval. */
    protected volatile int _actualInterval;

    /** The list of scene managers to tick. */
    protected List<TudeySceneManager> _scenemgrs = Lists.newArrayList();

    /** Holds the scene managers during processing. */
    protected TudeySceneManager[] _sarray = new TudeySceneManager[0];

    /** The time of the last tick. */
    protected long _lastTick;

    /** The trailing average of the actual intervals. */
    protected TrailingAverage _intervalAverage = new TrailingAverage();
}