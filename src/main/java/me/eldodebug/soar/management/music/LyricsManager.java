package me.eldodebug.soar.management.music;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.wrapper.spotify.model_objects.specification.Track;
import me.eldodebug.soar.Glide;
import me.eldodebug.soar.logger.GlideLogger;
import me.eldodebug.soar.management.mods.impl.MusicInfoMod;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class LyricsManager {
    private static final String DEFAULT_LYRICS_API_URL = "https://spotify.mopigames.gay/";
    private static final String TRACK_ID_PARAM = "?trackid=";
    private static final int TIMEOUT_SECONDS = 10;
    private static final long CACHE_DURATION_MS = 24 * 60 * 60 * 1000; // 24 hours

    private final Gson gson = new Gson();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Lyrics-Fetcher");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, CachedLyrics> lyricsCache = new ConcurrentHashMap<>();
    private String currentTrackId;
    private LyricsResponse currentLyrics;
    private int currentLineIndex = 0;

    public static class LyricsResponse {
        private boolean error;
        private String syncType;
        private final List<LyricsLine> lines = new ArrayList<>();

        public boolean isError() {
            return error;
        }

        public String getSyncType() {
            return syncType;
        }

        public List<LyricsLine> getLines() {
            return lines;
        }
    }

    public static class LyricsLine {
        @SerializedName("startTimeMs")
        private String startTimeMs;
        private String words;
        @SerializedName("endTimeMs")
        private String endTimeMs;
        private String romanizedWords; // Added field for romanized text

        public long getStartTime() {
            try {
                return Long.parseLong(startTimeMs);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public long getEndTime() {
            try {
                return Long.parseLong(endTimeMs);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public String getWords() {
            return words;
        }

        public String getRomanizedWords() {
            return romanizedWords;
        }

        public void setRomanizedWords(String romanizedWords) {
            this.romanizedWords = romanizedWords;
        }

        @Override
        public String toString() {
            return words;
        }
    }

    private static class CachedLyrics {
        private final LyricsResponse lyrics;
        private final long timestamp;

        public CachedLyrics(LyricsResponse lyrics) {
            this.lyrics = lyrics;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_DURATION_MS;
        }
    }

    public CompletableFuture<LyricsResponse> fetchLyrics(Track track) {
        if (track == null || track.getId() == null) {
            // Clear any existing lyrics if we're fetching for a null/invalid track
            reset();
            return CompletableFuture.completedFuture(null);
        }

        final String trackId = track.getId();

        // Check if requested track is different from current track
        if (!trackId.equals(currentTrackId)) {
            // Reset current lyrics when changing tracks
            // This ensures old lyrics aren't displayed while waiting for the API response
            reset();
        }

        // Check if lyrics are in cache and not expired
        CachedLyrics cached = lyricsCache.get(trackId);
        if (cached != null && !cached.isExpired()) {
            currentTrackId = trackId;
            currentLyrics = cached.lyrics;
            currentLineIndex = 0;
            return CompletableFuture.completedFuture(cached.lyrics);
        }

        // Get API URL from settings and format with track ID
        String apiUrl = getLyricsApiUrl(trackId);
        if (apiUrl == null) {
            GlideLogger.error("Failed to construct API URL for track: " + track.getName());
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(apiUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(TIMEOUT_SECONDS * 1000);
                connection.setReadTimeout(TIMEOUT_SECONDS * 1000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream()))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }

                        LyricsResponse lyrics = gson.fromJson(response.toString(), LyricsResponse.class);
                        if (lyrics != null && !lyrics.isError() && !lyrics.getLines().isEmpty()) {
                            lyricsCache.put(trackId, new CachedLyrics(lyrics));
                            currentTrackId = trackId;
                            currentLyrics = lyrics;
                            currentLineIndex = 0;
                            return lyrics;
                        } else {
                            // No valid lyrics or empty lyrics, ensure we remain in reset state
                            reset();
                        }
                    }
                } else {
                    GlideLogger.info("Failed to get lyrics, HTTP response code: " + responseCode);
                    reset();
                }

                return null;
            } catch (Exception e) {
                GlideLogger.error("Error fetching lyrics: " + e.getMessage());
                reset();
                return null;
            }
        }, executorService);
    }

    /**
     * Get the lyrics API URL from settings or use default if not available
     * and format it correctly with the track ID parameter
     */
    private String getLyricsApiUrl(String trackId) {
        if (trackId == null || trackId.isEmpty()) {
            return null;
        }

        try {
            MusicInfoMod musicInfoMod = MusicInfoMod.getInstance();
            if (musicInfoMod != null) {
                String baseUrl = musicInfoMod.getLyricsApiUrlSetting().getText().trim();
                if (baseUrl == null || baseUrl.isEmpty()) {
                    baseUrl = DEFAULT_LYRICS_API_URL;
                }

                // If URL already contains the trackid parameter, don't add it again
                if (baseUrl.contains("trackid=" + trackId)) {
                    return baseUrl;
                }

                // Remove trailing slash if it exists
                while (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }

                // Check if URL has a path component
                boolean hasPath = baseUrl.substring(baseUrl.indexOf("://") + 3).contains("/");

                // Check if URL already contains parameters
                boolean hasParams = baseUrl.contains("?");

                // Build final URL with proper parameter formatting
                if (hasParams) {
                    // Ensure we use & for additional parameters
                    return baseUrl + (baseUrl.endsWith("&") ? "" : "&") + "trackid=" + trackId;
                } else {
                    // No parameters yet, add path separator if needed before the query
                    if (!hasPath) {
                        return baseUrl + "/?" + "trackid=" + trackId;
                    } else {
                        return baseUrl + "?" + "trackid=" + trackId;
                    }
                }
            }
        } catch (Exception e) {
            GlideLogger.error("Error formatting lyrics API URL: " + e.getMessage());
        }

        // Fallback to default URL with proper parameter formatting
        return DEFAULT_LYRICS_API_URL + "?trackid=" + trackId;
    }

    public void reset() {
        currentTrackId = null;
        currentLyrics = null;
        currentLineIndex = 0;
    }

    public void updateCurrentLineIndex(long currentPositionMs) {
        if (currentLyrics == null || currentLyrics.getLines().isEmpty()) {
            return;
        }

        List<LyricsLine> lines = currentLyrics.getLines();
        int newIndex = 0;

        // Find the current line based on the current time
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            long startTime = line.getStartTime();

            if (startTime <= currentPositionMs) {
                newIndex = i;
            } else {
                break;
            }
        }

        currentLineIndex = newIndex;
    }

    public LyricsResponse getCurrentLyrics() {
        return currentLyrics;
    }

    public int getCurrentLineIndex() {
        return currentLineIndex;
    }

    public List<LyricsLine> getVisibleLines(int totalLines) {
        if (currentLyrics == null || currentLyrics.getLines().isEmpty()) {
            return new ArrayList<>();
        }

        List<LyricsLine> allLines = currentLyrics.getLines();
        List<LyricsLine> visibleLines = new ArrayList<>();

        // Calculate start index (center around current line)
        int halfLines = totalLines / 2;
        int startIndex = Math.max(0, currentLineIndex - halfLines);

        // Add visible lines
        for (int i = 0; i < totalLines; i++) {
            int index = startIndex + i;
            if (index < allLines.size()) {
                visibleLines.add(allLines.get(index));
            } else {
                break;
            }
        }

        return visibleLines;
    }

    public void clearCache() {
        lyricsCache.clear();
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Process lyrics for romanization
     * This is called after lyrics are fetched when romanization is enabled
     */
    public void processLyricsRomanization(LyricsResponse lyrics) {
        if (lyrics == null || lyrics.isError() || lyrics.getLines().isEmpty()) {
            return;
        }

        RomanizationManager romanizer = Glide.getInstance().getRomanizationManager();
        if (romanizer == null) {
            return;
        }

        // Process lines in batches to avoid overloading the API
        List<LyricsLine> linesToProcess = new ArrayList<>();
        for (LyricsLine line : lyrics.getLines()) {
            if (line != null && line.getWords() != null && !line.getWords().isEmpty()
                    && romanizer.containsJapaneseCharacters(line.getWords())) {
                linesToProcess.add(line);
            }
        }

        if (linesToProcess.isEmpty()) {
            return;
        }


        // Process lines in chunks to avoid sending too many requests at once
        int chunkSize = 10;
        for (int i = 0; i < linesToProcess.size(); i += chunkSize) {
            int endIndex = Math.min(i + chunkSize, linesToProcess.size());
            List<LyricsLine> chunk = linesToProcess.subList(i, endIndex);

            // Process this chunk
            for (LyricsLine line : chunk) {
                romanizer.romanizeText(line.getWords()).thenAccept(romanized -> {
                    if (romanized != null && !romanized.isEmpty()) {
                        line.setRomanizedWords(romanized);
                    }
                });

                // Add a small delay between requests to avoid rate limiting
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}