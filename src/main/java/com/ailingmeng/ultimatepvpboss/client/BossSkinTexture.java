package com.ailingmeng.ultimatepvpboss.client;

import com.ailingmeng.ultimatepvpboss.UltimatePvpBoss;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BossSkinTexture {
    private static final ResourceLocation STEVE = DefaultPlayerSkin.getDefaultSkin();
    private static final long RETRY_DELAY_MS = 5 * 60 * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final Map<String, ResourceLocation> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();
    private static final Map<String, Long> RETRY_AFTER = new ConcurrentHashMap<>();
    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ultimatepvpboss-skin-downloader");
        thread.setDaemon(true);
        return thread;
    });

    private BossSkinTexture() {}

    public static ResourceLocation get(String username) {
        if (username == null || username.isBlank() || username.equalsIgnoreCase("Steve")) {
            return STEVE;
        }
        String key = username.toLowerCase(Locale.ROOT);
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        // Rendering calls this method every frame. A failed request must not create a new
        // thread/request on the following frame, otherwise an unavailable skin service can
        // exhaust native threads and freeze the entire client without a crash report.
        long retryAfter = RETRY_AFTER.getOrDefault(key, 0L);
        if (System.currentTimeMillis() >= retryAfter && LOADING.putIfAbsent(key, Boolean.TRUE) == null) {
            final String name = username;
            DOWNLOAD_EXECUTOR.execute(() -> download(key, name));
        }
        return STEVE;
    }

    private static void download(String key, String username) {
        ResourceLocation loc = new ResourceLocation(UltimatePvpBoss.MOD_ID, "skins/" + sanitize(key));
        try {
            URI uri;
            if (username.startsWith("http://") || username.startsWith("https://")) {
                uri = URI.create(username);
            } else {
                String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
                uri = URI.create("https://mc-heads.net/skin/" + encoded);
            }
            URLConnection connection = uri.toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "UltimatePvpBoss/1.0");
            if (connection instanceof HttpURLConnection http) {
                http.setInstanceFollowRedirects(true);
            }
            try (InputStream in = connection.getInputStream()) {
                NativeImage image = NativeImage.read(in);
                Minecraft.getInstance().execute(() -> register(key, username, loc, image));
            }
        } catch (Exception e) {
            RETRY_AFTER.put(key, System.currentTimeMillis() + RETRY_DELAY_MS);
            LOADING.remove(key);
            UltimatePvpBoss.LOGGER.warn("Skin download failed for {}; retrying in 5 minutes: {}",
                    username, e.toString());
        }
    }

    private static void register(String key, String username, ResourceLocation loc, NativeImage image) {
        try {
            DynamicTexture texture = new DynamicTexture(image);
            Minecraft.getInstance().getTextureManager().register(loc, texture);
            CACHE.put(key, loc);
            RETRY_AFTER.remove(key);
        } catch (Exception e) {
            image.close();
            RETRY_AFTER.put(key, System.currentTimeMillis() + RETRY_DELAY_MS);
            UltimatePvpBoss.LOGGER.warn("Failed to register skin {}", username, e);
        } finally {
            LOADING.remove(key);
        }
    }

    private static String sanitize(String key) {
        if (key.startsWith("http://") || key.startsWith("https://")) {
            return "url_" + Integer.toHexString(key.hashCode());
        }
        return key.replaceAll("[^a-z0-9_\\-]", "_");
    }
}
