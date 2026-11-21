package com.thetowers.compat;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import org.bukkit.entity.Player;

/**
 * Action bar (the text line above the hotbar), via the Spigot API present in
 * 1.12.2: {@code player.spigot().sendMessage(ChatMessageType.ACTION_BAR, ...)}.
 * ViaVersion translates it for newer clients; ViaBackwards for 1.8.x ones.
 */
public final class ActionBars {

    private ActionBars() {
    }

    /** Shows a text in the action bar; empty or {@code null} does nothing. */
    public static void send(Player player, String text) {
        if (text == null || text.isEmpty() || player == null || !player.isOnline()) {
            return;
        }
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    TextComponent.fromLegacyText(text));
        } catch (Throwable ignored) {
            // An exotic fork without the API loses only the action bar.
        }
    }
}
