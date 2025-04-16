package me.eldodebug.soar.management.music;

import com.github.jikyo.romaji.Transliterator;
import me.eldodebug.soar.logger.GlideLogger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Manager class for handling Japanese text romanization
 * Uses the jikyo Transliterator library to convert Japanese text to romanized form
 */
public class RomanizationManager {
    private static final long CACHE_EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000; // 7 days
    
    private final Map<String, CachedRomanization> cache = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Romanization-Service");
        t.setDaemon(true);
        return t;
    });
    
    private static class CachedRomanization {
        private final String romanized;
        private final long timestamp;
        
        public CachedRomanization(String romanized) {
            this.romanized = romanized;
            this.timestamp = System.currentTimeMillis();
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRATION_MS;
        }
        
        public String getRomanized() {
            return romanized;
        }
    }
    
    /**
     * Check if text contains Japanese characters (Hiragana, Katakana, or Kanji)
     */
    public boolean containsJapaneseCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        for (char c : text.toCharArray()) {
            // Check for Hiragana (3040-309F), Katakana (30A0-30FF), or Kanji (4E00-9FAF)
            if ((c >= 0x3040 && c <= 0x309F) || 
                (c >= 0x30A0 && c <= 0x30FF) || 
                (c >= 0x4E00 && c <= 0x9FAF)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Romanize Japanese text using the Transliterator library
     * Returns a CompletableFuture that will be completed with the romanized text,
     * or with the original text if romanization fails or is not needed
     */
    public CompletableFuture<String> romanizeText(String text) {
        if (text == null || text.isEmpty() || !containsJapaneseCharacters(text)) {
            return CompletableFuture.completedFuture(text);
        }
        
        // Check cache first
        CachedRomanization cached = cache.get(text);
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(cached.getRomanized());
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> results = Transliterator.transliterate(text);
                
                if (results != null && !results.isEmpty()) {
                    String romanized = results.get(0);
                    cache.put(text, new CachedRomanization(romanized));
                    return romanized;
                }
            } catch (Exception e) {
                GlideLogger.error("Error romanizing text: " + e.getMessage());
            }

            return text;
        }, executorService);
    }
    
    /**
     * Shut down the executor service
     */
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
}
