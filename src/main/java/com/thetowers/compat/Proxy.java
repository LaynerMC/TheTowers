package com.thetowers.compat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.bukkit.entity.Player;

import com.thetowers.Main;

/**
 * Proxy compatibility (BungeeCord / Waterfall / Velocity): sends a player to
 * another server of the network, through the standard plugin-messaging
 * channel.
 *
 * <p>On our 1.12.2 server the channel is the plain {@code "BungeeCord"} name
 * (the namespaced {@code "bungeecord:main"} form only became the norm in
 * 1.13, when channels had to be lower-case and namespaced). Registering the
 * wrong name raises an exception on the version concerned. The same channel
 * is understood by BungeeCord, Waterfall and Velocity (with its BungeeCord
 * compatibility option enabled).
 *
 * <p>The protocol is a {@code Connect} sub-message: two UTF strings, the word
 * {@code "Connect"} then the name of the target server as it appears in the
 * proxy's config. None of that changed across versions.
 */
public final class Proxy {

    /** On 1.12.2 the plugin-messaging channel is the plain "BungeeCord" name. */
    private static final String CHANNEL = "BungeeCord";

    private final Main main;
    private final boolean enabled;
    private final String fallbackServer;
    private final String channel;

    public Proxy(Main main) {
        this.main = main;
        this.enabled = main.getConfig().getBoolean("bungeecord.enabled", false);
        this.fallbackServer = main.getConfig().getString("bungeecord.fallback-server", "lobby");
        this.channel = CHANNEL;
        if (enabled) {
            main.getServer().getMessenger().registerOutgoingPluginChannel(main, channel);
            main.getLogger().info("Proxy mode active: sending to server '"
                    + fallbackServer + "' at the end of a game (channel " + channel + ").");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Sends the player back to the configured hub server. */
    public void sendToFallback(Player player) {
        sendToServer(player, fallbackServer);
    }

    /** Sends the player back to a given server of the network. */
    public void sendToServer(Player player, String server) {
        if (!enabled || player == null || !player.isOnline()
                || server == null || server.isEmpty()) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException e) {
            // A ByteArrayOutputStream never throws: formality only.
            return;
        }
        player.sendPluginMessage(main, channel, bytes.toByteArray());
    }
}
