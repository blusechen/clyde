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

package com.threerings.tudey.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import com.samskivert.util.HashIntMap;
import com.samskivert.util.IntMap.IntEntry;

import com.threerings.crowd.chat.client.ChatDisplay;
import com.threerings.crowd.chat.data.ChatCodes;
import com.threerings.crowd.chat.data.ChatMessage;
import com.threerings.crowd.chat.data.UserMessage;
import com.threerings.crowd.client.OccupantObserver;
import com.threerings.crowd.client.PlaceView;
import com.threerings.crowd.data.OccupantInfo;
import com.threerings.crowd.data.PlaceObject;

import com.threerings.media.util.TrailingAverage;

import com.threerings.config.ConfigManager;
import com.threerings.expr.Scope;
import com.threerings.expr.Scoped;
import com.threerings.expr.SimpleScope;
import com.threerings.math.FloatMath;
import com.threerings.math.Ray3D;
import com.threerings.math.Transform3D;
import com.threerings.math.Vector2f;
import com.threerings.math.Vector3f;

import com.threerings.opengl.GlView;
import com.threerings.opengl.camera.OrbitCameraHandler;
import com.threerings.opengl.gui.StretchWindow;
import com.threerings.opengl.gui.Window;
import com.threerings.opengl.model.Model;
import com.threerings.opengl.scene.HashScene;
import com.threerings.opengl.scene.SceneElement;
import com.threerings.opengl.util.GlContext;
import com.threerings.opengl.util.Preloadable;
import com.threerings.opengl.util.PreloadableSet;
import com.threerings.opengl.util.Renderable;
import com.threerings.opengl.util.Tickable;

import com.samskivert.util.Predicate;

import com.threerings.tudey.client.sprite.ActorSprite;
import com.threerings.tudey.client.sprite.EffectSprite;
import com.threerings.tudey.client.sprite.EntrySprite;
import com.threerings.tudey.client.sprite.Sprite;
import com.threerings.tudey.client.sprite.PlaceableSprite;
import com.threerings.tudey.client.sprite.TileSprite;
import com.threerings.tudey.client.util.TimeSmoother;
import com.threerings.tudey.data.TudeyOccupantInfo;
import com.threerings.tudey.data.TudeySceneConfig;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.data.TudeySceneModel.Entry;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.data.effect.Effect;
import com.threerings.tudey.dobj.ActorDelta;
import com.threerings.tudey.dobj.SceneDeltaEvent;
import com.threerings.tudey.shape.Shape;
import com.threerings.tudey.shape.ShapeElement;
import com.threerings.tudey.space.HashSpace;
import com.threerings.tudey.space.SpaceElement;
import com.threerings.tudey.util.ActorAdvancer;
import com.threerings.tudey.util.TudeyContext;
import com.threerings.tudey.util.TudeySceneMetrics;

import static com.threerings.tudey.Log.*;

/**
 * Displays a view of a Tudey scene.
 */
