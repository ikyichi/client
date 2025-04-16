package me.eldodebug.soar.management.mods.impl;

import com.wrapper.spotify.model_objects.specification.Track;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.eldodebug.soar.Glide;
import me.eldodebug.soar.logger.GlideLogger;
import me.eldodebug.soar.management.color.ColorManager;
import me.eldodebug.soar.management.event.EventTarget;
import me.eldodebug.soar.management.event.impl.EventRender2D;
import me.eldodebug.soar.management.event.impl.EventUpdate;
import me.eldodebug.soar.management.event.impl.EventKey;
import me.eldodebug.soar.management.language.TranslateText;
import me.eldodebug.soar.management.mods.SimpleHUDMod;
import me.eldodebug.soar.management.mods.settings.impl.BooleanSetting;
import me.eldodebug.soar.management.mods.settings.impl.ComboSetting;
import me.eldodebug.soar.management.mods.settings.impl.TextSetting;
import me.eldodebug.soar.management.mods.settings.impl.combo.Option;
import me.eldodebug.soar.management.music.LyricsManager;
import me.eldodebug.soar.management.music.MusicManager;
import me.eldodebug.soar.management.nanovg.NanoVGManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;

public class MusicInfoMod extends SimpleHUDMod implements MusicManager.TrackInfoCallback {
    private static MusicInfoMod instance;
    private static final ResourceLocation PLACEHOLDER_IMAGE = new ResourceLocation("soar/music.png");
    private float addX;
    private boolean back;
    private final BooleanSetting iconSetting = new BooleanSetting(TranslateText.ICON, this, true);
    private final BooleanSetting showLyricsSetting = new BooleanSetting(TranslateText.SHOW_LYRICS, this, true);
    private final BooleanSetting romanizeJapaneseSetting = new BooleanSetting(TranslateText.ROMANIZE_JAPANESE, this, false);
    private final BooleanSetting enableHotkeysSetting = new BooleanSetting(TranslateText.ENABLE_HOTKEYS, this, true);
    private final ComboSetting designSetting = new ComboSetting(TranslateText.DESIGN, this, TranslateText.SIMPLE, new ArrayList<Option>(Arrays.asList(new Option(TranslateText.SIMPLE), new Option(TranslateText.ADVANCED))));
    private final TextSetting lyricsApiUrlSetting = new TextSetting(TranslateText.LYRICS_API_URL, this, "https://spotify.mopigames.gay/");
    private long trackPosition = 0L;
    private long trackDuration = 0L;
    private String currentTrackId = "";
    private float lyricsScrollOffset = 0.0f;
    private int prevLyricsLineIndex = 0;
    private long lastLyricsScrollTime = 0L;
    private static final long LYRICS_SCROLL_DURATION = 500L;
    private final int visibleLyrics = 5;
    private int cachedHeight = 85;
    private long lastVolumeChangeTime = 0L;

    public MusicInfoMod() {
        super(TranslateText.MUSIC_INFO, TranslateText.MUSIC_INFO_DESCRIPTION);
        instance = this;
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        NanoVGManager nvg = Glide.getInstance().getNanoVGManager();
        Option option = this.designSetting.getOption();
        this.updateDynamicHeight();
        if (option.getTranslate().equals((Object)TranslateText.SIMPLE)) {
            this.draw();
        } else if (option.getTranslate().equals((Object)TranslateText.ADVANCED)) {
            nvg.setupAndDraw(this::drawAdvancedNanoVG);
        }
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        this.setDraggable(true);
        MusicManager musicManager = Glide.getInstance().getMusicManager();
        if (musicManager != null && musicManager.isPlaying() && musicManager.getCurrentTrack() != null) {
            this.updateLyrics(musicManager.getCurrentTrack(), musicManager.getTrackPosition());
        }
    }

