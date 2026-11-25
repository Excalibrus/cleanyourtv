package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * input_tiles: the icons an input tile can wear. The built-in ones are plain badges, a colour with a short text or
 * symbol, drawn here: no one's logo is copied, and nothing is shipped as an image. The owner's own pictures (a
 * provider's logo, say) can be copied to the TV, into Android/data/com.gathin.tvhome/files/input-icons/, and show
 * up in the picker as "file:" icons.
 */
final class InputIcons {
    private InputIcons() {
    }

    static final class Icon {
        final String key;
        /** The name under the badge in the picker. */
        final String name;
        /** What the badge shows: a short text, or an emoji for the generic kinds. */
        final String text;
        final int color;

        Icon(String key, String name, String text, int color) {
            this.key = key;
            this.name = name;
            this.text = text;
            this.color = color;
        }
    }

    static final String HDMI = "hdmi";
    static final String AV = "av";
    static final String FILE_PREFIX = "file:";
    private static final String DIR = "input-icons";

    /** In the picker's order: the ports, Slovenian TV providers, then devices. */
    static final Icon[] ALL = {
            new Icon(HDMI, "HDMI", "HDMI", 0xFF3B4049),
            new Icon(AV, "AV", "AV", 0xFF4A4F58),
            new Icon("t2", "T-2", "T-2", 0xFFE8781E),
            new Icon("neo", "NEO · Telekom", "NEO", 0xFF1E5BA8),
            new Icon("a1", "A1", "A1", 0xFFD02A1E),
            new Icon("eon", "EON · Telemach", "EON", 0xFF4A2B86),
            new Icon("totaltv", "Total TV", "Total TV", 0xFF2C7A3B),
            new Icon("stb", "Set-top box", "BOX", 0xFF56606E),
            new Icon("sat", "Satellite", "📡", 0xFF34495E),
            new Icon("pc", "PC", "💻", 0xFF2E3A4A),
            new Icon("ps5", "PlayStation 5", "PS5", 0xFF1650B0),
            new Icon("xbox", "Xbox", "XBOX", 0xFF177A2D),
            new Icon("switch", "Switch", "Switch", 0xFFCF2A2A),
            new Icon("game", "Game console", "🎮", 0xFF5A3E8C),
            new Icon("appletv", "Apple TV", "Apple TV", 0xFF232528),
            new Icon("cast", "Chromecast", "Cast", 0xFF3A6CD4),
            new Icon("firetv", "Fire TV", "Fire TV", 0xFFD9731C),
            new Icon("bluray", "Blu-ray · DVD", "📀", 0xFF2D4C7A),
            new Icon("sound", "Soundbar · AV", "🔊", 0xFF3F4652),
            new Icon("camera", "Camera", "📷", 0xFF4B4F57),
    };

    static Icon find(String key) {
        if (key != null) for (Icon icon : ALL) if (icon.key.equals(key)) return icon;
        return null;
    }

    // ---- the owner's own pictures ----

    /** The folder for the owner's pictures, created on first use; null without external storage. */
    static File folder(Context ctx) {
        try {
            return ctx.getExternalFilesDir(DIR);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "file:" keys of the pictures in the folder, by name. */
    static List<String> files(Context ctx) {
        List<String> keys = new ArrayList<>();
        File dir = folder(ctx);
        String[] names = dir == null ? null : dir.list();
        if (names == null) return keys;
        Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
        for (String name : names) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")) {
                keys.add(FILE_PREFIX + name);
            }
        }
        return keys;
    }

    /** A picture's name without its extension, for the picker. */
    static String fileName(String key) {
        String name = key.substring(FILE_PREFIX.length());
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final Map<String, Bitmap> PICTURES = new HashMap<>();

    /** The picture, at most 360 px tall, or null when it is gone or does not decode. Main thread only. */
    private static Bitmap picture(Context ctx, String key) {
        File dir = folder(ctx);
        if (dir == null) return null;
        String name = key.substring(FILE_PREFIX.length());
        if (name.contains("/") || name.contains("\\")) return null;
        File file = new File(dir, name);
        if (!file.isFile()) return null;
        String cacheKey = name + '\n' + file.lastModified() + '\n' + file.length();
        Bitmap cached = PICTURES.get(cacheKey);
        if (cached != null) return cached;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outHeight <= 0 || bounds.outWidth <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outHeight / (options.inSampleSize * 2) >= 360) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) return null;
        if (PICTURES.size() > 24) PICTURES.clear();
        PICTURES.put(cacheKey, bitmap);
        return bitmap;
    }

    /** The icon for key, or fallback's when key is unknown or its picture is gone; a badge or a picture. */
    static Drawable drawable(Context ctx, String key, String fallback) {
        if (key != null && key.startsWith(FILE_PREFIX)) {
            Bitmap bitmap = picture(ctx, key);
            if (bitmap != null) return new BitmapDrawable(ctx.getResources(), bitmap);
            key = null;
        }
        Icon icon = find(key);
        if (icon == null) icon = find(fallback);
        return new Badge(icon != null ? icon : ALL[0]);
    }

    /** How wide the icon draws at height h: a picture keeps its shape, a badge fits its text. */
    static float widthFor(Drawable icon, float h) {
        if (icon instanceof Badge) return ((Badge) icon).widthFor(h);
        int w = icon.getIntrinsicWidth(), ih = icon.getIntrinsicHeight();
        return w > 0 && ih > 0 ? h * w / ih : h;
    }

    /** The badge: a rounded rectangle in the icon's colour, its text centred and shrunk to fit. */
    static final class Badge extends Drawable {
        private final Icon icon;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Rect measured = new Rect();
        private final boolean symbol;

        Badge(Icon icon) {
            this.icon = icon;
            fill.setColor(icon.color);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0x38FFFFFF);
            text.setColor(0xFFFFFFFF);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            text.setTextAlign(Paint.Align.CENTER);
            // Emoji are one code point (two chars); every badge text is plain ASCII otherwise.
            symbol = icon.text.codePointCount(0, icon.text.length()) == 1 && icon.text.charAt(0) > 0x7F;
        }

        /** Room for the text at its natural size, between a square-ish and a wide badge. */
        float widthFor(float h) {
            if (symbol) return h * 1.3f;
            text.setTextSize(h * 0.52f);
            return Math.max(h * 1.3f, Math.min(h * 3.6f, text.measureText(icon.text) + h * 0.7f));
        }

        @Override public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float h = b.height();
            float radius = h * 0.24f;
            stroke.setStrokeWidth(Math.max(1f, h / 44f));
            rect.set(b);
            canvas.drawRoundRect(rect, radius, radius, fill);
            rect.inset(stroke.getStrokeWidth() / 2f, stroke.getStrokeWidth() / 2f);
            canvas.drawRoundRect(rect, radius, radius, stroke);
            float size = h * (symbol ? 0.62f : 0.52f);
            text.setTextSize(size);
            float room = b.width() - h * 0.5f;
            float width = text.measureText(icon.text);
            if (width > room && room > 0) text.setTextSize(size * room / width);
            text.getTextBounds(icon.text, 0, icon.text.length(), measured);
            canvas.drawText(icon.text, b.exactCenterX(), b.exactCenterY() - measured.exactCenterY(), text);
        }

        @Override public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            text.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter filter) {
            fill.setColorFilter(filter);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * An input tile's banner, like an app's: dark, the title large in the middle, the icon and the caption under it.
     * 320 x 180 at heart; it is drawn at whatever size the tile is.
     */
    static final class TileBanner extends Drawable {
        private final String title;
        private final String caption;
        private final Drawable icon;
        private final Paint back = new Paint();
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect measured = new Rect();
        private final Rect iconBounds = new Rect();

        TileBanner(Context ctx, InputTiles.Tile tile, String fallbackIcon) {
            title = tile.title;
            caption = tile.caption;
            icon = drawable(ctx, tile.icon, fallbackIcon);
            titlePaint.setColor(0xFFFFFFFF);
            titlePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            titlePaint.setTextAlign(Paint.Align.CENTER);
            captionPaint.setColor(0xFFDCDDE0);
            captionPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }

        @Override public int getIntrinsicWidth() { return 320; }

        @Override public int getIntrinsicHeight() { return 180; }

        @Override public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            back.setShader(new android.graphics.LinearGradient(0, b.top, 0, b.bottom, 0xFF2B2E35, 0xFF16181D,
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(b, back);
            boolean low = !caption.isEmpty() || icon != null;
            // The title: as big as fits, a third of the height at most.
            if (!title.isEmpty()) {
                float size = h * 0.44f;
                titlePaint.setTextSize(size);
                float width = titlePaint.measureText(title);
                if (width > w * 0.86f) titlePaint.setTextSize(size * w * 0.86f / width);
                titlePaint.getTextBounds("TV", 0, 2, measured);
                float centre = b.top + h * (low ? 0.40f : 0.5f);
                canvas.drawText(title, b.exactCenterX(), centre - measured.exactCenterY(), titlePaint);
            }
            // The icon and caption, centred together under it.
            float rowH = h * 0.19f;
            float iconW = icon == null ? 0 : InputIcons.widthFor(icon, rowH);
            captionPaint.setTextSize(h * 0.15f);
            float gap = icon != null && !caption.isEmpty() ? h * 0.06f : 0;
            float captionW = caption.isEmpty() ? 0 : captionPaint.measureText(caption);
            float room = w * 0.86f - iconW - gap;
            String shown = caption;
            if (captionW > room && room > 0) {
                shown = android.text.TextUtils.ellipsize(caption, new android.text.TextPaint(captionPaint), room,
                        android.text.TextUtils.TruncateAt.END).toString();
                captionW = captionPaint.measureText(shown);
            }
            float total = iconW + gap + captionW;
            float left = b.exactCenterX() - total / 2f;
            float rowCentre = b.top + (title.isEmpty() ? h * 0.5f : h * 0.77f);
            if (icon != null) {
                iconBounds.set(Math.round(left), Math.round(rowCentre - rowH / 2f), Math.round(left + iconW),
                        Math.round(rowCentre + rowH / 2f));
                icon.setBounds(iconBounds);
                icon.draw(canvas);
            }
            if (!shown.isEmpty()) {
                captionPaint.getTextBounds("T-2", 0, 3, measured);
                canvas.drawText(shown, left + iconW + gap, rowCentre - measured.exactCenterY(), captionPaint);
            }
        }

        @Override public void setAlpha(int alpha) {
        }

        @Override public void setColorFilter(ColorFilter filter) {
        }

        @Override public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
