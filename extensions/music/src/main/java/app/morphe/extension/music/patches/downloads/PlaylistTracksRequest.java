/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Lists the tracks of a playlist or an album, following every page.
 * Uses the account of the app, so private playlists such as Liked music are listed too.
 */
final class PlaylistTracksRequest {

    private static final String INNERTUBE_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
    private static final String BROWSE_URL =
            "https://music.youtube.com/youtubei/v1/browse?key=" + INNERTUBE_KEY + "&alt=json&prettyPrint=false";
    private static final String CLIENT_NAME = "WEB_REMIX";
    private static final String CLIENT_VERSION = "1.20260914.01.00";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 10_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 20_000;
    private static final int MAX_FETCH_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLISECONDS = 1000;

    /**
     * A page has about 100 tracks, so this stops a page loop the server never ends,
     * long after the largest playlist allowed (5000 tracks) is listed.
     */
    private static final int MAX_PAGES = 200;

    /** Tracks that are no longer available are shown greyed out, and cannot be downloaded. */
    private static final String GREYED_OUT_POLICY = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT";

    private PlaylistTracksRequest() {
    }

    /**
     * @param browseId Browse id of the playlist page, such as 'VLLM' for Liked music,
     *                 or 'MPREb_…' for an album.
     * @param progress Told the number of tracks listed so far after each page.
     * @return The video ids of the tracks in playlist order, or null if the first page could not be loaded.
     */
    @Nullable
    static List<String> fetchVideoIds(@NonNull String browseId, @Nullable PageCallback progress) {
        Set<String> videoIds = new LinkedHashSet<>();

        JSONObject body = newRequestBody();
        if (body == null) return null;
        try {
            body.put("browseId", browseId);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not build the playlist request", ex);
            return null;
        }

        JSONObject page = send(body);
        if (page == null) return null;

        String continuation = parseTracks(firstShelf(page), videoIds);
        int pages = 1;

        while (continuation != null && pages < MAX_PAGES) {
            if (progress != null) progress.onPage(videoIds.size());

            JSONObject continuationBody = newRequestBody();
            if (continuationBody == null) break;
            try {
                continuationBody.put("continuation", continuation);
            } catch (Exception ex) {
                break;
            }

            JSONObject continuationPage = send(continuationBody);
            if (continuationPage == null) {
                // The tracks listed so far are still downloaded.
                Logger.printInfo(() -> "Could not load playlist page " + (videoIds.size() / 100 + 1)
                        + ", stopping at " + videoIds.size() + " tracks");
                break;
            }
            continuation = parseContinuation(continuationPage, videoIds);
            pages++;
        }

        final int pageCount = pages;
        Logger.printDebug(() -> "Listed " + videoIds.size() + " tracks of " + browseId
                + " in " + pageCount + " pages");
        return new ArrayList<>(videoIds);
    }

    interface PageCallback {
        void onPage(int tracksListed);
    }

    /**
     * The tracks of the first page are in the playlist shelf for playlists,
     * and in the music shelf for albums. Suggestions are in later shelves.
     */
    @Nullable
    private static JSONObject firstShelf(JSONObject page) {
        JSONObject shelf = findObject(page, "musicPlaylistShelfRenderer");
        return shelf != null ? shelf : findObject(page, "musicShelfRenderer");
    }

    /**
     * @return The continuation of the next page, or null if this is the last page.
     */
    @Nullable
    private static String parseTracks(@Nullable JSONObject shelf, Set<String> videoIds) {
        if (shelf == null) return null;

        String continuation = parseItems(shelf.optJSONArray("contents"), videoIds);
        return continuation != null ? continuation : legacyContinuation(shelf);
    }

    @Nullable
    private static String parseContinuation(JSONObject page, Set<String> videoIds) {
        JSONArray actions = page.optJSONArray("onResponseReceivedActions");
        if (actions != null) {
            for (int i = 0; i < actions.length(); i++) {
                JSONObject action = actions.optJSONObject(i);
                JSONObject append = action == null ? null : action.optJSONObject("appendContinuationItemsAction");
                if (append != null) {
                    return parseItems(append.optJSONArray("continuationItems"), videoIds);
                }
            }
        }

        JSONObject contents = page.optJSONObject("continuationContents");
        if (contents != null) {
            JSONObject shelf = contents.optJSONObject("musicPlaylistShelfContinuation");
            if (shelf == null) shelf = contents.optJSONObject("musicShelfContinuation");
            return parseTracks(shelf, videoIds);
        }
        return null;
    }