    @EventTarget
    public void onKey(EventKey event) {
        if (!this.isToggled() || !this.enableHotkeysSetting.isToggled()) {
            return;
        }

        int keyCode = event.getKeyCode();
        MusicManager musicManager = Glide.getInstance().getMusicManager();

        if (musicManager == null || !musicManager.isPlaying()) {
            return;
        }

        if (keyCode == Keyboard.KEY_UP) {
            int currentVolume = musicManager.getVolume();
            int newVolume = Math.min(100, currentVolume + 5); // Increase by 5%, capped at 100%
            musicManager.setVolume(newVolume);
            lastVolumeChangeTime = System.currentTimeMillis();
        } else if (keyCode == Keyboard.KEY_DOWN) {
            int currentVolume = musicManager.getVolume();
            int newVolume = Math.max(0, currentVolume - 5); // Decrease by 5%, minimum 0%
            musicManager.setVolume(newVolume);
            lastVolumeChangeTime = System.currentTimeMillis();
        }

        // Fixed seeking implementation
        if (keyCode == Keyboard.KEY_RIGHT) {
            // Get current position directly from MusicManager to ensure it's up-to-date
            long currentPosition = musicManager.getTrackPosition();
            long duration = trackDuration > 0 ? trackDuration : Long.MAX_VALUE;
            // Skip forward 10 seconds (10,000 ms)
            long newPosition = Math.min(currentPosition + 10000, duration);
            GlideLogger.info("Seeking from " + currentPosition + "ms to " + newPosition + "ms");
            musicManager.seekToPosition(newPosition);
        } else if (keyCode == Keyboard.KEY_LEFT) {
            // Get current position directly from MusicManager to ensure it's up-to-date
            long currentPosition = musicManager.getTrackPosition();
            // Skip backward 10 seconds (10,000 ms)
            long newPosition = Math.max(currentPosition - 10000, 0);
            GlideLogger.info("Seeking from " + currentPosition + "ms to " + newPosition + "ms");
            musicManager.seekToPosition(newPosition);
        }
    }

    private void updateDynamicHeight() {
        LyricsManager lyricsManager;
        LyricsManager.LyricsResponse lyrics;
        MusicManager musicManager = Glide.getInstance().getMusicManager();
        int baseHeight = 85;
        if (musicManager == null || !musicManager.isPlaying() || musicManager.getCurrentTrack() == null) {
            baseHeight = 75;
        } else if (this.showLyricsSetting.isToggled() && musicManager.getLyricsManager() != null && (lyrics = (lyricsManager = musicManager.getLyricsManager()).getCurrentLyrics()) != null && !lyrics.isError() && !lyrics.getLines().isEmpty()) {
            baseHeight = 110 + this.visibleLyrics * 12;
        }
        this.cachedHeight = baseHeight;
        this.setHeight(baseHeight);
    }

    private void updateLyrics(Track currentTrack, long position) {
        if (!this.showLyricsSetting.isToggled() || currentTrack == null) {
            return;
        }
        MusicManager musicManager = Glide.getInstance().getMusicManager();
        if (musicManager == null || musicManager.getLyricsManager() == null) {
            return;
        }
        LyricsManager lyricsManager = musicManager.getLyricsManager();
        if (!currentTrack.getId().equals(this.currentTrackId)) {
            this.currentTrackId = currentTrack.getId();
            lyricsManager.reset();
            lyricsManager.fetchLyrics(currentTrack).thenAcceptAsync(lyrics -> {
                if (lyrics != null && !lyrics.isError() && !lyrics.getLines().isEmpty()) {
                    if (this.romanizeJapaneseSetting.isToggled()) {
                        lyricsManager.processLyricsRomanization(lyrics);
                    }
                }
            });
        }
        lyricsManager.updateCurrentLineIndex(position);
    }