public class TudeySceneView extends SimpleScope
    implements GlView, PlaceView, TudeySceneModel.Observer,
        OccupantObserver, ChatDisplay, ActorAdvancer.Environment
{
    /**
     * An interface for objects (such as sprites and observers) that require per-tick updates.
     */
    public interface TickParticipant
    {
        /**
         * Ticks the participant.
         *
         * @param delayedTime the current delayed client time.
         * @return true to continue ticking the participant, false to remove it from the list.
         */
        public boolean tick (int delayedTime);
    }

    /**
     * Creates a new scene view for use in the editor.
     */
    public TudeySceneView (TudeyContext ctx)
    {
        this(ctx, null);
    }

    /**
     * Creates a new scene view.
     */
    public TudeySceneView (TudeyContext ctx, TudeySceneController ctrl)
    {
        super(ctx.getScope());
        _ctx = ctx;
        _ctrl = ctrl;
        _placeConfig = (ctrl == null) ?
            new TudeySceneConfig() : (TudeySceneConfig)ctrl.getPlaceConfig();
        _scene = new HashScene(ctx, 64f, 6);
        _scene.setParentScope(this);

        // create and initialize the camera handler
        _camhand = createCameraHandler();
        TudeySceneMetrics.initCameraHandler(_camhand);

        // create the input window
        _inputWindow = new StretchWindow(ctx, null) {
            public boolean shouldShadeBehind () {
                return false;
            }
        };
        _inputWindow.setModal(true);

        // insert the baseline (empty) update record
        _records.add(new UpdateRecord(0, new HashIntMap<Actor>()));
    }

    /**
     * Returns a reference to the scene controller.
     */
    public TudeySceneController getController ()
    {
        return _ctrl;
    }

    /**
     * Returns a reference to the camera handler.
     */
    public OrbitCameraHandler getCameraHandler ()
    {
        return _camhand;
    }

    /**
     * Returns a reference to the window used to gather input events.
     */
    public Window getInputWindow ()
    {
        return _inputWindow;
    }

    /**
     * Returns a reference to the view scene.
     */
    public HashScene getScene ()
    {
        return _scene;
    }

    /**
     * Returns a reference to the actor space.
     */
    public HashSpace getActorSpace ()
    {
        return _actorSpace;
    }

    /**
     * Returns the delayed client time, which is the smoothed time minus a delay that compensates
     * for network jitter and dropped packets.
     */
    public int getDelayedTime ()
    {
        return _smoothedTime - getBufferDelay();
    }

    /**
     * Returns the delay with which to display information received from the server in order to
     * compensate for network jitter and dropped packets.
     */
    public int getBufferDelay ()
    {
        return _placeConfig.getBufferDelay();
    }

    /**
     * Returns the advanced time, which is the smoothed time plus an interval that compensates for
     * buffering and latency.
     */
    public int getAdvancedTime ()
    {
        return _advancedTime;
    }

    /**
     * Returns the interval ahead of the smoothed server time (which estimates the server time
     * minus one-way latency) at which we schedule input events.  This should be at least the
     * transmit interval (which represents the maximum amount of time that events may be delayed)
     * plus the two-way latency.
     */
    public int getInputAdvance ()
    {
        return _placeConfig.getInputAdvance(_pingAverage.value());
    }

    /**
     * Returns the smoothed estimate of the server time (plus network latency) calculated at
     * the start of each tick.
     */
    public int getSmoothedTime ()
    {
        return _smoothedTime;
    }

    /**
     * Sets the scene model for this view.
     */
    public void setSceneModel (TudeySceneModel model)
    {
        // clear out the existing sprites
        if (_sceneModel != null) {
            _sceneModel.removeObserver(this);
        }
        for (EntrySprite sprite : _entrySprites.values()) {
            sprite.dispose();
        }
        _entrySprites.clear();

        // create the new sprites
        (_sceneModel = model).addObserver(this);
        for (Entry entry : _sceneModel.getEntries()) {
            addEntrySprite(entry);
        }
    }

    /**
     * Returns the sprite corresponding to the entry with the given key.
     */
    public EntrySprite getEntrySprite (Object key)
    {
        return _entrySprites.get(key);
    }

    /**
     * Returns a reference to the actor sprite with the supplied id, or <code>null</code> if it
     * doesn't exist.
     */
    public ActorSprite getActorSprite (int id)
    {
        return _actorSprites.get(id);
    }

    /**
     * Returns a reference to the target sprite.
     */
    public ActorSprite getTargetSprite ()
    {
        return _targetSprite;
    }

    /**
     * Checks for an intersection between the provided ray and the sprites in the scene.
     *
     * @param location a vector to populate with the location of the intersection, if any.
     * @return a reference to the first sprite intersected by the ray, or <code>null</code> for
     * none.
     */
    public Sprite getIntersection (Ray3D ray, Vector3f location)
    {
        Predicate<Sprite> filter = Predicate.trueInstance();
        return getIntersection(ray, location, filter);
    }

    /**
     * Checks for an intersection between the provided ray and the sprites in the scene.
     *
     * @param location a vector to populate with the location of the intersection, if any.
     * @return a reference to the first sprite intersected by the ray, or <code>null</code> for
     * none.
     */
    public Sprite getIntersection (Ray3D ray, Vector3f location, final Predicate<Sprite> filter)
    {
        SceneElement el = _scene.getIntersection(ray, location, new Predicate<SceneElement>() {
            public boolean isMatch (SceneElement element) {
                Object userObject = element.getUserObject();
                return userObject instanceof Sprite && filter.isMatch((Sprite)userObject);
            }
        });
        return (el == null) ? null : (Sprite)el.getUserObject();
    }

    /**
     * Gets the transform of an object on the floor with the provided coordinates.
     *
     * @param mask the floor mask to use for the query.
     */
    public Transform3D getFloorTransform (float x, float y, float rotation, int mask)
    {
        return getFloorTransform(x, y, rotation, _floorMaskFilter.init(mask));
    }

    /**
     * Gets the transform of an object on the floor with the provided coordinates.
     *
     * @param filter the floor filter to use for the query.
     */
    public Transform3D getFloorTransform (
        float x, float y, float rotation, Predicate<SceneElement> filter)
    {
        return getFloorTransform(x, y, rotation, filter, new Transform3D(Transform3D.UNIFORM));
    }

    /**
     * Gets the transform of an object on the floor with the provided coordinates.
     *
     * @param mask the floor mask to use for the query.
     */
    public Transform3D getFloorTransform (
        float x, float y, float rotation, int mask, Transform3D result)
    {
        return getFloorTransform(x, y, rotation, _floorMaskFilter.init(mask), result);
    }

    /**
     * Gets the transform of an object on the floor with the provided coordinates.
     *
     * @param filter the floor filter to use for the query.
     */
    public Transform3D getFloorTransform (
        float x, float y, float rotation, Predicate<SceneElement> filter, Transform3D result)
    {
        Vector3f translation = result.getTranslation();
        translation.set(x, y, getFloorZ(x, y, filter, translation.z));
        result.getRotation().fromAngleAxis(FloatMath.HALF_PI + rotation, Vector3f.UNIT_Z);
        return result;
    }

    /**
     * Returns the z coordinate of the floor at the provided coordinates, or the provided default
     * if no floor is found.
     *
     * @param mask the floor mask to use for the query.
     */
    public float getFloorZ (float x, float y, int mask, float defvalue)
    {
        return getFloorZ(x, y, _floorMaskFilter.init(mask), defvalue);
    }

    /**
     * Returns the z coordinate of the floor at the provided coordinates, or the provided default
     * if no floor is found.
     *
     * @param filter the floor filter to use for the query.
     */
    public float getFloorZ (float x, float y, Predicate<SceneElement> filter, float defvalue)
    {
        _ray.getOrigin().set(x, y, 10000f);
        return (_scene.getIntersection(_ray, _isect, filter) == null) ? defvalue : _isect.z;
    }

    /**
     * Processes a scene delta received from the server.
     *
     * @return true if the scene delta was processed, false if we have not yet received the
     * reference delta.
     */
    public boolean processSceneDelta (SceneDeltaEvent event)
    {
        // update the ping estimate (used to compute the input advance)
        _pingAverage.record(_ping = event.getPing());

        // create/update the time smoothers
        int timestamp = event.getTimestamp(), advanced = timestamp + getInputAdvance();
        if (_smoother == null) {
            _smoother = new TimeSmoother(_smoothedTime = timestamp);
            _advancedSmoother = new TimeSmoother(_advancedTime = advanced);
        } else {
            _smoother.update(timestamp);
            _advancedSmoother.update(advanced);
        }

        // find the reference and remove all records before it
        if (!pruneRecords(event.getReference())) {
            return false;
        }
        HashIntMap<Actor> oactors = _records.get(0).getActors();
        HashIntMap<Actor> actors = new HashIntMap<Actor>();

        // start with all the old actors
        actors.putAll(oactors);

        // add any new actors
        Actor[] added = event.getAddedActors();
        if (added != null) {
            for (Actor actor : added) {
                actor.init(_ctx.getConfigManager());
                Actor oactor = actors.put(actor.getId(), actor);
                if (oactor != null) {
                    log.warning("Replacing existing actor.", "oactor", oactor, "nactor", actor);
                }
            }
        }

        // update any updated actors
        ActorDelta[] updated = event.getUpdatedActorDeltas();
        if (updated != null) {
            for (ActorDelta delta : updated) {
                int id = delta.getId();
                Actor oactor = actors.get(id);
                if (oactor != null) {
                    Actor nactor = (Actor)delta.apply(oactor);
                    nactor.init(_ctx.getConfigManager());
                    actors.put(id, nactor);
                } else {
                    log.warning("Missing actor for delta.", "delta", delta);
                }
            }
        }

        // remove any removed actors
        int[] removed = event.getRemovedActorIds();
        if (removed != null) {
            for (int id : removed) {
                actors.remove(id);
            }
        }

        // record the update
        _records.add(new UpdateRecord(timestamp, actors));

        // at this point, if we are to preload, we have enough information to begin
        if (_loadingWindow != null) {
            if (_preloads == null) {
                ((TudeySceneModel)_ctx.getSceneDirector().getScene().getSceneModel()).getPreloads(
                    _preloads = new PreloadableSet());
                ConfigManager cfgmgr = _ctx.getConfigManager();
                for (Actor actor : actors.values()) {
                    actor.getPreloads(cfgmgr, _preloads);
                }
            }
            return true;
        }

        // create/update the sprites for actors in the set
        for (Actor actor : actors.values()) {
            int id = actor.getId();
            ActorSprite sprite = _actorSprites.get(id);
            if (sprite == null) {
                addPreloads(actor);
                _actorSprites.put(id, sprite = new ActorSprite(_ctx, this, timestamp, actor));
                if (id == _ctrl.getTargetId()) {
                    _targetSprite = sprite;
                    if (_ctrl.isTargetControlled()) {
                        _ctrl.controlledTargetAdded(timestamp, actor);
                    }
                }
            } else {
                if (_ctrl.isControlledTargetId(id)) {
                    _ctrl.controlledTargetUpdated(timestamp, actor);
                } else {
                    sprite.update(timestamp, actor);
                }
            }
        }

        // remove sprites for actors no longer in the set
        for (Iterator<IntEntry<ActorSprite>> it = _actorSprites.intEntrySet().iterator();
                it.hasNext(); ) {
            IntEntry<ActorSprite> entry = it.next();
            if (!actors.containsKey(entry.getIntKey())) {
                ActorSprite sprite = entry.getValue();
                sprite.remove(timestamp);
                if (_targetSprite == sprite) {
                    _targetSprite = null;
                    if (_ctrl.isTargetControlled()) {
                        _ctrl.controlledTargetRemoved(timestamp);
                    }
                }
                it.remove();
            }
        }

        // create handlers for any effects fired since the last update
        Effect[] fired = event.getEffectsFired();
        if (fired != null) {
            int last = _records.get(_records.size() - 2).getTimestamp();
            for (Effect effect : fired) {
                if (effect.getTimestamp() > last) {
                    effect.init(_ctx.getConfigManager());
                    new EffectSprite(_ctx, this, effect);
                }
            }
        }

        return true;
    }

    /**
     * Adds a participant to tick at each frame.
     */
    public void addTickParticipant (TickParticipant participant)
    {
        _tickParticipants.add(participant);
    }

    /**
     * Removes a participant from the tick list.
     */
    public void removeTickParticipant (TickParticipant participant)
    {
        _tickParticipants.remove(participant);
    }

    /**
     * Updates the target sprite based on the target id.
     */
    public void updateTargetSprite ()
    {
        _targetSprite = _actorSprites.get(_ctrl.getTargetId());
    }

    // documentation inherited from interface GlView
    public void wasAdded ()
    {
        _ctx.setCameraHandler(_camhand);
        _ctx.getRoot().addWindow(_inputWindow);
        if (_ctrl != null) {
            _ctrl.wasAdded();
        }
    }

    // documentation inherited from interface GlView
    public void wasRemoved ()
    {
        _ctx.getRoot().removeWindow(_inputWindow);
        if (_loadingWindow != null) {
            _ctx.getRoot().removeWindow(_loadingWindow);
            _loadingWindow = null;
        }
        if (_ctrl != null) {
            _ctrl.wasRemoved();
        }
        _scene.dispose();
    }

    // documentation inherited from interface Tickable
    public void tick (float elapsed)
    {
        // if we are preloading, load up the next batch of resources
        if (_loadingWindow != null && _preloads != null) {
            float pct = _preloads.preloadBatch(_ctx);
            updateLoadingWindow(pct);
            if (pct == 1f) {
                _loadingWindow = null;
                setSceneModel((TudeySceneModel)_ctx.getSceneDirector().getScene().getSceneModel());
            }
        }

        // update the smoothed time, if possible
        if (_smoother != null) {
            _smoothedTime = _smoother.getTime();
            _advancedTime = _advancedSmoother.getTime();
        }

        // tick the controller, if present
        if (_ctrl != null) {
            _ctrl.tick(elapsed);
        }

        // tick the participants in reverse order, to allow removal
        int delayedTime = getDelayedTime();
        for (int ii = _tickParticipants.size() - 1; ii >= 0; ii--) {
            if (!_tickParticipants.get(ii).tick(delayedTime)) {
                _tickParticipants.remove(ii);
            }
        }

        // track the target sprite, if any
        if (_targetSprite != null) {
            Vector3f translation = _targetSprite.getModel().getLocalTransform().getTranslation();
            _camhand.getTarget().set(translation).addLocal(TudeySceneMetrics.getTargetOffset());
            _camhand.updatePosition();
        }

        // tick the scene
        _scene.tick(elapsed);
    }

    // documentation inherited from interface Renderable
    public void enqueue ()
    {
        _scene.enqueue();
    }

    // documentation inherited from interface PlaceView
    public void willEnterPlace (PlaceObject plobj)
    {
        _ctx.getOccupantDirector().addOccupantObserver(this);
        _ctx.getChatDirector().addChatDisplay(this);

        // if we don't need to preload, set the scene model immediately; otherwise, create the
        // loading screen and wait for the first scene delta to start preloading
        TudeySceneModel model =
            (TudeySceneModel)_ctx.getSceneDirector().getScene().getSceneModel();
        _loadingWindow = maybeCreateLoadingWindow(model);
        if (_loadingWindow == null) {
            setSceneModel(model);
            return;
        }
        _ctx.getRoot().addWindow(_loadingWindow);
        updateLoadingWindow(0f);
    }

    // documentation inherited from interface PlaceView
    public void didLeavePlace (PlaceObject plobj)
    {
        if (_sceneModel != null) {
            _sceneModel.removeObserver(this);
        }
        _ctx.getOccupantDirector().removeOccupantObserver(this);
        _ctx.getChatDirector().removeChatDisplay(this);
    }

    // documentation inherited from interface TudeySceneModel.Observer
    public void entryAdded (Entry entry)
    {
        addPreloads(entry);
        addEntrySprite(entry);
    }

    // documentation inherited from interface TudeySceneModel.Observer
    public void entryUpdated (Entry oentry, Entry nentry)
    {
        addPreloads(nentry);
        EntrySprite sprite = _entrySprites.get(nentry.getKey());
        if (sprite != null) {
            sprite.update(nentry);
        } else {
            log.warning("Missing sprite to update.", "entry", nentry);
        }
    }

    // documentation inherited from interface TudeySceneModel.Observer
    public void entryRemoved (Entry oentry)
    {
        EntrySprite sprite = _entrySprites.remove(oentry.getKey());
        if (sprite != null) {
            sprite.dispose();
        } else {
            log.warning("Missing entry sprite to remove.", "entry", oentry);
        }
    }

    // documentation inherited from interface OccupantObserver
    public void occupantEntered (OccupantInfo info)
    {
        TudeyOccupantInfo toi = (TudeyOccupantInfo)info;
        ActorSprite sprite = _actorSprites.get(toi.pawnId);
        if (sprite != null) {
            sprite.occupantEntered(toi);
        }
    }

    // documentation inherited from interface OccupantObserver
    public void occupantLeft (OccupantInfo info)
    {
        TudeyOccupantInfo toi = (TudeyOccupantInfo)info;
        ActorSprite sprite = _actorSprites.get(toi.pawnId);
        if (sprite != null) {
            sprite.occupantLeft(toi);
        }
    }

    // documentation inherited from interface OccupantObserver
    public void occupantUpdated (OccupantInfo oinfo, OccupantInfo ninfo)
    {
        TudeyOccupantInfo otoi = (TudeyOccupantInfo)oinfo;
        ActorSprite sprite = _actorSprites.get(otoi.pawnId);
        if (sprite != null) {
            sprite.occupantUpdated(otoi, (TudeyOccupantInfo)ninfo);
        }
    }

    // documentation inherited from interface ChatDisplay
    public boolean displayMessage (ChatMessage msg, boolean alreadyDisplayed)
    {
        if (!(msg instanceof UserMessage && ChatCodes.PLACE_CHAT_TYPE.equals(msg.localtype))) {
            return false;
        }
        UserMessage umsg = (UserMessage)msg;
        TudeyOccupantInfo info =
            (TudeyOccupantInfo)_ctx.getOccupantDirector().getOccupantInfo(umsg.speaker);
        if (info == null) {
            return false;
        }
        ActorSprite sprite = _actorSprites.get(info.pawnId);
        return sprite != null && sprite.displayMessage(umsg, alreadyDisplayed);
    }

    // documentation inherited from interface ChatDisplay
    public void clear ()
    {
        for (ActorSprite sprite : _actorSprites.values()) {
            sprite.clearMessages();
        }
    }

    // documentation inherited from interface ActorAdvancer.Environment
    public boolean getPenetration (Actor actor, Shape shape, Vector2f result)
    {
        // start with zero penetration
        result.set(Vector2f.ZERO);

        // check the scene model
        _sceneModel.getPenetration(actor, shape, result);

        // get the intersecting elements
        _actorSpace.getIntersecting(shape, _elements);
        for (int ii = 0, nn = _elements.size(); ii < nn; ii++) {
            SpaceElement element = _elements.get(ii);
            Actor oactor = ((ActorSprite)element.getUserObject()).getActor();
            if (actor.canCollide(oactor)) {
                ((ShapeElement)element).getWorldShape().getPenetration(shape, _penetration);
                if (_penetration.lengthSquared() > result.lengthSquared()) {
                    result.set(_penetration);
                }
            }
        }
        _elements.clear();

        // if our vector is non-zero, we penetrated
        return !result.equals(Vector2f.ZERO);
    }

    @Override // documentation inherited
    public String getScopeName ()
    {
        return "view";
    }

    /**
     * Creates the camera handler for the view.
     */
    protected OrbitCameraHandler createCameraHandler ()
    {
        return new OrbitCameraHandler(_ctx);
    }

    /**
     * Creates the loading window, or returns <code>null</code> to skip preloading.
     */
    protected Window maybeCreateLoadingWindow (TudeySceneModel model)
    {
        return null;
    }

    /**
     * Updates the loading window with the current percentage of resources loaded.  If
     * <code>pct</code> is equal to 1.0, this method should remove the loading window (or start
     * fading it out).
     */
    protected void updateLoadingWindow (float pct)
    {
        if (pct == 1f) {
            _ctx.getRoot().removeWindow(_loadingWindow);
        }
    }

    /**
     * Adds a sprite for the specified entry.
     */
    protected void addEntrySprite (Entry entry)
    {
        _entrySprites.put(entry.getKey(), entry.createSprite(_ctx, this));
    }

    /**
     * Adds the specified entry's preloads to the set if appropriate.
     */
    protected void addPreloads (Entry entry)
    {
        if (_preloads != null) {
            entry.getPreloads(_ctx.getConfigManager(), _npreloads);
            addNewPreloads();
        }
    }

    /**
     * Adds the specified actor's preloads to the set if appropriate.
     */
    protected void addPreloads (Actor actor)
    {
        if (_preloads != null) {
            actor.getPreloads(_ctx.getConfigManager(), _npreloads);
            addNewPreloads();
        }
    }

    /**
     * Adds the specified effect's preloads to the set if appropriate.
     */
    protected void addPreloads (Effect effect)
    {
        if (_preloads != null) {
            effect.getPreloads(_ctx.getConfigManager(), _npreloads);
            addNewPreloads();
        }
    }

    /**
     * Adds any preloads in the new set that are not yet in the actual set.
     */
    protected void addNewPreloads ()
    {
        int osize = _preloads.size();
        for (Preloadable preloadable : _npreloads) {
            if (_preloads.add(preloadable)) {
                preloadable.preload(_ctx);
            }
        }
        _npreloads.clear();
    }

    /**
     * Prunes all records before the supplied reference time, if found.
     *
     * @return true if the reference time was found, false if not.
     */
    protected boolean pruneRecords (int reference)
    {
        for (int ii = _records.size() - 1; ii >= 0; ii--) {
            if (_records.get(ii).getTimestamp() == reference) {
                _records.subList(0, ii).clear();
                return true;
            }
        }
        return false;
    }

    /**
     * Contains the state at a single update.
     */
    protected static class UpdateRecord
    {
        /**
         * Creates a new update record.
         */
        public UpdateRecord (int timestamp, HashIntMap<Actor> actors)
        {
            _timestamp = timestamp;
            _actors = actors;
        }

        /**
         * Returns the timestamp of this update.
         */
        public int getTimestamp ()
        {
            return _timestamp;
        }

        /**
         * Returns the map of actors.
         */
        public HashIntMap<Actor> getActors ()
        {
            return _actors;
        }

        /** The timestamp of the update. */
        protected int _timestamp;

        /** The states of the actors. */
        protected HashIntMap<Actor> _actors;
    }

    /**
     * Used to select sprites according to their floor flags.
     */
    protected static class FloorMaskFilter extends Predicate<SceneElement>
    {
        /**
         * (Re)initializes the filter with its mask.
         *
         * @return a reference to the filter, for chaining.
         */
        public FloorMaskFilter init (int mask)
        {
            _mask = mask;
            return this;
        }

        @Override // documentation inherited
        public boolean isMatch (SceneElement element)
        {
            Object obj = element.getUserObject();
            return obj instanceof Sprite && (((Sprite)obj).getFloorFlags() & _mask) != 0;
        }

        /** The floor mask. */
        protected int _mask;
    }

    /** The application context. */
    protected TudeyContext _ctx;

    /** The controller that created this view. */
    protected TudeySceneController _ctrl;

    /** The place configuration. */
    protected TudeySceneConfig _placeConfig;

    /** The view's camera handler. */
    protected OrbitCameraHandler _camhand;

    /** A window used to gather input events. */
    protected Window _inputWindow;

    /** The loading window, if any. */
    protected Window _loadingWindow;

    /** The set of resources to preload. */
    protected PreloadableSet _preloads;

    /** The OpenGL scene. */
    @Scoped
    protected HashScene _scene;

    /** The scene model. */
    protected TudeySceneModel _sceneModel;

    /** Smoother used to provide a smoothed time estimate. */
    protected TimeSmoother _smoother;

    /** The smoothed time. */
    protected int _smoothedTime;

    /** Smooths the advanced time. */
    protected TimeSmoother _advancedSmoother;

    /** The advanced time. */
    protected int _advancedTime;

    /** The last estimated ping time. */
    protected int _ping;

    /** The trailing average of the ping times. */
    protected TrailingAverage _pingAverage = new TrailingAverage();

    /** Records of each update received from the server. */
    protected ArrayList<UpdateRecord> _records = new ArrayList<UpdateRecord>();

    /** Sprites corresponding to the scene entries. */
    protected HashMap<Object, EntrySprite> _entrySprites = new HashMap<Object, EntrySprite>();

    /** Sprites corresponding to the actors in the scene. */
    protected HashIntMap<ActorSprite> _actorSprites = new HashIntMap<ActorSprite>();

    /** The actor space (used for client-side collision detection). */
    protected HashSpace _actorSpace = new HashSpace(64f, 6);

    /** The list of participants in the tick. */
    protected ArrayList<TickParticipant> _tickParticipants = new ArrayList<TickParticipant>();

    /** The sprite that the camera is tracking. */
    protected ActorSprite _targetSprite;

    /** The offset of the camera target from the target sprite's translation. */
    protected Vector3f _targetOffset = new Vector3f();

    /** Used to find the floor. */
    protected Ray3D _ray = new Ray3D(Vector3f.ZERO, new Vector3f(0f, 0f, -1f));

    /** Used to find the floor. */
    protected Vector3f _isect = new Vector3f();

    /** Used to find the floor. */
    protected FloorMaskFilter _floorMaskFilter = new FloorMaskFilter();

    /** Holds collected elements during queries. */
    protected ArrayList<SpaceElement> _elements = new ArrayList<SpaceElement>();

    /** Stores penetration vector during queries. */
    protected Vector2f _penetration = new Vector2f();

    /** Stores "preloads" loaded after the initial load screen. */
    protected PreloadableSet _npreloads = new PreloadableSet();
}
