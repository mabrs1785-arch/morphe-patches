/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

public class OfflineQueueTest {
    private static OfflineQueue<String> queue(int start, String... items) {
        OfflineQueue<String> queue = new OfflineQueue<>(new Random(42));
        queue.set(Arrays.asList(items), start, false);
        return queue;
    }

    @Test public void tappingASongPlaysTheListFromThatSong() {
        OfflineQueue<String> q = queue(2, "a", "b", "c", "d");
        assertEquals("c", q.current());
        assertEquals(3, q.next());
        assertEquals("d", q.current());
        assertEquals(-1, q.next());
        assertEquals("d", q.current());
    }

    @Test public void playNextGoesRightAfterTheCurrentSong() {
        OfflineQueue<String> q = queue(1, "a", "b", "c");
        q.playNext("x");
        assertEquals(Arrays.asList("a", "b", "x", "c"), q.items());
        assertEquals("b", q.current());
        q.next();
        assertEquals("x", q.current());
    }

    @Test public void addToQueueGoesAtTheEnd() {
        OfflineQueue<String> q = queue(0, "a", "b");
        q.add("x");
        assertEquals(Arrays.asList("a", "b", "x"), q.items());
        assertEquals("a", q.current());
    }

    @Test public void anEmptyQueueStartsWithTheAddedSong() {
        OfflineQueue<String> q = new OfflineQueue<>();
        assertNull(q.current());
        q.playNext("x");
        assertEquals("x", q.current());
        q.clear();
        q.add("y");
        assertEquals("y", q.current());
    }

    @Test public void removingKeepsTheCurrentSong() {
        OfflineQueue<String> q = queue(2, "a", "b", "c", "d");
        assertFalse(q.remove(0));
        assertEquals("c", q.current());
        assertFalse(q.remove(2));
        assertEquals("c", q.current());
        assertEquals(Arrays.asList("b", "c"), q.items());
    }

    @Test public void removingTheCurrentSongMovesOn() {
        OfflineQueue<String> q = queue(1, "a", "b", "c");
        assertTrue(q.remove(1));
        assertEquals("c", q.current());
        assertTrue(q.remove(1));
        assertEquals("a", q.current());
        assertTrue(q.remove(0));
        assertNull(q.current());
        assertEquals(-1, q.index());
    }

    @Test public void removeAllDropsADeletedDownload() {
        OfflineQueue<String> q = queue(2, "a", "x", "b", "x");
        assertFalse(q.removeAll("x"));
        assertEquals(Arrays.asList("a", "b"), q.items());
        assertEquals("b", q.current());
    }

    @Test public void movingKeepsTheCurrentSongCurrent() {
        OfflineQueue<String> q = queue(2, "a", "b", "c", "d");
        q.move(0, 3);
        assertEquals(Arrays.asList("b", "c", "d", "a"), q.items());
        assertEquals("c", q.current());
        q.move(3, 0);
        assertEquals(Arrays.asList("a", "b", "c", "d"), q.items());
        assertEquals("c", q.current());
        q.move(2, 0);
        assertEquals("c", q.current());
        assertEquals(0, q.index());
    }

    @Test public void moveNextPlacesTheSongAfterTheCurrentOne() {
        OfflineQueue<String> q = queue(2, "a", "b", "c", "d", "e");
        q.moveNext(4);
        assertEquals(Arrays.asList("a", "b", "c", "e", "d"), q.items());
        q.moveNext(0);
        assertEquals(Arrays.asList("b", "c", "a", "e", "d"), q.items());
        assertEquals("c", q.current());
        q.next();
        assertEquals("a", q.current());
    }

    @Test public void shuffleKeepsTheCurrentSongFirstAndEverySong() {
        OfflineQueue<String> q = queue(2, "a", "b", "c", "d", "e", "f");
        q.setShuffle(true);
        assertTrue(q.isShuffled());
        assertEquals("c", q.current());
        assertEquals(0, q.index());
        assertEquals(new HashSet<>(Arrays.asList("a", "b", "c", "d", "e", "f")), new HashSet<>(q.items()));
        assertEquals(6, q.size());
    }

    @Test public void unshuffleRestoresTheOrderAroundTheCurrentSong() {
        OfflineQueue<String> q = queue(0, "a", "b", "c", "d", "e");
        q.setShuffle(true);
        q.next();
        q.next();
        String current = q.current();
        q.setShuffle(false);
        assertEquals(Arrays.asList("a", "b", "c", "d", "e"), q.items());
        assertEquals(current, q.current());
    }

