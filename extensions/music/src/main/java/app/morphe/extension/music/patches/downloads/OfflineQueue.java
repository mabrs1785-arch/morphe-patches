/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The play queue of offline playback, modelled on the queue of online playback: play next,
 * add to queue, remove, move, shuffle and repeat.
 * <p>
 * Plain Java without Android types, so every rule is covered by unit tests. Not thread safe,
 * the player only touches it from the main thread.
 */
public final class OfflineQueue<T> {

    public enum Repeat {OFF, ALL, ONE}

    /** Restarting the track instead of going back matches online playback. */
    public static final int RESTART_THRESHOLD_MILLISECONDS = 3000;

    private final List<T> items = new ArrayList<>();

    /** The order before shuffling, restored when shuffle is turned off. */
    private final List<T> unshuffled = new ArrayList<>();

    private int index = -1;
    private boolean shuffled;
    private Repeat repeat = Repeat.OFF;
    private final Random random;

    public OfflineQueue() {
        this(new Random());
    }

    OfflineQueue(Random random) {
        this.random = random;
    }

    /**
     * Replaces the queue, like tapping a song of a list plays that list from the song.
     *
     * @param start   Item to play first, or -1 for a random one when shuffling.
     * @param shuffle Whether the rest of the list plays in a random order.
     */
    public void set(List<T> list, int start, boolean shuffle) {
        items.clear();
        unshuffled.clear();
        items.addAll(list);
        shuffled = false;
        if (items.isEmpty()) {
            index = -1;
            return;
        }
        if (start < 0 || start >= items.size()) {
            start = shuffle ? random.nextInt(items.size()) : 0;
        }
        index = start;
        if (shuffle) setShuffle(true);
    }

    public void clear() {
        set(Collections.emptyList(), -1, false);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public int index() {
        return index;
    }

    @Nullable
    public T current() {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    public T get(int position) {
        return items.get(position);
    }

    /** A copy, so the caller can keep it while the queue changes. */
    public List<T> items() {
        return new ArrayList<>(items);
    }

    public boolean isShuffled() {
        return shuffled;
    }

    public Repeat repeat() {
        return repeat;
    }

    public void setRepeat(Repeat repeat) {
        this.repeat = repeat;
    }

    public Repeat cycleRepeat() {
        repeat = switch (repeat) {
            case OFF -> Repeat.ALL;
            case ALL -> Repeat.ONE;
            case ONE -> Repeat.OFF;
        };
        return repeat;
    }

    /** Inserts right after the current item. An empty queue starts with it. */
    public void playNext(T item) {
        if (items.isEmpty()) {
            set(Collections.singletonList(item), 0, false);
            return;
        }
        items.add(index + 1, item);
        if (shuffled) unshuffled.add(item);
    }

    /** Appends at the end. An empty queue starts with it. */
    public void add(T item) {
        if (items.isEmpty()) {
            set(Collections.singletonList(item), 0, false);
            return;
        }
        items.add(item);
        if (shuffled) unshuffled.add(item);
    }

    /**
     * Removes one item. Removing the current one makes the next one current, or the previous
     * one when it was the last.
     *
     * @return Whether the current item changed.
     */
    public boolean remove(int position) {
        if (position < 0 || position >= items.size()) return false;
        T removed = items.remove(position);
        if (shuffled) unshuffled.remove(removed);

        if (items.isEmpty()) {
            index = -1;
            return true;
        }
        if (position < index) {
            index--;
            return false;
        }
        if (position == index) {
            if (index >= items.size()) index = items.size() - 1;
            return true;
        }
        return false;
    }

    /** Removes every occurrence of an item, for a download that was deleted. */
    public boolean removeAll(T item) {
        boolean currentChanged = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).equals(item)) currentChanged |= remove(i);
        }
        unshuffled.remove(item);
        return currentChanged;
    }

    /** Moves an item, the current item stays current wherever it ends up. */
    public void move(int from, int to) {
        if (from < 0 || from >= items.size() || to < 0 || to >= items.size() || from == to) return;
        T item = items.remove(from);
        items.add(to, item);

        if (from == index) {
            index = to;
        } else if (from < index && to >= index) {
            index--;
        } else if (from > index && to <= index) {
            index++;
        }
    }

    /** Moves an item right after the current one. */
    public void moveNext(int position) {
        if (position == index || index < 0) return;
        move(position, position < index ? index : index + 1);
    }

    public void jumpTo(int position) {
        if (position >= 0 && position < items.size()) index = position;
    }

    /**
     * Turning shuffle on keeps the current item first and shuffles everything else after it,
     * turning it off restores the original order around the current item.
     */
    public void setShuffle(boolean enable) {
        if (enable == shuffled || items.isEmpty()) {
            shuffled = enable && !items.isEmpty();
            return;
        }
        T current = current();
        if (enable) {
            unshuffled.clear();
            unshuffled.addAll(items);
            List<T> rest = new ArrayList<>(items);
            rest.remove(index);
            Collections.shuffle(rest, random);
            items.clear();
            items.add(current);
            items.addAll(rest);
            index = 0;
            shuffled = true;
        } else {
            items.clear();
            items.addAll(unshuffled);
            unshuffled.clear();
            index = Math.max(0, items.indexOf(current));
            shuffled = false;
        }
    }

    public boolean toggleShuffle() {
        setShuffle(!shuffled);
        return shuffled;
    }

    /**
     * What a skip forward lands on. Repeat one still moves on, like the skip button of online
     * playback, while the end of a track replays it (see {@link #afterCompletion()}).
     *
     * @return The new index, or -1 when there is nothing after the current item.
     */
    public int next() {
        if (items.isEmpty()) return -1;
        if (index + 1 < items.size()) return ++index;
        if (repeat != Repeat.OFF) return index = 0;
        return -1;
    }

    /**
     * @param positionMilliseconds How far the current track has played.
     * @return The new index, the same index to restart the track, or -1 when the queue is empty.
     */
    public int previous(int positionMilliseconds) {
        if (items.isEmpty()) return -1;
        if (positionMilliseconds > RESTART_THRESHOLD_MILLISECONDS) return index;
        if (index > 0) return --index;
        if (repeat == Repeat.ALL) return index = items.size() - 1;
        return index;
    }

    /** @return The index to play once the current track ended, or -1 to stop. */
    public int afterCompletion() {
        if (items.isEmpty()) return -1;
        if (repeat == Repeat.ONE) return index;
        return next();
    }

    public boolean hasNext() {
        return !items.isEmpty() && (index + 1 < items.size() || repeat != Repeat.OFF);
    }
}
