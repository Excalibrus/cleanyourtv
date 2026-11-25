package local.newepisodes;

import java.util.Comparator;

/** One episode from Cinemeta's {@code meta.videos}. */
final class Video {
    /** {@code tt0903747:1:1}. */
    final String id;
    /** -1 when the video has no season (not a series episode); 0 is specials. */
    final int season;
    final int episode;
    final String title;
    /** Release time in ms since the epoch, 0 when Cinemeta has none. */
    final long released;

    Video(String id, int season, int episode, String title, long released) {
        this.id = id;
        this.season = season;
        this.episode = episode;
        this.title = title == null ? "" : title;
        this.released = released;
    }

    boolean hasSeries() {
        return season >= 0;
    }

    /**
     * The order stremio-core sorts a series' videos in before it reads the watched bitfield: by season with
     * specials (season 0) last, then by episode; videos without a season after those, newest first.
     */
    static final Comparator<Video> STREMIO_ORDER = (a, b) -> {
        if (a.hasSeries() && b.hasSeries()) {
            long sa = a.season == 0 ? Long.MAX_VALUE : a.season;
            long sb = b.season == 0 ? Long.MAX_VALUE : b.season;
            if (sa != sb) return Long.compare(sa, sb);
            return Integer.compare(a.episode, b.episode);
        }
        if (a.hasSeries()) return -1;
        if (b.hasSeries()) return 1;
        return Long.compare(b.released, a.released);
    };
}
