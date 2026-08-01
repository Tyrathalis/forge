package forge.gamemodes.chronicle;

import java.util.Random;

/**
 * Deterministic seed derivation for Chronicle's seed-integrity invariant: all
 * daily randomness derives from (run seed, day index, domain), and sealed
 * items commit a contents seed at acquisition — quitting without saving can
 * never reroll a pull or a stock roll.
 *
 * Derivation is FNV-1a over the domain string folded into the run seed and
 * day index, finalized with the SplitMix64 mixer. Nothing here depends on
 * JVM identity hashes or iteration order.
 */
public final class ChronicleSeeds {

    /** Daily LGS stock roll. */
    public static final String DOMAIN_LGS_STOCK = "lgs-stock";
    /** Contents of one sealed item; qualified by the item's id. */
    public static final String DOMAIN_SEALED_CONTENTS = "sealed-contents";

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long GOLDEN = 0x9e3779b97f4a7c15L;

    private ChronicleSeeds() {
    }

    /** Seed for a daily channel: (run seed, day index, domain). */
    public static long deriveDaily(long runSeed, int dayIndex, String domain) {
        long h = fnv1a64(domain);
        h ^= mix64(runSeed);
        h ^= mix64(dayIndex * GOLDEN);
        return mix64(h);
    }

    /** Seed for a per-item channel: (run seed, domain, item id) — e.g. sealed contents. */
    public static long deriveItem(long runSeed, String domain, long itemId) {
        long h = fnv1a64(domain);
        h ^= mix64(runSeed);
        h ^= mix64(itemId * GOLDEN + 1);
        return mix64(h);
    }

    /** Random over a derived seed. java.util.Random's algorithm is specified, so streams are stable across JVMs. */
    public static Random random(long seed) {
        return new Random(seed);
    }

    static long fnv1a64(String s) {
        long h = FNV_OFFSET;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= FNV_PRIME;
        }
        return h;
    }

    /** SplitMix64 finalizer. */
    static long mix64(long z) {
        z += GOLDEN;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
