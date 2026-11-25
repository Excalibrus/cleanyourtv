package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * input_tiles: an input dressed as a tile, like an app's banner: a title ("TV"), an icon and a caption ("T-2"). Made
 * in the App library; one can sit on Home like an app, where it switches to its input. Kept in input_tiles.txt as
 * "key TAB input id TAB title TAB caption TAB icon TAB on Home (1 or 0)" lines.
 */
final class InputTiles {
    private static final String FILE = "input_tiles.txt";
    /** A Home tile's package: "input:" and the tile's key, so Order and the tile menu tell it from an app. */
    static final String PKG_PREFIX = "input:";

    private InputTiles() {
    }

    static final class Tile {
        final String key;
        final String inputId;
        final String title;
        final String caption;
        final String icon;
        final boolean home;

        Tile(String key, String inputId, String title, String caption, String icon, boolean home) {
            this.key = key;
            this.inputId = inputId;
            this.title = clean(title);
            this.caption = clean(caption);
            this.icon = icon == null ? "" : icon.trim();
            this.home = home;
        }

        Tile withInput(String id) { return new Tile(key, id, title, caption, icon, home); }
        Tile withTitle(String t) { return new Tile(key, inputId, t, caption, icon, home); }
        Tile withCaption(String c) { return new Tile(key, inputId, title, c, icon, home); }
        Tile withIcon(String i) { return new Tile(key, inputId, title, caption, i, home); }
        Tile withHome(boolean h) { return new Tile(key, inputId, title, caption, icon, h); }

        String pkg() { return PKG_PREFIX + key; }

        /** Home's tile key: a component no app can have. */
        ComponentName component() { return new ComponentName(pkg(), "input"); }
    }

    static boolean isTile(String pkg) {
        return pkg != null && pkg.startsWith(PKG_PREFIX);
    }

    static String keyOf(String pkg) {
        return isTile(pkg) ? pkg.substring(PKG_PREFIX.length()) : null;
    }

    /** No tabs or line breaks, which would break the file's lines; 24 characters at most. */
    static String clean(String text) {
        if (text == null) return "";
        String s = text.replaceAll("[\\t\\r\\n]+", " ").trim();
        return s.length() > 24 ? s.substring(0, 24).trim() : s;
    }

    static List<Tile> parse(String text) {
        List<Tile> tiles = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] p = line.split("\t", -1);
            if (p.length < 6 || p[0].trim().isEmpty() || p[1].trim().isEmpty()) continue;
            tiles.add(new Tile(p[0].trim(), p[1].trim(), p[2], p[3], p[4], "1".equals(p[5].trim())));
        }
        return tiles;
    }

    static String format(List<Tile> tiles) {
        StringBuilder out = new StringBuilder();
        for (Tile t : tiles) {
            out.append(t.key).append('\t').append(t.inputId).append('\t').append(t.title).append('\t')
                    .append(t.caption).append('\t').append(t.icon).append('\t').append(t.home ? '1' : '0').append('\n');
        }
        return out.toString();
    }

    /** A key no tile in tiles has: t1, t2, ... */
    static String newKey(List<Tile> tiles) {
        for (int n = 1; ; n++) {
            String key = "t" + n;
            boolean used = false;
            for (Tile t : tiles) if (t.key.equals(key)) { used = true; break; }
            if (!used) return key;
        }
    }

    static List<Tile> load(Context ctx) {
        File file = new File(ctx.getFilesDir(), FILE);
        if (!file.isFile()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 64 * 1024)];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            return parse(new String(data, 0, read, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    static Tile find(List<Tile> tiles, String key) {
        for (Tile t : tiles) if (t.key.equals(key)) return t;
        return null;
    }

    /** Replaces the tile with its key, or adds it at the end; false when the file could not be written. */
    static boolean put(Context ctx, Tile tile) {
        List<Tile> tiles = load(ctx);
        boolean replaced = false;
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).key.equals(tile.key)) {
                tiles.set(i, tile);
                replaced = true;
            }
        }
        if (!replaced) tiles.add(tile);
        return write(ctx, tiles);
    }

    static boolean remove(Context ctx, String key) {
        List<Tile> tiles = load(ctx);
        Tile tile = find(tiles, key);
        if (tile == null) return true;
        tiles.remove(tile);
        return write(ctx, tiles);
    }

    private static boolean write(Context ctx, List<Tile> tiles) {
        File dir = ctx.getFilesDir();
        File tmp = new File(dir, FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(format(tiles).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            return false;
        }
        return tmp.renameTo(new File(dir, FILE));
    }
}