    private void drawAdvancedNanoVG() {
        LyricsManager lyricsManager;
        LyricsManager.LyricsResponse lyrics;
        MusicManager musicManager = Glide.getInstance().getMusicManager();
        boolean hasLyrics = false;
        int baseHeight = this.cachedHeight;
        if (this.showLyricsSetting.isToggled() && musicManager != null && musicManager.isPlaying() && musicManager.getCurrentTrack() != null && musicManager.getLyricsManager() != null && (lyrics = (lyricsManager = musicManager.getLyricsManager()).getCurrentLyrics()) != null && !lyrics.getLines().isEmpty()) {
            hasLyrics = true;
        }
        this.drawBackground(155.0f, baseHeight);
        if (musicManager.isPlaying() && musicManager.getCurrentTrack() != null) {
            Track currentTrack = musicManager.getCurrentTrack();
            String albumArtUrl = musicManager.getAlbumArtUrl(currentTrack);
            if (albumArtUrl != null && !albumArtUrl.isEmpty()) {
                File albumArtFile = new File(albumArtUrl);
                if (albumArtFile.exists()) {
                    this.drawRoundedImage(albumArtFile, 5.5f, 25.0f, 37.0f, 37.0f, 6.0f);
                } else {
                    this.drawRoundedImage(PLACEHOLDER_IMAGE, 5.5f, 25.0f, 37.0f, 37.0f, 6.0f);
                }
            } else {
                this.drawRoundedImage(PLACEHOLDER_IMAGE, 5.5f, 25.0f, 37.0f, 37.0f, 6.0f);
            }
            this.save();
            this.scissor(0.0f, 0.0f, 155.0f, baseHeight);
            this.drawText(TranslateText.NOW_PLAYING.getText(), 5.5f, 6.0f, 10.5f, this.getHudFont(3), new Color(255, 255, 255, 80));
            String trackName = currentTrack.getName();
            String artistNames = String.join((CharSequence)", ", (CharSequence[])Arrays.stream(currentTrack.getArtists()).map(artist -> artist.getName()).toArray(String[]::new));
            List<String> trackNameLines = this.breakTextIntoLines(trackName, 95.0f);
            float trackNameY = 25.0f;
            for (String line : trackNameLines) {
                this.drawText(line, 47.0f, trackNameY, 10.5f, this.getHudFont(2), new Color(255, 255, 255, 80));
                trackNameY += 12.0f;
            }
            float artistY = trackNameY + 2.0f;
            this.drawText(artistNames, 47.0f, artistY, 9.5f, this.getHudFont(1), new Color(255, 255, 255, 80));
            this.restore();
            float current = musicManager.getCurrentTime();
            float end = musicManager.getEndTime();
            String currentTime = this.formatTime((long)current);
            String totalTime = this.formatTime((long)end);
            float progressBarY = 70.5f;
            float progressFactor = current / end;

            // Use improved progress bar design from main menu
            this.drawRoundedRect(6.0f, progressBarY, 142.5f, 2.5f, 1.3f,
                    new Color(255, 255, 255, 80));
            this.drawRoundedRect(6.0f, progressBarY, progressFactor * 142.5f, 2.5f, 1.3f,
                    new Color(255, 255, 255, 180));

            float timeY = progressBarY + 6.0f;
            this.drawText(currentTime, 6.0f, timeY, 6.0f, this.getHudFont(1));
            float totalTimeWidth = this.getTextWidth(totalTime, 9.0f, this.getHudFont(1));
            this.drawText(totalTime, 163.0f - totalTimeWidth - 5.5f, timeY, 6.0f, this.getHudFont(1));
            
            if (hasLyrics && this.showLyricsSetting.isToggled()) {
                float lyricsHeaderY = timeY + 15.0f;
                LyricsManager lyricsManager2 = musicManager.getLyricsManager();
                List<LyricsManager.LyricsLine> visibleLines = lyricsManager2.getVisibleLines(this.visibleLyrics);
                
                if (visibleLines != null && !visibleLines.isEmpty()) {
                    // Begin scissoring (clipping) for lyrics section
                    this.save();
                    float lyricsAreaTop = lyricsHeaderY;
                    float lyricsAreaHeight = baseHeight - lyricsHeaderY - 5.0f; // 5px padding at bottom
                    this.scissor(0, lyricsAreaTop, 145.0f, lyricsAreaHeight + 4.0f);
                    
                    int currentLineIndex = lyricsManager2.getCurrentLineIndex();
                    this.updateLyricsScrollAnimation(currentLineIndex);
                    float lyricsY = lyricsAreaTop;
                    float lineHeight = 16.0f;
                    float yOffset = this.lyricsScrollOffset * lineHeight;
                    
                    for (int i = 0; i < visibleLines.size(); ++i) {
                        LyricsManager.LyricsLine line = visibleLines.get(i);
                        if (line == null) continue;
                        int actualIndex = Math.max(0, currentLineIndex - visibleLines.size() / 2) + i;
                        boolean isCurrentLine = actualIndex == currentLineIndex;
                        String text = line.getWords();
                        
                        // Use romanized text if available and the setting is enabled
                        if (this.romanizeJapaneseSetting.isToggled() && line.getRomanizedWords() != null) {
                            text = line.getRomanizedWords();
                        }
                        
                        if (text != null && !text.isEmpty()) {
                            String limitedText = Glide.getInstance().getNanoVGManager().getLimitText(text, 9.0f, this.getHudFont(1), 140.0f);
                            float xPos = 5.0f;
                            if (isCurrentLine) {
                                this.drawText(limitedText, xPos, lyricsY + yOffset, 9.0f, this.getHudFont(2), new Color(255, 255, 255, 180));
                            } else {
                                this.drawText(limitedText, xPos, lyricsY + yOffset, 9.0f, this.getHudFont(1), new Color(255, 255, 255, 80));
                            }
                        }
                        lyricsY += lineHeight;
                    }
                    
                    // End scissoring
                    this.restore();
                } else {
                    String noLyricsText = "No lyrics available";
                    float textWidth = this.getTextWidth(noLyricsText, 10.0f, this.getHudFont(1));
                    float centerX = 77.5f;
                    this.drawText(noLyricsText, centerX - textWidth / 2.0f, lyricsHeaderY + 20.0f, 10.0f, this.getHudFont(1), new Color(200, 200, 200));
                }
            }
        } else {
            this.drawText(TranslateText.NOTHING_IS_PLAYING.getText(), 5.5f, 6.0f, 10.5f, this.getHudFont(3), new Color(255, 255, 255, 80));
            this.drawRoundedImage(PLACEHOLDER_IMAGE, 5.5f, 25.0f, 37.0f, 37.0f, 6.0f);
            float progressBarY = 67.5f;
            // Use consistent progress bar design even when nothing is playing
            this.drawRoundedRect(6.0f, progressBarY, 142.5f, 2.5f, 1.3f, 
                    new Color(255, 255, 255, 80));
        }
        this.setWidth(155);
    }

