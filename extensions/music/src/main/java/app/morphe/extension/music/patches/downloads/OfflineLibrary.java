/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The catalogue of downloaded tracks, shared by the downloads screen and the player.
 * <p>
 * It is read once per process and kept in memory. An index of every track is saved next to
 * the app data, so opening the screen lists thousands of downloads at once instead of reading
 * one metadata file per track. Only tracks missing from the index are read, off the main
 * thread, and the list fills in while they are.
 */
public final class OfflineLibrary {

    public interface Listener {
        /**
         * Called on the main thread whenever the catalogue changed or got further loading.
         *
         * @param loading Whether tracks are still being read.
         */
        void onLibraryChanged(boolean loading, int scanned, int total);
    }

    private static final String INDEX_FILE = "morphe_offline_index.json";

    /** How many newly read tracks are listed at once while the catalogue is first built. */
    private static final int PUBLISH_EVERY = 150;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    /** Paths deleted but maybe still in a list the worker is building. */
    private static final Set<String> deleted = ConcurrentHashMap.newKeySet();

    /** Replaced as a whole, so readers on the main thread always see a complete list. */
    private static volatile List<OfflineTrack> tracks = Collections.emptyList();
    private static volatile Map<String, OfflineTrack> byPath = Collections.emptyMap();

    private static volatile boolean loaded;
    private static volatile boolean loading;
    private static volatile int scanned;
    private static volatile int total;

    private OfflineLibrary() {
    }

    public static File root(Context context) {
        return new File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
    }

    public static List<OfflineTrack> tracks() {
        return tracks;
    }

    @Nullable
    public static OfflineTrack find(String path) {
        return path == null ? null : byPath.get(path);
    }

    public static boolean isLoading() {
        return loading || !loaded;
    }

    public static int scanned() {
        return scanned;
    }

    public static int total() {
        return total;
    }

    public static void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Loads the catalogue unless it already is in memory or on its way. */
    public static void ensureLoaded(Context context) {
        if (loaded || loading) return;
        loading = true;
        Context app = context.getApplicationContext();
        worker.execute(() -> scan(app));
    }

    /** Looks for tracks that appeared or disappeared since the catalogue was read. */
    public static void refresh(Context context) {
        if (loading) return;
        loading = true;
        Context app = context.getApplicationContext();
        worker.execute(() -> scan(app));
    }

    /** A download finished, so it joins the catalogue without a rescan. */
    public static void onDownloadSaved(File audioFile) {
        Context context = Utils.getContext();
        if (context == null) return;
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            // Before the catalogue was ever read there is nothing to add to: saving now would
            // replace the index of every download with this one track. The next scan lists it.
            if (!loaded) return;
            OfflineTrack track = OfflineTrack.load(audioFile);
            deleted.remove(track.path());
            List<OfflineTrack> updated = new ArrayList<>(tracks);
            updated.removeIf(existing -> existing.path().equals(track.path()));
            updated.add(track);
            publish(updated);
            saveIndex(app, updated);
            notifyListeners();
        });
    }

    /** Files were deleted, they leave the catalogue and the play queue. */
    public static void onTracksDeleted(Context context, Collection<OfflineTrack> removed) {
        Context app = context.getApplicationContext();
        List<String> paths = new ArrayList<>();
        for (OfflineTrack track : removed) paths.add(track.path());
        // Remembered until the worker has caught up, so a scan still running cannot bring them
        // back when it publishes what it read before the deletion.
        deleted.addAll(paths);

        publish(new ArrayList<>(tracks));
        notifyListeners();
        // Queued behind any scan or download, so it works on their final list.
        worker.execute(() -> {
            List<OfflineTrack> updated = new ArrayList<>(tracks);
            publish(updated);
            if (loaded) saveIndex(app, updated);
            deleted.removeAll(paths);
            notifyListeners();
        });
        OfflinePlaybackService.onTracksDeleted(app, paths);
    }

    private static void scan(Context context) {
        try {
            File directory = root(context);
            String[] names = directory.list((dir, name) ->
                    name.endsWith(".webm") || name.endsWith(".m4a"));
            if (names == null) names = new String[0];
            total = names.length;
            scanned = 0;

            Map<String, OfflineTrack> indexed = readIndex(context, directory);
            List<OfflineTrack> found = new ArrayList<>(names.length);
            List<String> missing = new ArrayList<>();

            for (String name : names) {
                OfflineTrack track = indexed.get(name);
                if (track != null) found.add(track);
                else missing.add(name);
            }
            scanned = found.size();
            // Everything known is shown right away, the rest fills in below.
            publish(new ArrayList<>(found));
            notifyListeners();

            int sincePublish = 0;
            for (String name : missing) {
                found.add(OfflineTrack.load(new File(directory, name)));
                scanned++;
                if (++sincePublish >= PUBLISH_EVERY) {
                    sincePublish = 0;
                    publish(new ArrayList<>(found));
                    notifyListeners();
                }
            }

            publish(found);
            if (!missing.isEmpty() || found.size() != indexed.size()) {
                saveIndex(context, found);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the offline catalogue", ex);
        } finally {
            loaded = true;
            loading = false;
            // The last session comes back whether or not a screen is listening by now, and
            // before the listeners hear about the catalogue.
            Utils.runOnMainThread(() -> OfflinePlaybackService.restoreIfIdle(context));
            notifyListeners();
        }
    }

    /** @param list Mutable, deleted tracks are dropped from it. */
    private static void publish(List<OfflineTrack> list) {
        if (!deleted.isEmpty()) list.removeIf(track -> deleted.contains(track.path()));
        Map<String, OfflineTrack> paths = new HashMap<>(list.size() * 2);
        for (OfflineTrack track : list) paths.put(track.path(), track);
        byPath = paths;
        tracks = Collections.unmodifiableList(list);
    }

    private static void notifyListeners() {
        final boolean stillLoading = loading;
        final int done = scanned;
        final int all = total;
        Utils.runOnMainThread(() -> {
            for (Listener listener : listeners) {
                listener.onLibraryChanged(stillLoading, done, all);
            }
        });
    }

    private static File indexFile(Context context) {
        return new File(context.getFilesDir(), INDEX_FILE);
    }

    /** @return Indexed tracks by audio file name, empty when there is no usable index. */
    private static Map<String, OfflineTrack> readIndex(Context context, File directory) {
        Map<String, OfflineTrack> indexed = new HashMap<>();
        File file = indexFile(context);
        if (!file.isFile()) return indexed;
        try {
            JSONArray array = new JSONArray(OfflineStorage.readText(file));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) continue;
                OfflineTrack track = OfflineTrack.fromIndex(directory, json);
                if (track != null) indexed.put(track.audioFile().getName(), track);
            }
        } catch (Exception ex) {
            // A damaged index only costs one full read, it is rebuilt afterwards.
            Logger.printException(() -> "Could not read the offline index", ex);
            indexed.clear();
        }
        return indexed;
    }

    /** Written beside and then renamed over the old index, so a kill never leaves half of it. */
    private static void saveIndex(Context context, List<OfflineTrack> list) {
        try {
            JSONArray array = new JSONArray();
            for (OfflineTrack track : list) array.put(track.toIndex());

            File target = indexFile(context);
            File temporary = new File(target.getParentFile(), INDEX_FILE + ".tmp");
            OfflineStorage.writeText(temporary, array.toString());
            if (!temporary.renameTo(target)) {
                throw new IllegalStateException("Could not replace " + target);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save the offline index", ex);
        }
    }
}
