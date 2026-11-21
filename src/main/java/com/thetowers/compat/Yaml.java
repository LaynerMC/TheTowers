package com.thetowers.compat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads YAML files <b>always in UTF-8</b>.
 *
 * <p>{@code YamlConfiguration.loadConfiguration(File)} reads, in 1.12.2, with
 * the system default charset: on a Windows server under Java 8 that is
 * cp1252, and the accents of our files (saved in UTF-8) arrive as gibberish
 * (two wrong characters per accented letter). Only from Java 18 on is the
 * default charset UTF-8, hence a bug that shows up on the Java 8 + Windows
 * combos typical of 1.12.2 servers. We therefore go through
 * {@code loadConfiguration(Reader)} (present since 1.8) with an explicit
 * UTF-8 reader.
 */
public final class Yaml {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Yaml() {
    }

    /** Loads a YAML file in UTF-8; missing or unreadable file = empty config. */
    public static YamlConfiguration load(File file) {
        if (file == null || !file.exists()) {
            return new YamlConfiguration();
        }
        Reader reader = null;
        try {
            reader = new InputStreamReader(new FileInputStream(file), UTF8);
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            return new YamlConfiguration();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Closing: nothing more to do.
                }
            }
        }
    }
}