    private String formatTime(long seconds) {
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        return String.format("%02d:%02d", minutes, remainingSeconds);
    }

    private void updateLyricsScrollAnimation(int currentLineIndex) {
        if (currentLineIndex != this.prevLyricsLineIndex) {
            int linesToScroll = currentLineIndex - this.prevLyricsLineIndex;
            this.lyricsScrollOffset = linesToScroll;
            this.lastLyricsScrollTime = System.currentTimeMillis();
            this.prevLyricsLineIndex = currentLineIndex;
        }
        
        if (this.lyricsScrollOffset != 0.0f) {
            long currentTime = System.currentTimeMillis();
            long timeSinceScroll = currentTime - this.lastLyricsScrollTime;
            if (timeSinceScroll >= LYRICS_SCROLL_DURATION) {
                this.lyricsScrollOffset = 0.0f;
            } else {
                float progress = (float)timeSinceScroll / LYRICS_SCROLL_DURATION;
                progress = this.easeOutCubic(progress);
                this.lyricsScrollOffset = this.lyricsScrollOffset > 0.0f ? 
                    (1.0f - progress) * this.lyricsScrollOffset : 
                    (1.0f - progress) * this.lyricsScrollOffset;
            }
        }
    }

    private float easeOutCubic(float t) {
        return 1.0f - (float)Math.pow(1.0f - t, 3.0);
    }

    private List<String> breakTextIntoLines(String text, float maxWidth) {
        ArrayList<String> lines = new ArrayList<String>();
        NanoVGManager nvgManager = Glide.getInstance().getNanoVGManager();
        if (this.getTextWidth(text, 10.5f, this.getHudFont(1)) <= maxWidth) {
            lines.add(text);
            return lines;
        }
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        for (String word : words) {
            String testLine;
            String string = testLine = currentLine.length() > 0 ? currentLine + " " + word : word;
            if (this.getTextWidth(testLine, 10.5f, this.getHudFont(1)) <= maxWidth) {
                currentLine = new StringBuilder(testLine);
                continue;
            }
            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
            }
            currentLine = new StringBuilder(word);
        }
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        if (lines.size() > 2) {
            String lastLine = (String)lines.get(1);
            if (lastLine.length() > 3) {
                lines.set(1, lastLine.substring(0, lastLine.length() - 3) + "...");
            }
            return lines.subList(0, 2);
        }
        return lines;
    }

    @Override
    public String getText() {
        MusicManager musicManager = Glide.getInstance().getMusicManager();
        if (musicManager.isPlaying()) {
            Track currentTrack = musicManager.getCurrentTrack();
            return currentTrack != null ? "Now Playing: " + currentTrack.getName() : "Nothing is Playing";
        }
        return "Nothing is Playing";
    }

    @Override
    public String getIcon() {
        return this.iconSetting.isToggled() ? "9" : null;
    }

    @Override
    public void onTrackInfoUpdated(long position, long duration) {
        this.trackPosition = position;
        this.trackDuration = duration;
        MusicManager musicManager = Glide.getInstance().getMusicManager();
        if (musicManager != null && musicManager.getLyricsManager() != null) {
            musicManager.getLyricsManager().updateCurrentLineIndex(position);
            int newLineIndex = musicManager.getLyricsManager().getCurrentLineIndex();
            if (newLineIndex != this.prevLyricsLineIndex) {
                this.lyricsScrollOffset = newLineIndex - this.prevLyricsLineIndex;
                this.lastLyricsScrollTime = System.currentTimeMillis();
                this.prevLyricsLineIndex = newLineIndex;
            }
        }
    }

    public BooleanSetting getShowLyricsSetting() {
        return this.showLyricsSetting;
    }

    public TextSetting getLyricsApiUrlSetting() {
        return this.lyricsApiUrlSetting;
    }

    public BooleanSetting getRomanizeJapaneseSetting() {
        return this.romanizeJapaneseSetting;
    }

    public BooleanSetting getEnableHotkeysSetting() {
        return this.enableHotkeysSetting;
    }

    public static MusicInfoMod getInstance() {
        return instance;
    }

    public ComboSetting getDesignSetting() {
        return this.designSetting;
    }
}
