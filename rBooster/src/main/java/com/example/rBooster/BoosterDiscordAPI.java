package com.example.rBooster;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class BoosterDiscordAPI extends ListenerAdapter {

    private final rBooster plugin;
    private JDA jda;
    private final ConfigManager config;

    private final File cooldownFile;
    private final Gson gson = new Gson();
    private final Map<String, Long> lastUsedMap = new ConcurrentHashMap<>();
    private long cooldownMillis;

    public BoosterDiscordAPI(rBooster plugin) {
        this.plugin = plugin;
        this.config = new ConfigManager(plugin);

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        cooldownFile = new File(plugin.getDataFolder(),
                config.getString("general.cooldown-file", "cooldowns.json"));
        cooldownMillis = config.getInt("discord.cooldown-days", 10) * 24L * 60 * 60 * 1000;

        loadCooldowns();
        startBot();
    }

    private void startBot() {
        new Thread(() -> {
            while (true) {
                try {
                    List<String> intentNames = config.getStringList("general.intents");
                    EnumSet<GatewayIntent> intents = EnumSet.noneOf(GatewayIntent.class);

                    intents.add(GatewayIntent.GUILD_MESSAGES);
                    intents.add(GatewayIntent.MESSAGE_CONTENT);
                    intents.add(GatewayIntent.GUILD_MEMBERS);

                    for (String intentName : intentNames) {
                        try {
                            intents.add(GatewayIntent.valueOf(intentName));
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Geçersiz intent: " + intentName);
                        }
                    }

                    String botToken = config.getString("discord.bot-token");
                    if (botToken == null || botToken.isEmpty()) {
                        plugin.getLogger().severe("Discord bot tokenı config.yml'de bulunamadı!");
                        return;
                    }

                    jda = JDABuilder.createDefault(botToken, intents)
                            .addEventListeners(this)
                            .build()
                            .awaitReady();

                    plugin.getLogger().info("✅ Discord bot başarıyla bağlandı.");
                    break;
                } catch (Exception e) {
                    plugin.getLogger().warning("❌ Discord bot bağlanamadı: " + e.getMessage());
                    plugin.getLogger().info("🔁 10 saniye sonra yeniden deneniyor...");
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    private synchronized void saveCooldowns() {
        try (FileWriter writer = new FileWriter(cooldownFile)) {
            gson.toJson(lastUsedMap, writer);
        } catch (Exception e) {
            plugin.getLogger().severe("Cooldown dosyası kaydedilemedi: " + e.getMessage());
        }
    }

    private synchronized void loadCooldowns() {
        if (!cooldownFile.exists()) return;

        try (FileReader reader = new FileReader(cooldownFile)) {
            Type type = new TypeToken<Map<String, Number>>() {}.getType();
            Map<String, Number> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                for (Map.Entry<String, Number> entry : loaded.entrySet()) {
                    lastUsedMap.put(entry.getKey(), entry.getValue().longValue());
                }
            }
            plugin.getLogger().info("Cooldown verileri yüklendi.");
        } catch (Exception e) {
            plugin.getLogger().severe("Cooldown dosyası yüklenirken hata: " + e.getMessage());
        }
    }

    @Override
    public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        Member member = event.getMember();
        if (member == null) return;

        String msg = event.getMessage().getContentRaw().trim();
        String channelId = event.getChannel().getId();

        String verifyChannelId = config.getString("discord.verify-channel-id");
        if (verifyChannelId == null) {
            plugin.getLogger().warning("verify-channel-id config'de bulunamadı!");
            return;
        }

        boolean isInVerifyChannel = channelId.equals(verifyChannelId);
        boolean isBoostermesajiCommand = msg.startsWith("!boostermesaji");

        if (isInVerifyChannel) {
            boolean isAdmin = member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR);
            if (!(isAdmin && isBoostermesajiCommand)) {
                event.getMessage().delete().queue(
                        null,
                        error -> plugin.getLogger().warning("Mesaj silinemedi: " + error.getMessage())
                );
            }
        }

        if (!isInVerifyChannel && isBoostermesajiCommand) {
            return;
        }

        if (isInVerifyChannel && isBoostermesajiCommand) {
            if (!member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) {
                sendTempMessage(event, config.getString("discord.messages.no-admin-permission", "❌ Bu komutu sadece yöneticiler kullanabilir!"));
                return;
            }

            Button button = createBoostButton();

            String message = config.getString("discord.boostermesaji-content");
            if (message == null || message.isEmpty()) {
                message = "**BOOSTER ALMA SİSTEMİ**\n\nAşağıdaki butona tıklayarak boost alabilirsiniz.";
            }

            event.getChannel().sendMessage(message)
                    .setActionRow(button)
                    .queue();
            return;
        }
    }

    @Override
    public void onButtonInteraction(@Nonnull ButtonInteractionEvent event) {
        String buttonId = event.getComponentId();
        String boostButtonId = config.getString("discord.button.id", "boost_button");

        if (buttonId.equals(boostButtonId)) {
            Member member = event.getMember();
            if (member == null) return;

            String allowedRoleId = config.getString("discord.allowed-role-id");
            if (allowedRoleId == null || allowedRoleId.isEmpty()) {
                event.reply("❌ Sistem hatası: Rol ID'si bulunamadı!")
                        .setEphemeral(true)
                        .queue();
                return;
            }

            boolean hasPermission = member.getRoles().stream()
                    .anyMatch(role -> role.getId().equals(allowedRoleId));

            if (!hasPermission) {
                event.reply(config.getString("discord.messages.no-permission", "❌ Boost rolünüz yok!"))
                        .setEphemeral(true)
                        .queue();
                return;
            }

            String userId = event.getUser().getId();
            long now = Instant.now().toEpochMilli();

            if (lastUsedMap.containsKey(userId)) {
                long lastUsed = lastUsedMap.get(userId);
                long elapsed = now - lastUsed;

                if (elapsed < cooldownMillis) {
                    long remaining = (cooldownMillis - elapsed) / 1000;
                    long hours = remaining / 3600;
                    long minutes = (remaining % 3600) / 60;

                    String cooldownMsg = config.getString("discord.messages.cooldown-message", "⏳ Bu butonu tekrar kullanmak için %hours% saat %minutes% dakika beklemelisin.")
                            .replace("%hours%", String.valueOf(hours))
                            .replace("%minutes%", String.valueOf(minutes));

                    event.reply(cooldownMsg)
                            .setEphemeral(true)
                            .queue();
                    return;
                }
            }

            TextInput nickInput = TextInput.create("minecraft_nick",
                            config.getString("discord.modal.input-label", "Minecraft Nickiniz"),
                            TextInputStyle.SHORT)
                    .setPlaceholder(config.getString("discord.modal.placeholder", "Örn: RdBuLL5"))
                    .setMinLength(3)
                    .setMaxLength(16)
                    .setRequired(true)
                    .build();

            Modal modal = Modal.create("boost_modal_" + userId,
                            config.getString("discord.modal.title", "Boost Alma Sistemi"))
                    .addActionRows(ActionRow.of(nickInput))
                    .build();

            event.replyModal(modal).queue();
        }
    }

    @Override
    public void onModalInteraction(@Nonnull ModalInteractionEvent event) {
        String modalId = event.getModalId();
        String userId = event.getUser().getId();

        if (modalId.startsWith("boost_modal_")) {
            String minecraftNick = event.getValue("minecraft_nick").getAsString().trim();

            if (minecraftNick.isEmpty()) {
                event.reply(config.getString("discord.messages.usage", "❗ Lütfen bir Minecraft nicki giriniz."))
                        .setEphemeral(true)
                        .queue();
                return;
            }

            event.deferReply().setEphemeral(true).queue();

            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    HttpClient client = HttpClient.newHttpClient();

                    String apiHost = config.getString("api.host", "localhost");
                    int apiPort = config.getInt("api.port", 3001);
                    String apiEndpoint = config.getString("api.endpoint", "/boost");

                    String apiUrl = "http://" + apiHost + ":" + apiPort + apiEndpoint;

                    String jsonBody = "{\"discordId\":\"" + userId + "\",\"nick\":\"" + minecraftNick + "\"}";

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(apiUrl))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                            .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        lastUsedMap.put(userId, Instant.now().toEpochMilli());
                        saveCooldowns();

                        String successMsg = config.getString("discord.messages.success-pm", "✅ `%nick%` adlı oyuncuya 10 gün booster verildi!")
                                .replace("%nick%", minecraftNick);

                        event.getHook().sendMessage(successMsg).queue();
                    } else {
                        event.getHook().sendMessage(config.getString("discord.messages.api-failed", "❌ API isteği başarısız.")).queue();
                    }
                } catch (Exception ex) {
                    plugin.getLogger().severe("API isteği hatası: " + ex.getMessage());
                    String errorMsg = config.getString("discord.messages.server-error", "❌ Sunucu hatası: %error%")
                            .replace("%error%", ex.getMessage());

                    event.getHook().sendMessage(errorMsg).queue();
                }
            });
        }
    }

    private Button createBoostButton() {
        String buttonId = config.getString("discord.button.id", "boost_button");
        String buttonLabel = config.getString("discord.button.label", "🎁 Boost Al");
        String buttonEmoji = config.getString("discord.button.emoji", "🎮");
        String buttonColor = config.getString("discord.button.color", "PRIMARY");

        ButtonStyle style = getButtonStyle(buttonColor);

        Button button = Button.of(style, buttonId, buttonLabel);

        if (buttonEmoji != null && !buttonEmoji.isEmpty()) {
            try {
                if (buttonEmoji.matches("\\p{So}+")) {
                    button = button.withEmoji(Emoji.fromUnicode(buttonEmoji));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Emoji eklenirken hata: " + e.getMessage());
            }
        }

        return button;
    }

    private ButtonStyle getButtonStyle(String color) {
        if (color == null) return ButtonStyle.PRIMARY;

        switch (color.toUpperCase()) {
            case "PRIMARY": return ButtonStyle.PRIMARY;
            case "SECONDARY": return ButtonStyle.SECONDARY;
            case "SUCCESS": return ButtonStyle.SUCCESS;
            case "DANGER": return ButtonStyle.DANGER;
            default: return ButtonStyle.PRIMARY;
        }
    }

    private void sendTempMessage(MessageReceivedEvent event, String message) {
        event.getChannel().sendMessage(message)
                .queue(m -> m.delete().queueAfter(
                        config.getInt("general.auto-delete-time", 2),
                        TimeUnit.SECONDS
                ));
    }

    public void shutdown() {
        if (jda != null) {
            try {
                jda.shutdown();
                plugin.getLogger().info("Discord bot kapatıldı.");
            } catch (Exception e) {
                plugin.getLogger().warning("Discord bot kapatılırken hata: " + e.getMessage());
            }
        }
        saveCooldowns();
    }
}