    @Test public void songsAddedWhileShuffledSurviveUnshuffle() {
        OfflineQueue<String> q = queue(0, "a", "b", "c");
        q.setShuffle(true);
        q.add("x");
        q.playNext("y");
        q.setShuffle(false);
        List<String> items = q.items();
        assertEquals(5, items.size());
        assertTrue(items.containsAll(Arrays.asList("x", "y")));
    }

    @Test public void playNextStillPlaysNextAfterUnshuffle() {
        OfflineQueue<String> q = queue(0, "a", "b", "c", "d", "e");
        q.setShuffle(true);
        q.playNext("y");
        q.setShuffle(false);
        assertEquals("a", q.current());
        assertEquals("y", q.get(q.index() + 1));
        assertEquals(6, q.size());
    }

    @Test public void moveNextStillPlaysNextAfterUnshuffle() {
        OfflineQueue<String> q = queue(0, "a", "b", "c", "d", "e");
        q.setShuffle(true);
        q.moveNext(q.items().indexOf("e"));
        assertEquals("e", q.get(q.index() + 1));
        q.setShuffle(false);
        assertEquals(Arrays.asList("a", "e", "b", "c", "d"), q.items());
        assertEquals("a", q.current());
    }

    @Test public void restoreKeepsTheShuffleAndTheOriginalOrder() {
        OfflineQueue<String> q = new OfflineQueue<>(new Random(3));
        q.restore(Arrays.asList("c", "a", "b"), 0, Arrays.asList("a", "b", "c"));
        assertTrue(q.isShuffled());
        assertEquals(Arrays.asList("c", "a", "b"), q.items());
        assertEquals("c", q.current());
        q.setShuffle(false);
        assertEquals(Arrays.asList("a", "b", "c"), q.items());
        assertEquals("c", q.current());
    }

    @Test public void restoreForgetsDeletedSongsInBothOrders() {
        OfflineQueue<String> q = new OfflineQueue<>(new Random(3));
        q.restore(Arrays.asList("c", "a", "x"), 1, Arrays.asList("a", "b", "c"));
        assertEquals("a", q.current());
        q.setShuffle(false);
        assertEquals(Arrays.asList("a", "c", "x"), q.items());
        assertEquals("a", q.current());
    }

    @Test public void restoreWithoutAnOriginalOrderIsNotShuffled() {
        OfflineQueue<String> q = new OfflineQueue<>();
        q.restore(Arrays.asList("a", "b"), 1, null);
        assertFalse(q.isShuffled());
        assertEquals("b", q.current());
    }

    @Test public void shuffleAllStartsAnywhereAndKeepsEverySong() {
        OfflineQueue<String> q = new OfflineQueue<>(new Random(1));
        List<String> list = new ArrayList<>();
        for (int i = 0; i < 100; i++) list.add("s" + i);
        q.set(list, -1, true);
        assertTrue(q.isShuffled());
        assertEquals(0, q.index());
        assertEquals(new HashSet<>(list), new HashSet<>(q.items()));
    }

    @Test public void repeatAllWrapsAround() {
        OfflineQueue<String> q = queue(1, "a", "b");
        q.setRepeat(OfflineQueue.Repeat.ALL);
        assertEquals(0, q.next());
        assertEquals(1, q.previous(0));
        assertTrue(q.hasNext());
    }

    @Test public void repeatOneReplaysAtTheEndButSkipStillMovesOn() {
        OfflineQueue<String> q = queue(0, "a", "b");
        q.setRepeat(OfflineQueue.Repeat.ONE);
        assertEquals(0, q.afterCompletion());
        assertEquals("a", q.current());
        assertEquals(1, q.next());
    }

    @Test public void theEndOfTheQueueStopsWithoutRepeat() {
        OfflineQueue<String> q = queue(1, "a", "b");
        assertFalse(q.hasNext());
        assertEquals(-1, q.afterCompletion());
        assertEquals("b", q.current());
    }

    @Test public void previousRestartsASongThatPlayedAWhile() {
        OfflineQueue<String> q = queue(1, "a", "b");
        assertEquals(1, q.previous(OfflineQueue.RESTART_THRESHOLD_MILLISECONDS + 1));
        assertEquals("b", q.current());
        assertEquals(0, q.previous(500));
        assertEquals("a", q.current());
        assertEquals(0, q.previous(0));
    }

    @Test public void repeatCycleMatchesTheOnlineButton() {
        OfflineQueue<String> q = queue(0, "a");
        assertEquals(OfflineQueue.Repeat.ALL, q.cycleRepeat());
        assertEquals(OfflineQueue.Repeat.ONE, q.cycleRepeat());
        assertEquals(OfflineQueue.Repeat.OFF, q.cycleRepeat());
    }
}
