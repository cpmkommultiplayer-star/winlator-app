package com.winlator.core;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * A named set of upscaler settings that can be reused across containers, so a game
 * can be tuned once instead of retuned every time it is added.
 *
 * Stored as one line per profile in a plain text file in the app's private directory;
 * the container file format is left alone.
 */
public class GameProfile {
    public static final String FILE_NAME = "game_profiles.txt";

    public final String name;
    public final int mode;
    public final int scale;
    public final int sharpness;

    public GameProfile(String name, int mode, int scale, int sharpness) {
        this.name = name;
        this.mode = mode;
        this.scale = scale;
        this.sharpness = sharpness;
    }

    public static File getFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static ArrayList<GameProfile> load(Context context) {
        ArrayList<GameProfile> profiles = new ArrayList<>();
        File file = getFile(context);
        if (!file.exists()) return profiles;

        for (String line : FileUtils.readLines(file)) {
            String[] parts = line.split("\t");
            if (parts.length != 4) continue;
            try {
                String name = parts[0].trim();
                if (name.isEmpty()) continue;
                profiles.add(new GameProfile(name, Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim())));
            }
            catch (NumberFormatException e) {}
        }
        return profiles;
    }

    /** Adds the profile, replacing any existing one with the same name, and persists the list. */
    public static void save(Context context, ArrayList<GameProfile> profiles, GameProfile profile) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name.equalsIgnoreCase(profile.name)) {
                profiles.set(i, profile);
                write(context, profiles);
                return;
            }
        }
        profiles.add(profile);
        write(context, profiles);
    }

    public static void remove(Context context, ArrayList<GameProfile> profiles, int index) {
        if (index < 0 || index >= profiles.size()) return;
        profiles.remove(index);
        write(context, profiles);
    }

    private static void write(Context context, ArrayList<GameProfile> profiles) {
        StringBuilder sb = new StringBuilder();
        for (GameProfile profile : profiles) {
            sb.append(profile.name.replace('\t', ' ').replace('\n', ' ').trim())
              .append('\t').append(profile.mode)
              .append('\t').append(profile.scale)
              .append('\t').append(profile.sharpness)
              .append('\n');
        }
        FileUtils.writeString(getFile(context), sb.toString());
    }

    public String describe() {
        return String.format(Locale.ENGLISH, "%s: %d%%, sharpness %d", mode == Upscaler.MODE_OFF ? "off" : (mode == Upscaler.MODE_EASU ? "FSR 1" : "bilinear+RCAS"), scale, sharpness);
    }
}
