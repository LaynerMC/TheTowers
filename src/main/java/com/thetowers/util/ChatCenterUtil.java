package com.thetowers.util;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * Text-centering utility for the Minecraft chat (standard width 320px, center at 154px).
 */
public final class ChatCenterUtil {

    private static final int CENTER_PX = 154;

    private ChatCenterUtil() {
    }

    public static void sendCentered(Player player, String message) {
        if (player == null) {
            return;
        }
        player.sendMessage(center(message));
    }

    public static String center(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        String translated = ChatColor.translateAlternateColorCodes('&', message);
        int messagePxSize = 0;
        boolean previousCode = false;
        boolean isBold = false;

        for (char c : translated.toCharArray()) {
            if (c == '\u00a7' || c == ChatColor.COLOR_CHAR) {
                previousCode = true;
                continue;
            } else if (previousCode) {
                previousCode = false;
                if (c == 'l' || c == 'L') {
                    isBold = true;
                } else if (c == 'r' || c == 'R' || isColorCode(c)) {
                    isBold = false;
                }
                continue;
            }
            int charWidth = getCharWidth(c);
            messagePxSize += isBold ? charWidth + 1 : charWidth;
            messagePxSize++;
        }

        int halvedMessageSize = messagePxSize / 2;
        int toCompensate = CENTER_PX - halvedMessageSize;
        int spaceLength = 4; // 3px + 1px spacing
        int compensated = 0;
        StringBuilder sb = new StringBuilder();
        while (compensated < toCompensate) {
            sb.append(" ");
            compensated += spaceLength;
        }
        return sb.toString() + translated;
    }

    private static boolean isColorCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int getCharWidth(char c) {
        switch (c) {
            case ' ':
                return 3;
            case '!':
            case ',':
            case '.':
            case ':':
            case ';':
            case 'i':
            case '|':
            case '\'':
            case '`':
                return 1;
            case 'l':
                return 2;
            case '"':
            case '(':
            case ')':
            case '*':
            case 'I':
            case '[':
            case ']':
            case 't':
            case '{':
            case '}':
                return 3;
            case '<':
            case '>':
            case 'f':
            case 'k':
                return 4;
            case '@':
            case '~':
                return 6;
            default:
                return 5;
        }
    }
}
