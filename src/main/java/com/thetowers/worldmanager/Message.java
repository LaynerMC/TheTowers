package com.thetowers.worldmanager;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * A single translatable message line from the {@code messages} section of
 * {@code world-manager.yml}, with {@code %name%} placeholders and {@code &}
 * color codes. The config is bound once on enable through {@link #init}.
 */
public final class Message {
    private static WorldManagerConfig config;
    private String message;

    public static void init(WorldManagerConfig config) {
        Message.config = config;
    }

    public Message(String messagePath) {
        this.message = config == null ? "" : config.getMessage(messagePath);
    }

    public Message withPlaceHolder(String placeHolder, String value) {
        if (message != null) {
            message = message.replace("%" + placeHolder + "%", value);
        }
        return this;
    }

    public void sendMessage(CommandSender sender) {
        if (message != null && !message.isEmpty()) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }
}
