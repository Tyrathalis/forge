package forge.gamemodes.chronicle;

import java.util.List;
import java.util.Random;

import forge.StaticData;
import forge.item.PaperCard;
import forge.item.SealedTemplate;
import forge.item.generation.BoosterGenerator;
import forge.util.MyRandom;

/**
 * Opens sealed items: era-authentic collation via forge-core's
 * BoosterGenerator, made deterministic by driving the thread's MyRandom from
 * the item's committed contents seed (every RNG site on the 1993-94 booster
 * path routes through MyRandom). Same item, same contents — on any device,
 * any session.
 *
 * Deliberately bypasses BoosterPack.fromSet / TournamentPack.fromSet, which
 * burn extra RNG (random booster kind, cover artIndex) before collation.
 *
 * NOTE (post-MVP landmine, from the archaeology): BoosterSlot.replaceSlot()
 * uses raw Math.random() — unreachable for the MVP window (no BoosterSlots=
 * sets before ~2018), but it breaks seed determinism if Chronicle's window
 * ever reaches the named-slot era. Fix upstream before crossing that line.
 */
public final class ChroniclePackGenerator {

    private ChroniclePackGenerator() {
    }

    /** Reveal the committed contents of a sealed item. */
    public static List<PaperCard> open(SealedItem item) {
        SealedTemplate template = templateFor(item.kind, item.editionCode);
        Random previous = MyRandom.getRandom();
        MyRandom.setRandom(new Random(item.contentsSeed));
        try {
            return BoosterGenerator.getBoosterPack(template);
        } finally {
            MyRandom.setRandom(previous);
        }
    }

    public static SealedTemplate templateFor(SealedItem.Kind kind, String editionCode) {
        SealedTemplate template;
        switch (kind) {
            case BOOSTER:
                template = StaticData.instance().getBoosters().get(editionCode);
                break;
            case STARTER:
                template = StaticData.instance().getTournamentPacks().get(editionCode);
                break;
            default:
                template = null;
        }
        if (template == null) {
            throw new IllegalArgumentException("Chronicle: no " + kind + " template for edition " + editionCode);
        }
        return template;
    }
}
