package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * locale: the language the launcher shows itself in, instead of the TV's. A China-market Xiaomi offers Chinese and
 * English only, so a Vietnamese owner saw the launcher in Chinese although it is translated into Vietnamese.
 * Each activity wraps its base context here; an empty or malformed tag leaves the TV's language.
 */
final class LauncherLocale {
    private static final Pattern TAG = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{2,8})?");

    private LauncherLocale() {
    }

    /** The Locale for a config tag such as "vi" or "pt-BR", or null for "" or anything that is not a plain tag. */
    static Locale parse(String tag) {
        if (tag == null || !TAG.matcher(tag.trim()).matches()) return null;
        return Locale.forLanguageTag(tag.trim());
    }

    /** base with the configured language, or base itself when locale is off. */
    static Context wrap(Context base) {
        return wrap(base, false);
    }

    /**
     * Also scaled: Home's ui_scale, as a lower density, so every dp and sp on Home (tiles, pills, text, popups)
     * shrinks by the same amount. Read with the language from one Config load.
     */
    static Context wrap(Context base, boolean scaled) {
        Config cfg = Config.load(base);
        Locale locale = parse(cfg.locale);
        int scale = scaled ? cfg.uiScale : 100;
        if (locale == null && scale == 100) return base;
        Configuration c = new Configuration(base.getResources().getConfiguration());
        if (locale != null) {
            // Formatters that take no Context (the clock's date, the lunar line) follow the default locale.
            Locale.setDefault(locale);
            c.setLocale(locale);
        }
        if (scale != 100) c.densityDpi = Math.round(c.densityDpi * scale / 100f);
        return base.createConfigurationContext(c);
    }
}
