package local.newepisodes;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A Stremio library item's watched state, the string stremio-core writes as
 * {@code <anchor video id>:<anchor length>:<base64 of the zlib-compressed bitfield>}. Bit i of the bitfield is
 * {@code (bytes[i / 8] >> (i % 8)) & 1} and stands for the i-th video in the series' sorted video list as it
 * was when the field was written; the anchor (the last video then, and the list's length) lets the bits be
 * moved onto today's list when episodes were added or removed since.
 */
final class Watched {
    private final String anchorId;
    private final int anchorLength;
    private final byte[] bits;

    private Watched(String anchorId, int anchorLength, byte[] bits) {
        this.anchorId = anchorId;
        this.anchorLength = anchorLength;
        this.bits = bits;
    }

    /** The parsed field, or null when it is missing or not in the format above. */
    static Watched parse(String field) {
        if (field == null) return null;
        int last = field.lastIndexOf(':');
        if (last <= 0) return null;
        int second = field.lastIndexOf(':', last - 1);
        if (second <= 0) return null;
        String anchor = field.substring(0, second);
        int length;
        try {
            length = Integer.parseInt(field.substring(second + 1, last));
        } catch (NumberFormatException e) {
            return null;
        }
        byte[] packed;
        try {
            packed = Base64.getDecoder().decode(field.substring(last + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        byte[] bits = inflate(packed);
        return bits == null ? null : new Watched(anchor, length, bits);
    }

    private static byte[] inflate(byte[] packed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[256];
            // A series has a few hundred episodes at most; anything past 64 KB is not a watched field.
            while (!inflater.finished() && out.size() < 65536) {
                int n = inflater.inflate(chunk);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(chunk, 0, n);
            }
            return inflater.finished() ? out.toByteArray() : null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private boolean bit(int i) {
        int b = i >> 3;
        return i >= 0 && b < bits.length && ((bits[b] >> (i & 7)) & 1) != 0;
    }

    /**
     * Which of {@code sortedIds} (the series' videos in {@link Video#STREMIO_ORDER}) are watched. When the anchor
     * video is no longer in the list nothing is, as in stremio-core.
     */
    boolean[] map(List<String> sortedIds) {
        int n = sortedIds.size();
        boolean[] watched = new boolean[n];
        int anchorIdx = sortedIds.indexOf(anchorId);
        if (anchorIdx < 0) return watched;
        int offset = anchorLength - anchorIdx - 1;
        for (int i = 0; i < n; i++) {
            int prev = i + offset;
            // stremio-core keeps the shifted bits only inside today's list length.
            if (offset == 0 || prev < n) watched[i] = bit(prev);
        }
        return watched;
    }
}