    /**
     * @return The continuation of the next page, if the last item is one.
     */
    @Nullable
    private static String parseItems(@Nullable JSONArray items, Set<String> videoIds) {
        if (items == null) return null;

        String continuation = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            JSONObject track = item.optJSONObject("musicResponsiveListItemRenderer");
            if (track != null) {
                if (GREYED_OUT_POLICY.equals(track.optString("musicItemRendererDisplayPolicy"))) {
                    continue;
                }
                String videoId = trackVideoId(track);
                if (videoId != null) videoIds.add(videoId);
                continue;
            }

            JSONObject next = item.optJSONObject("continuationItemRenderer");
            if (next != null) {
                JSONObject command = findObject(next, "continuationCommand");
                if (command != null && !command.optString("token").isEmpty()) {
                    continuation = command.optString("token");
                }
            }
        }
        return continuation;
    }

    @Nullable
    private static String trackVideoId(JSONObject track) {
        JSONObject itemData = track.optJSONObject("playlistItemData");
        if (itemData != null && !itemData.optString("videoId").isEmpty()) {
            return itemData.optString("videoId");
        }
        // Album tracks are only identified by the endpoint of their play button.
        JSONObject watchEndpoint = findObject(track, "watchEndpoint");
        if (watchEndpoint != null && !watchEndpoint.optString("videoId").isEmpty()) {
            return watchEndpoint.optString("videoId");
        }
        return null;
    }

    @Nullable
    private static String legacyContinuation(JSONObject shelf) {
        JSONArray continuations = shelf.optJSONArray("continuations");
        if (continuations == null || continuations.length() == 0) return null;

        JSONObject next = continuations.optJSONObject(0);
        JSONObject data = next == null ? null : next.optJSONObject("nextContinuationData");
        String token = data == null ? "" : data.optString("continuation");
        return token.isEmpty() ? null : token;
    }

    /**
     * @return The first object with the given key, searched depth first.
     */
    @Nullable
    private static JSONObject findObject(@Nullable Object node, String key) {
        if (node instanceof JSONObject object) {
            JSONObject match = object.optJSONObject(key);
            if (match != null) return match;

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                JSONObject found = findObject(object.opt(keys.next()), key);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findObject(array.opt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Nullable
    private static JSONObject newRequestBody() {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", CLIENT_NAME);
            client.put("clientVersion", CLIENT_VERSION);
            client.put("hl", "en");

            JSONObject context = new JSONObject();
            context.put("client", client);
            context.put("user", new JSONObject());

            JSONObject body = new JSONObject();
            body.put("context", context);
            return body;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not build the playlist request", ex);
            return null;
        }
    }

    @Nullable
    private static JSONObject send(JSONObject body) {
        final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        for (int attempt = 1; attempt <= MAX_FETCH_ATTEMPTS; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = Requester.openConnection(BROWSE_URL);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("X-YouTube-Client-Name", "67");
                connection.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
                connection.setRequestProperty("Origin", "https://music.youtube.com");
                connection.setRequestProperty("Cookie", "SOCS=CAI");
                for (Map.Entry<String, String> header : AuthUtils.getRequestHeader().entrySet()) {
                    if (!header.getValue().isEmpty()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }

                final int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    return Requester.parseJSONObject(connection);
                }
                final int attemptNumber = attempt;
                Logger.printInfo(() -> "Playlist request returned HTTP " + responseCode
                        + " (attempt " + attemptNumber + ")");
                // The request itself is refused, so trying again cannot help.
                if (responseCode >= 400 && responseCode < 500 && responseCode != 429) return null;
            } catch (Exception ex) {
                final int attemptNumber = attempt;
                Logger.printInfo(() -> "Playlist request failed (attempt " + attemptNumber + ")", ex);
            } finally {
                if (connection != null) connection.disconnect();
            }

            try {
                Thread.sleep(RETRY_BACKOFF_MILLISECONDS * attempt);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
