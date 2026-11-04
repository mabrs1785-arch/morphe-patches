/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Decodes the covers of the downloads screen off the main thread.
 * <p>
 * The catalogue can hold thousands of tracks, and decoding a cover for every one of them while
 * the screen is built blocks the app long enough for the system to close it. Covers are only
 * decoded for the rows that are shown, the most recent request first, and kept in a small cache
 * for scrolling back.
 */
final class OfflineArtworkLoader {

    /**
     * Over a hundred row covers. The pixels of a bitmap are not on the Java heap, so the budget is
     * fixed rather than taken from the heap size.
     */
    private static final int CACHE_BYTES = 16 * 1024 * 1024;

    private static final int DECODE_THREADS = 2;
    private static final long IDLE_THREAD_SECONDS = 30;

    private static final LruCache<String, Bitmap> cache = new LruCache<>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };

    /**
     * Requests of a list that reuses its rows are last in, first out, so the rows a fast scroll
     * stops on are decoded before the rows it passed. Requests of rows that were reused for another
     * track by then are dropped unread. Pages that build all their rows at once, top to bottom,
     * are decoded in that order instead.
     */
    private static final ThreadPoolExecutor decoder = new ThreadPoolExecutor(
            DECODE_THREADS, DECODE_THREADS, IDLE_THREAD_SECONDS, TimeUnit.SECONDS,
            new LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable task) {
                    if (task instanceof DecodeTask decode && !decode.newestFirst) {
                        return offerLast(task);
                    }
                    return offerFirst(task);
                }
            },
            task -> {
                Thread thread = new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    task.run();
                }, "morphe-offline-artwork");
                thread.setDaemon(true);
                return thread;
            });

    static {
        decoder.allowCoreThreadTimeOut(true);
        registerMemoryCallbacks();
    }

    /**
     * The cache is static, and the player keeps the process alive in the background, so the
     * covers are let go once the screens of the app are hidden, or when memory runs low.
     */
    private static void registerMemoryCallbacks() {
        try {
            Utils.getContext().registerComponentCallbacks(new ComponentCallbacks2() {
                @Override
                public void onTrimMemory(int level) {
                    if (level >= TRIM_MEMORY_UI_HIDDEN) {
                        cache.evictAll();
                    } else if (level >= TRIM_MEMORY_RUNNING_LOW) {
                        cache.trimToSize(cache.maxSize() / 2);
                    }
                }

                @Override
                public void onConfigurationChanged(Configuration configuration) {
                }

                @Override
                public void onLowMemory() {
                    cache.evictAll();
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Could not register the memory callbacks", ex);
        }
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OfflineArtworkLoader() {
    }

    /**
     * Shows the cover of a track, or the placeholder until it is decoded. Must be called on the
     * main thread.
     * <p>
     * A row of a list is reused for another track while the cover of the previous one may still
     * be decoding, so the view is tagged with the cover it waits for, and a result is only shown
     * by a view that still waits for it.
     *
     * @param maxSize     Longest edge to decode, in pixels.
     * @param newestFirst If this request goes before the ones already waiting, which suits a list
     *                    that reuses its rows. Otherwise requests are decoded in the order made.
     */
    static void load(ImageView view, File artworkFile, int maxSize, OfflineUi ui,
                     boolean newestFirst) {
        String key = artworkFile.getAbsolutePath() + '@' + maxSize;

        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.setTag(key);
            ui.showArtwork(view, cached);
            return;
        }

        // Already waiting for this cover, or already showing it after it left the cache, as when
        // a list is redrawn to mark the track that started playing.
        if (key.equals(view.getTag())) return;

        view.setTag(key);
        ui.showArtwork(view, null);
        decoder.execute(new DecodeTask(view, artworkFile, maxSize, key, ui, newestFirst));
    }

    /**
     * Drops the covers that a screen still waits for, once that screen is gone. Requests of other
     * screens are kept.
     */
    static void cancelPending(OfflineUi owner) {
        decoder.getQueue().removeIf(task -> task instanceof DecodeTask decode && decode.owner == owner);
    }

    /**
     * Lets go of the cached covers, for when the screen showing them stops. The player keeps the
     * process alive in the background, where the covers would otherwise stay for as long as it
     * plays. Rows still on screen keep theirs.
     */
    static void evictAll() {
        cache.evictAll();
    }

    private static final class DecodeTask implements Runnable {
        /** Weak, so a request still queued does not keep a closed screen in memory. */
        private final WeakReference<ImageView> target;
        private final File artworkFile;
        private final int maxSize;
        private final String key;
        private final OfflineUi owner;
        private final boolean newestFirst;

        DecodeTask(ImageView view, File artworkFile, int maxSize, String key, OfflineUi owner,
                   boolean newestFirst) {
            this.target = new WeakReference<>(view);
            this.artworkFile = artworkFile;
            this.maxSize = maxSize;
            this.key = key;
            this.owner = owner;
            this.newestFirst = newestFirst;
        }

        @Override
        public void run() {
            try {
                ImageView waiting = target.get();
                if (waiting == null || !key.equals(waiting.getTag())) return;

                Bitmap bitmap = cache.get(key);
                if (bitmap == null) {
                    // Rows and the mini player crop the cover to a square.
                    bitmap = OfflineStorage.decodeSquareArtwork(artworkFile, maxSize);
                    // A track saved without a cover keeps the placeholder.
                    if (bitmap == null) return;
                    cache.put(key, bitmap);
                }

                Bitmap decoded = bitmap;
                mainHandler.post(() -> {
                    ImageView shown = target.get();
                    if (shown != null && key.equals(shown.getTag())) {
                        owner.showArtwork(shown, decoded);
                    }
                });
            } catch (Throwable ex) {
                // A cover that cannot be decoded, even for lack of memory, only costs its row
                // the placeholder.
                Logger.printException(() -> "Could not decode cover: " + artworkFile, ex);
            }
        }
    }
}
