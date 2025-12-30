package com.example.rBooster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class MinecraftBoostAPI {

    private final rBooster plugin;
    private final ConfigManager config;

    public MinecraftBoostAPI(rBooster plugin) {
        this.plugin = plugin;
        this.config = new ConfigManager(plugin);

        if (config.getBoolean("api.enabled", true)) {
            startServer();
        }
    }

    private void startServer() {
        try {
            String host = config.getString("api.host", "0.0.0.0");
            int port = config.getInt("api.port", 3001);

            HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext(config.getString("api.endpoint", "/boost"), new BoostHandler());
            server.setExecutor(null);
            server.start();

            plugin.getLogger().info("Boost API HTTP server " + port + " portunda çalışıyor.");
        } catch (Exception e) {
            plugin.getLogger().severe("HTTP API sunucu başlatılamadı: " + e.getMessage());
        }
    }

    private class BoostHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                    exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                    return;
                }

                InputStream requestBody = exchange.getRequestBody();
                String body = new Scanner(requestBody, StandardCharsets.UTF_8).useDelimiter("\\A").next();
                plugin.getLogger().info("Gelen API isteği: " + body);

                String discordId = body.replaceAll(".*\"discordId\"\\s*:\\s*\"(.*?)\".*", "$1");
                String nick = body.replaceAll(".*\"nick\"\\s*:\\s*\"(.*?)\".*", "$1");

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayerExact(nick);
                    if (player != null && player.isOnline()) {
                        // LuckPerms komutunu configden al
                        String lpCommand = config.getString("minecraft.luckperms-command", "")
                                .replace("%nick%", nick);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), lpCommand);

                        // Action bar mesajı gönder
                        String message = config.getString("minecraft.booster-given", "");
                        player.sendMessage(message);

                        // Ses efekti
                        if (config.getBoolean("minecraft.sound.enabled", true)) {
                            try {
                                Sound sound = Sound.valueOf(config.getString("minecraft.sound.sound", "ENTITY_PLAYER_LEVELUP"));
                                float volume = (float) config.getDouble("minecraft.sound.volume", 1.0);
                                float pitch = (float) config.getDouble("minecraft.sound.pitch", 1.0);
                                player.playSound(player.getLocation(), sound, volume, pitch);
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Geçersiz ses efekti: " + config.getString("minecraft.sound.sound"));
                            }
                        }
                    } else {
                        plugin.getLogger().warning("Oyuncu çevrimdışı: " + nick);
                    }
                });

                // Başarılı yanıt
                String response = "{\"status\":\"ok\"}";
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();

            } catch (Exception e) {
                plugin.getLogger().severe("BoostHandler hatası: " + e.getMessage());
                try {
                    exchange.sendResponseHeaders(500, -1);
                } catch (Exception ignored) {}
            }
        }
    }
}