package forge.chronicle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.animation.ForgeAnimation;
import forge.assets.FSkin;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.assets.FTextureRegionImage;
import forge.assets.ImageCache;
import forge.card.CardRenderer;
import forge.card.CardRenderer.CardStackPosition;
import forge.card.CardZoom;
import forge.haptic.HapticEngine;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;
import forge.toolbox.FCardPanel;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FLabel;
import forge.util.Utils;

/**
 * The D3 reveal scene: period wrapper (the set's real booster art) torn open by
 * a drag, then a player-paced flip loop — commons batch-flip, best-last rare
 * buildup, first-pull glint — or the batch register (starters/boxes): a rapid
 * cascade that auto-pauses on the cards worth a beat. Two registers, one scene;
 * 2.5D throughout per the reveal-UX bar.
 */
public class ChronicleRevealScene extends FDisplayObject {

    /** One card staged for reveal, with everything pacing decisions need. */
    public static final class RevealCard {
        public final PaperCard card;
        public final boolean firstPull; //first copy this run ever acquired
        public final boolean notable;   //1994 tier table entry
        public final int valueCents;

        public RevealCard(PaperCard card, boolean firstPull, boolean notable, int valueCents) {
            this.card = card;
            this.firstPull = firstPull;
            this.notable = notable;
            this.valueCents = valueCents;
        }

        int rarityRank() {
            switch (card.getRarity()) {
                case BasicLand: return 0;
                case Common:    return 1;
                case Uncommon:  return 2;
                case Rare:      return 3;
                default:        return 4;
            }
        }

        /** Worth stopping the cascade for, and worth the shard sound. */
        boolean jackpot() {
            return rarityRank() >= 3 || notable;
        }

        /** Worth a brief hold in the cascade, not a full stop: any non-jackpot first pull. */
        boolean softPause() {
            return firstPull && !jackpot();
        }
    }

    /** One sealed product staged for reveal (a pack, or a starter's card block). */
    public static final class RevealPack {
        public final String title;
        public final String artKey;         //preferred product art (may never arrive — host gone)
        public final String fallbackArtKey; //drawn while/if the preferred art is missing; null = none
        public final List<RevealCard> cards;

        public RevealPack(String title, String artKey, String fallbackArtKey, List<RevealCard> cards) {
            this.title = title;
            this.artKey = artKey;
            this.fallbackArtKey = fallbackArtKey;
            this.cards = cards;
        }
    }

    public interface Listener {
        void onRevealFinished();

        /** Fired whenever pacing state moves — hosts sync affordances (e.g. the commons batch-flip button). */
        default void onStateChanged() {
        }
    }

    //pacing constants — the dogfood tuning surface
    private static final float TEAR_STRIP_FRACTION = 0.24f;
    private static final float TEAR_SWIPE_FRACTION = 0.5f;  //fraction of screen width ONE committed swipe must travel
    private static final float TEAR_SPRING_RATE = 3.2f;     //progress/sec the wrapper eases shut when released short
    private static final float TEAR_FLING_MIN = 250f;       //px/s along the locked axis that counts as a committed flick
    private static final float TEAR_FLY_DURATION = 0.45f;
    private static final int TEAR_SEGMENTS = 16;            //vertical columns the wrapper splits into
    private static final float TEAR_JAG_FRACTION = 0.05f;   //zigzag amplitude, fraction of wrapper height
    private static final float TEAR_PEEL_LAG = 0.6f;        //how much later the far end lets go (0 = all at once)
    private static final float TEAR_CURL_DEG = 16f;         //peel curl at the gripped end
    private static final float TEAR_LIFT_FRACTION = 0.12f;  //how far a fully peeled segment rises
    private static final float TEAR_SLIDE_FRACTION = 0.3f;  //how far it slides along the pull
    private static final float FLIP_DURATION = 0.32f;
    private static final float FLY_DURATION = 0.22f;
    private static final float CASCADE_FLIP_DURATION = 0.18f;
    private static final float CASCADE_FLY_DURATION = 0.12f;
    private static final float CASCADE_DWELL = 0.18f;        //face-up beat between flip and fly in the cascade
    private static final float SOFT_PAUSE_HOLD = 0.75f;      //new uncommons
    private static final float SOFT_PAUSE_HOLD_COMMON = 0.45f; //new commons/basics
    private static final float BUILDUP_DURATION = 0.9f;
    private static final float BUILDUP_BATCH_DURATION = 0.35f;
    private static final float GLINT_DURATION = 0.8f;
    private static final float INTERSTITIAL_DURATION = 0.5f;
    private static final float WIGGLE_DURATION = 0.4f;

    private static final float PADDING = Utils.scale(6);

    private enum Phase {
        TEAR_IDLE,     //wrapper on screen, waiting for the rip
        TEAR_FLY,      //strip flying off, wrapper dropping away
        CARD_DOWN,     //face-down card waiting (ceremony) or about to auto-flip (batch)
        BUILDUP,       //pre-flip pulse on the staged-last card
        FLIP,          //back-to-face flip animation
        CARD_UP,       //face-up card holding (glint may be sweeping)
        FLY,           //face-up card flying to the revealed row
        INTERSTITIAL,  //next pack's art sliding through (batch, packs 2+)
        FINISHED
    }

    private final List<RevealPack> packs;
    private final boolean batch;
    private final Listener listener;

    private final FTextureRegionImage cardBack = new FTextureRegionImage(FSkin.getSleeves().get(0));

    private Phase phase;
    private float phaseT;      //seconds inside the current phase
    private float glintT = Float.MAX_VALUE;   //independent of phase so the sweep can outlive FLIP
    private float wiggleT = Float.MAX_VALUE;  //tear-hint nudge
    private float tearProgress; //0..1
    private int tearDir;           //committed drag direction: 0 = uncommitted, -1 left, +1 right
    private float tearAnchorX;     //local x the committed travel is measured from
    private int tearFlyDir = 1;    //direction the wrapper was actually torn, kept for the fly-off
    private boolean tearDragging;  //a finger is down and driving the tear right now
    private boolean tearSpringing; //finger lifted short of the tear — easing back shut
    private boolean cascading;  //batch auto-advance running
    private int autoFlipRemaining; //ceremony commons batch-flip: cards left in the auto-run
    private float softHoldT;    //counts the soft-pause dwell in CARD_UP

    private int packIndex;
    private int cardIndex;      //index into current pack, the card currently center stage
    private int revealedTotal;  //across packs, drives the counter and the row
    private final List<RevealCard> revealedRow = new ArrayList<>(); //most recent last
    private final Set<Integer> preTouched = new HashSet<>();

    private final SceneDriver driver = new SceneDriver();

    public ChronicleRevealScene(List<RevealPack> packs, boolean batch, Listener listener) {
        this.packs = packs;
        this.batch = batch;
        this.listener = listener;
        this.phase = Phase.TEAR_IDLE;
        this.cascading = batch;
        driver.start();
    }

    private RevealPack pack() {
        return packs.get(packIndex);
    }

    private RevealCard current() {
        return pack().cards.get(cardIndex);
    }

    private int totalCards() {
        int n = 0;
        for (RevealPack p : packs) {
            n += p.cards.size();
        }
        return n;
    }

    private boolean lastOfPack() {
        return cardIndex == pack().cards.size() - 1;
    }

    /** Skip straight to the end (the parent shows the summary either way). */
    public void finishNow() {
        if (phase != Phase.FINISHED) {
            enterFinished();
        }
    }

    /** Length of the face-down run of plain commons/basics starting at the current card. */
    private int commonsRunLength() {
        int n = 0;
        List<RevealCard> cards = pack().cards;
        for (int i = cardIndex; i < cards.size(); i++) {
            RevealCard rc = cards.get(i);
            if (rc.rarityRank() > 1 || rc.notable) {
                break;
            }
            n++;
        }
        return n;
    }

    /** True when the ceremony can offer "flip the commons" (a run of 3+ waits face-down). */
    public boolean canBatchFlipCommons() {
        return !batch && phase == Phase.CARD_DOWN && autoFlipRemaining == 0 && commonsRunLength() >= 3;
    }

    public int batchFlipCount() {
        return commonsRunLength();
    }

    /** Auto-flip through the waiting commons run at cascade pace, then hand pacing back. */
    public void batchFlipCommons() {
        if (!canBatchFlipCommons()) {
            return;
        }
        autoFlipRemaining = commonsRunLength();
        beginRevealOfCurrent();
        notifyStateChanged();
    }

    private boolean autoAdvancing() {
        return cascading || autoFlipRemaining > 0;
    }

    private void notifyStateChanged() {
        if (listener != null) {
            listener.onStateChanged();
        }
    }

    //--- phase transitions ---------------------------------------------------

    private void startTearFly() {
        phase = Phase.TEAR_FLY;
        phaseT = 0;
        SoundSystem.instance.play(SoundEffectType.Shuffle, false);
        HapticEngine.vibrate(FPref.UI_VIBRATE_ON_ADVENTURE_REWARD, 20);
    }

    private void enterCardDown() {
        phase = Phase.CARD_DOWN;
        phaseT = 0;
        preTouchAhead();
        notifyStateChanged();
    }

    private void startBuildup() {
        phase = Phase.BUILDUP;
        phaseT = 0;
        SoundSystem.instance.play(SoundEffectType.Draw, false);
        notifyStateChanged();
    }

    private void startFlip() {
        phase = Phase.FLIP;
        phaseT = 0;
        SoundSystem.instance.play(SoundEffectType.FlipCard, false);
        notifyStateChanged();
    }

    private void enterCardUp() {
        phase = Phase.CARD_UP;
        phaseT = 0;
        softHoldT = 0;
        RevealCard rc = current();
        if (rc.firstPull) {
            glintT = 0;
        }
        if (rc.jackpot()) {
            SoundSystem.instance.play(SoundEffectType.TakeShard, false);
            HapticEngine.vibrate(FPref.UI_VIBRATE_ON_ADVENTURE_REWARD, 25);
            cascading = false; //the auto-pause: the player restarts the cascade
            autoFlipRemaining = 0;
        }
        notifyStateChanged();
    }

    private void startFly() {
        phase = Phase.FLY;
        phaseT = 0;
    }

    private void advanceToNextCard() {
        revealedRow.add(current());
        revealedTotal++;
        cardIndex++;
        if (autoFlipRemaining > 0) {
            autoFlipRemaining--;
        }
        if (cardIndex >= pack().cards.size()) {
            packIndex++;
            cardIndex = 0;
            if (packIndex >= packs.size()) {
                enterFinished();
                return;
            }
            phase = Phase.INTERSTITIAL;
            phaseT = 0;
            SoundSystem.instance.play(SoundEffectType.Shuffle, false);
            return;
        }
        enterCardDown();
        if (autoAdvancing()) {
            beginRevealOfCurrent(); //no idle beat between auto-advanced cards
        }
    }

    private void enterFinished() {
        phase = Phase.FINISHED;
        if (listener != null) {
            listener.onRevealFinished();
        }
    }

    /** Kick the current face-down card into motion (buildup if it earned one, else flip). */
    private void beginRevealOfCurrent() {
        if (lastOfPack() && (!batch || current().jackpot())) {
            startBuildup();
        } else {
            startFlip();
        }
    }

    //--- the driver ----------------------------------------------------------

    private class SceneDriver extends ForgeAnimation {
        @Override
        protected boolean advance(float dt) {
            if (glintT < GLINT_DURATION) {
                glintT += dt;
            }
            if (wiggleT < WIGGLE_DURATION) {
                wiggleT += dt;
            }
            switch (phase) {
                case TEAR_IDLE:
                    if (tearSpringing) {
                        tearProgress -= TEAR_SPRING_RATE * dt;
                        if (tearProgress <= 0) {
                            tearProgress = 0;
                            tearSpringing = false;
                            tearDir = 0; //shut again: the next swipe may commit either way
                        }
                    }
                    break;
                case TEAR_FLY:
                    phaseT += dt;
                    if (phaseT >= TEAR_FLY_DURATION) {
                        enterCardDown();
                        if (cascading) {
                            beginRevealOfCurrent();
                        }
                    }
                    break;
                case CARD_DOWN:
                    phaseT += dt; //idle, but the pause-prompt pulse keeps breathing
                    break;
                case BUILDUP:
                    phaseT += dt;
                    if (phaseT >= buildupDuration()) {
                        startFlip();
                    }
                    break;
                case FLIP:
                    phaseT += dt;
                    if (phaseT >= flipDuration()) {
                        enterCardUp();
                    }
                    break;
                case CARD_UP:
                    phaseT += dt;
                    if (autoAdvancing()) {
                        RevealCard rc = current();
                        float hold = rc.softPause()
                                ? (rc.rarityRank() == 2 ? SOFT_PAUSE_HOLD : SOFT_PAUSE_HOLD_COMMON)
                                : CASCADE_DWELL;
                        softHoldT += dt;
                        if (softHoldT >= hold) {
                            startFly();
                        }
                    }
                    break;
                case FLY:
                    phaseT += dt;
                    if (phaseT >= flyDuration()) {
                        advanceToNextCard();
                    }
                    break;
                case INTERSTITIAL:
                    phaseT += dt;
                    if (phaseT >= INTERSTITIAL_DURATION) {
                        enterCardDown();
                        if (cascading) {
                            beginRevealOfCurrent();
                        }
                    }
                    break;
                case FINISHED:
                    return false;
            }
            return true;
        }

        @Override
        protected void onEnd(boolean endingAll) {
        }
    }

    private float flipDuration() {
        return autoAdvancing() ? CASCADE_FLIP_DURATION : FLIP_DURATION;
    }

    private float flyDuration() {
        return autoAdvancing() ? CASCADE_FLY_DURATION : FLY_DURATION;
    }

    private float buildupDuration() {
        return batch ? BUILDUP_BATCH_DURATION : BUILDUP_DURATION;
    }

    /** Warm the texture cache one card ahead so flips never hitch on a disk load. */
    private void preTouchAhead() {
        int next = cardIndex + 1;
        List<RevealCard> cards = pack().cards;
        if (next < cards.size() && preTouched.add(packIndex * 1000 + next)) {
            ImageCache.getInstance().getImage(cards.get(next).card.getImageKey(false), false);
        }
    }

    //--- input ---------------------------------------------------------------

    @Override
    public boolean tap(float x, float y, int count) {
        switch (phase) {
            case TEAR_IDLE:
                wiggleT = 0; //nudge: the wrapper wants a rip, not a tap
                break;
            case TEAR_FLY:
                phaseT = TEAR_FLY_DURATION;
                break;
            case CARD_DOWN:
                if (batch) {
                    cascading = true; //also recovers a paused batch parked face-down
                }
                beginRevealOfCurrent();
                break;
            case BUILDUP:
                phaseT = buildupDuration(); //impatient: jump to the flip
                break;
            case FLIP:
                phaseT = flipDuration();
                break;
            case CARD_UP:
                if (batch && !cascading) {
                    cascading = true; //tap resumes the cascade after an auto-pause
                    startFly();
                } else if (autoAdvancing()) {
                    cascading = false; //tap during an auto-run: hold this card
                    autoFlipRemaining = 0;
                    notifyStateChanged();
                } else {
                    startFly();
                }
                break;
            case FLY:
                phaseT = flyDuration();
                break;
            default:
                break;
        }
        return true;
    }

    /**
     * The tear is a committed one-way swipe: the first horizontal travel commits a
     * direction, and only travel that way opens the wrapper. Pulling back closes it
     * again, and lifting short of the tear springs it shut — you cannot shake a pack
     * open, and you never open one by accident on the return stroke.
     */
    @Override
    public boolean pan(float x, float y, float deltaX, float deltaY, boolean moreVertical) {
        if (phase != Phase.TEAR_IDLE) {
            return false;
        }
        if (!tearDragging) {
            if (tearDir == 0) {
                if (moreVertical || deltaX == 0) {
                    return true; //a drag up or down the wrapper isn't a tear
                }
                tearDir = deltaX < 0 ? -1 : 1;
            }
            //anchor so wherever the tear currently sits stays put under the finger —
            //true both for a fresh swipe (progress 0) and one resumed mid-spring
            tearAnchorX = (x - deltaX) - tearDir * tearProgress * tearTravel();
            tearDragging = true;
        }
        tearSpringing = false;
        float travelled = (x - tearAnchorX) * tearDir;
        if (travelled < 0) {
            tearAnchorX = x; //pulled back past the start: the tear re-anchors here
            travelled = 0;
        }
        tearProgress = Math.min(1, travelled / tearTravel());
        if (tearProgress >= 1) {
            tearDragging = false;
            tearFlyDir = tearDir;
            tearDir = 0;
            startTearFly();
        }
        return true;
    }

    /** Finger travel, in local px, that a committed swipe must cover to tear the wrapper. */
    private float tearTravel() {
        return getWidth() * TEAR_SWIPE_FRACTION;
    }

    @Override
    public boolean panStop(float x, float y) {
        if (phase == Phase.TEAR_IDLE && tearDragging) {
            //fling() fires right after this and may still finish the tear; until it
            //does, a lifted finger means the wrapper closes back up. The committed
            //direction survives the release so fling() can check the flick against it.
            tearDragging = false;
            tearSpringing = tearProgress > 0;
            if (!tearSpringing) {
                tearDir = 0; //dragged all the way back before lifting: either way is fair again
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean fling(float velocityX, float velocityY) {
        //a flick finishes the tear only if it flies the way the drag committed to
        if (phase != Phase.TEAR_IDLE || tearProgress <= 0 || !tearSpringing) {
            return false;
        }
        if (Math.abs(velocityX) < TEAR_FLING_MIN || Math.abs(velocityY) > Math.abs(velocityX)) {
            return false;
        }
        if (Math.signum(velocityX) != tearDir) {
            return false;
        }
        tearSpringing = false;
        tearFlyDir = tearDir;
        tearDir = 0;
        tearProgress = 1;
        startTearFly();
        return true;
    }

    @Override
    public boolean longPress(float x, float y) {
        //match-inspect zoom over what's been revealed so far — no peeking ahead
        List<PaperCard> shown = new ArrayList<>();
        for (RevealCard rc : revealedRow) {
            shown.add(rc.card);
        }
        boolean currentUp = phase == Phase.CARD_UP || phase == Phase.FLY;
        if (currentUp) {
            shown.add(current().card);
        }
        if (shown.isEmpty()) {
            return true;
        }
        //a press on the revealed row opens THAT card, not the latest
        int index = rowIndexAt(x, y);
        if (index < 0) {
            index = shown.size() - 1;
        }
        CardZoom.show(shown, index, null);
        return true;
    }

    /** Which revealed-row mini sits under (x, y), or -1 outside the row. Mirrors drawRevealedRow geometry. */
    private int rowIndexAt(float x, float y) {
        float h = getHeight();
        float rowH = h * 0.16f;
        if (revealedRow.isEmpty() || y < h - rowH) {
            return -1;
        }
        float miniH = rowH - PADDING;
        float miniW = miniH / FCardPanel.ASPECT_RATIO;
        float step = miniW * 0.55f;
        int shown = Math.min(revealedRow.size(), 8);
        float totalW = miniW + (shown - 1) * step;
        float startX = (getWidth() - totalW) / 2;
        if (x < startX || x > startX + totalW) {
            return -1;
        }
        //overlapped fans: the rightmost mini under the press wins, matching what's visible on top
        int slot = Math.min(shown - 1, (int) ((x - startX) / step));
        return revealedRow.size() - shown + slot;
    }

    //--- drawing -------------------------------------------------------------

    @Override
    public void draw(Graphics g) {
        float w = getWidth();
        float h = getHeight();
        float headerH = Utils.scale(24);
        float rowH = h * 0.16f;
        float stageTop = headerH + PADDING;
        float stageH = h - stageTop - rowH - 2 * PADDING;

        //header: pack title + progress
        String header = pack().title + "  (" + Math.min(revealedTotal + 1, totalCards()) + "/" + totalCards() + ")";
        g.drawText(header, FSkinFont.get(14), FLabel.getInlineLabelColor(),
                0, 0, w, headerH, false, Align.center, true);

        //card geometry, shared by every phase
        float cardH = stageH * 0.92f;
        float cardW = cardH / FCardPanel.ASPECT_RATIO;
        if (cardW > w * 0.7f) {
            cardW = w * 0.7f;
            cardH = cardW * FCardPanel.ASPECT_RATIO;
        }
        float cx = w / 2;
        float cy = stageTop + stageH / 2;
        float cardX = cx - cardW / 2;
        float cardY = cy - cardH / 2;

        switch (phase) {
            case TEAR_IDLE:
            case TEAR_FLY:
                drawWrapper(g, w, cx, cy, stageH, cardX, cardY, cardW, cardH);
                break;
            case CARD_DOWN:
                drawStack(g, cardX, cardY, cardW, cardH, remainingInPack());
                drawBack(g, cardX, cardY, cardW, cardH, 1, 0);
                if (!batch) {
                    drawHint(g, hintText("lblChronicleTapToFlip", "Tap to flip"), w, h, rowH);
                }
                break;
            case BUILDUP: {
                float t = phaseT / buildupDuration();
                //pulse grows toward the flip; a slight shiver joins in late
                float pulse = 1 + 0.06f * t * (float) Math.abs(Math.sin(t * Math.PI * 5));
                float shiver = t > 0.5f ? 1.6f * (float) Math.sin(t * Math.PI * 22) * (t - 0.5f) : 0;
                drawStack(g, cardX, cardY, cardW, cardH, remainingInPack());
                drawBack(g, cardX, cardY, cardW, cardH, pulse, shiver);
                break;
            }
            case FLIP: {
                float t = Math.min(1, phaseT / flipDuration());
                drawStack(g, cardX, cardY, cardW, cardH, remainingInPack());
                if (t < 0.5f) {
                    float s = 1 - t * 2; //back collapsing
                    drawBack(g, cx - cardW * s / 2, cardY, cardW * s, cardH, 1, 0);
                } else {
                    float s = overshoot((t - 0.5f) * 2); //face expanding with a pop
                    drawFace(g, current(), cx - cardW * s / 2, cardY - cardH * (s - 1) / 2,
                            cardW * s, cardH * s, false);
                }
                break;
            }
            case CARD_UP:
                drawStack(g, cardX, cardY, cardW, cardH, remainingInPack());
                drawFace(g, current(), cardX, cardY, cardW, cardH, true);
                if (!batch) {
                    drawHint(g, hintText("lblChronicleTapForNext", "Tap for next"), w, h, rowH);
                }
                break;
            case FLY: {
                float t = smooth(Math.min(1, phaseT / flyDuration()));
                //fly toward the row's landing point, shrinking to row-mini size
                float destW = (rowH - PADDING) / FCardPanel.ASPECT_RATIO;
                float destX = rowLandingX(w);
                float destY = h - rowH;
                float fx = cardX + (destX - cardX) * t;
                float fy = cardY + (destY - cardY) * t;
                float fw = cardW + (destW - cardW) * t;
                float fh = fw * FCardPanel.ASPECT_RATIO;
                drawStack(g, cardX, cardY, cardW, cardH, remainingInPack() - 1);
                if (remainingInPack() > 1) {
                    drawBack(g, cardX, cardY, cardW, cardH, 1, 0);
                }
                drawFace(g, current(), fx, fy, fw, fh, false);
                break;
            }
            case INTERSTITIAL: {
                //next pack's art slides through center stage
                float t = smooth(Math.min(1, phaseT / INTERSTITIAL_DURATION));
                float artX = w * 1.1f - (w * 1.1f - (cx - cardW / 2)) * t; //slides in from the right, settles center

                drawPackArt(g, packArt(pack()), pack().title, artX, cardY, cardW, cardH, 1);
                g.drawText(pack().title, FSkinFont.get(13), FLabel.getInlineLabelColor(),
                        0, cardY + cardH + PADDING, w, Utils.scale(18), false, Align.center, false);
                break;
            }
            case FINISHED:
                break;
        }

        drawRevealedRow(g, w, h, rowH);

        //batch auto-pause: an unmissable prompt — the tiny hint was invisible in play
        if (batch && !cascading && (phase == Phase.CARD_UP || phase == Phase.CARD_DOWN)) {
            String prompt = hintText("lblChronicleTapToContinue", "Tap to continue") + "  ▸";
            float pw = Utils.scale(160);
            float ph = Utils.scale(28);
            float px = (w - pw) / 2;
            float py = h - rowH - ph - Utils.scale(10);
            float pulse = 0.75f + 0.25f * (float) Math.sin(phaseT * Math.PI * 1.6);
            g.setAlphaComposite(pulse);
            g.fillRoundRect(FSkinColor.getStandardColor(new Color(0.12f, 0.12f, 0.16f, 0.92f)).getColor(),
                    px, py, pw, ph, ph / 2);
            g.drawRoundRect(Utils.scale(1), Color.LIGHT_GRAY, px, py, pw, ph, ph / 2);
            g.drawText(prompt, FSkinFont.get(13), Color.WHITE, px, py, pw, ph, false, Align.center, true);
            g.resetAlphaComposite();
        }
    }

    private int remainingInPack() {
        return pack().cards.size() - cardIndex;
    }

    private static float overshoot(float t) {
        //back-ease-out: 0 → 1 with a ~10% pop that settles
        float s = 1.70158f;
        t -= 1;
        return 1 + t * t * ((s + 1) * t + s);
    }

    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    private void drawWrapper(Graphics g, float w, float cx, float cy, float stageH,
                             float cardX, float cardY, float cardW, float cardH) {
        //the stack is already waiting behind the wrapper once the tear starts flying
        if (phase == Phase.TEAR_FLY) {
            float t = Math.min(1, phaseT / TEAR_FLY_DURATION);
            drawStack(g, cardX, cardY, cardW, cardH, remainingInPack());
            drawBack(g, cardX, cardY, cardW, cardH, 1, 0);
            drawTornWrapper(g, cx, cy, stageH, t);
            return;
        }
        drawTornWrapper(g, cx, cy, stageH, 0);
        float wiggle = wiggleT < WIGGLE_DURATION ? (float) Math.sin(wiggleT / WIGGLE_DURATION * Math.PI * 3) * 2 : 0;
        g.drawText(Forge.getLocalizer().getMessageorUseDefault("lblChronicleTearToOpen", "Swipe across to tear it open"),
                FSkinFont.get(13), FSkinColor.getStandardColor(Color.GRAY),
                wiggle, cy + stageH / 2 - Utils.scale(2), w, Utils.scale(18), false, Align.center, false);
    }

    /** Best available product art for a pack: preferred key, else fallback, else null. */
    private static Texture packArt(RevealPack pack) {
        Texture art = loadedArt(pack.artKey);
        return art != null ? art : loadedArt(pack.fallbackArtKey);
    }

    private static Texture loadedArt(String artKey) {
        if (artKey == null) {
            return null;
        }
        Texture art = ImageCache.getInstance().getImage(artKey, true);
        return art == null || art == ImageCache.getInstance().getDefaultImage() ? null : art;
    }

    /**
     * The wrapper, split along a jagged tear that propagates across it as you pull.
     * The wrapper is drawn as vertical columns: each one is whole until the tear
     * reaches it, then splits at its own point on the jag line and its top peels —
     * sliding, lifting and curling, the gripped end leading and the far end lagging.
     * flyT > 0 carries the peeled strip and the body off the stage.
     */
    private void drawTornWrapper(Graphics g, float cx, float cy, float stageH, float flyT) {
        float artH = stageH * 0.8f;
        float artW = artH * 0.72f;
        Texture art = packArt(pack());
        boolean real = art != null;
        if (real) {
            float texAspect = (float) art.getWidth() / art.getHeight();
            artW = artH * Math.min(texAspect, 0.85f);
        }
        float ax = cx - artW / 2;
        float ay = cy - artH / 2;
        float stripH = artH * TEAR_STRIP_FRACTION;
        float jagAmp = artH * TEAR_JAG_FRACTION;
        int dir = phase == Phase.TEAR_IDLE && tearDir != 0 ? tearDir : tearFlyDir;

        //the body falls away as one piece once the strip is gone
        float bodyDy = flyT * stageH * 0.7f;
        float segW = artW / TEAR_SEGMENTS;
        float[] edgeY = new float[TEAR_SEGMENTS];
        float[] edgeLit = new float[TEAR_SEGMENTS];

        for (int i = 0; i < TEAR_SEGMENTS; i++) {
            float u0 = i / (float) TEAR_SEGMENTS;
            float uMid = (i + 0.5f) / TEAR_SEGMENTS;
            //1 at the gripped edge, 0 at the far one. You grab the near edge and pull
            //across, so the tear front travels WITH your hand: swipe right and the
            //left edge lets go first, the split chasing the finger rightwards.
            float grip = dir > 0 ? 1 - uMid : uMid;
            float lagStart = (1 - grip) * TEAR_PEEL_LAG;
            float peel = tearProgress <= lagStart ? 0
                    : Math.min(1, (tearProgress - lagStart) / (1 - lagStart));

            float sx = ax + u0 * artW;
            float tearY = ay + stripH + jag(i) * jagAmp * Math.min(1, peel * 2);

            if (peel <= 0) {
                //untorn: this column of the wrapper is still whole
                drawWrapperColumn(g, art, real, sx, ay + bodyDy, segW, artH,
                        u0, u0 + 1f / TEAR_SEGMENTS, 0, 1, 1 - flyT, 0);
                continue;
            }

            //body of the column, from its own point on the jag down
            float bodyTop = tearY - ay;
            drawWrapperColumn(g, art, real, sx, tearY + bodyDy, segW, artH - bodyTop,
                    u0, u0 + 1f / TEAR_SEGMENTS, bodyTop / artH, 1, 1 - flyT, 0);

            //the peeled strip above it: slides along the pull, lifts, curls
            float dx = dir * (peel * artW * TEAR_SLIDE_FRACTION + flyT * getWidth() * 0.9f);
            float dy = -peel * artH * TEAR_LIFT_FRACTION - flyT * getHeight() * 0.35f;
            float angle = dir * (peel * TEAR_CURL_DEG + flyT * 30);
            drawWrapperColumn(g, art, real, sx + dx, ay + dy, segW, bodyTop,
                    u0, u0 + 1f / TEAR_SEGMENTS, 0, bodyTop / artH, 1 - flyT, angle);

            edgeY[i] = tearY;
            edgeLit[i] = Math.min(1, peel * 2.5f);
        }

        //raw paper along the fresh edge — one connected zigzag, not a row of dashes,
        //bright where the tear just gave way and fading behind it
        if (flyT < 1) {
            for (int i = 0; i < TEAR_SEGMENTS; i++) {
                if (edgeLit[i] <= 0) {
                    continue;
                }
                float x0 = ax + i * segW;
                g.setAlphaComposite(edgeLit[i] * (1 - flyT) * 0.8f);
                g.drawLine(Utils.scale(1.5f), Color.WHITE,
                        x0, edgeY[i] + bodyDy, x0 + segW, edgeY[i] + bodyDy);
                if (i + 1 < TEAR_SEGMENTS && edgeLit[i + 1] > 0) {
                    g.drawLine(Utils.scale(1.5f), Color.WHITE,
                            x0 + segW, edgeY[i] + bodyDy, x0 + segW, edgeY[i + 1] + bodyDy);
                }
                g.resetAlphaComposite();
            }
        }

        //placeholder wrappers carry the product name where the art would be
        if (!real && flyT < 1) {
            g.setAlphaComposite(1 - flyT);
            g.drawText(pack().title, FSkinFont.get(14), Color.WHITE,
                    ax, ay + stripH + bodyDy, artW, artH - stripH, true, Align.center, true);
            g.resetAlphaComposite();
        }
    }

    /**
     * One cell of the wrapper: the art's [u0, u1] × [v0, v1] rect, drawn at (x, y)
     * with the given size and rotated about its bottom-left corner (where a peeling
     * strip hinges).
     */
    private void drawWrapperColumn(Graphics g, Texture art, boolean real,
                                   float x, float y, float w, float h,
                                   float u0, float u1, float v0, float v1, float alpha, float angle) {
        if (h <= 0 || alpha <= 0) {
            return;
        }
        if (alpha < 1) {
            g.setAlphaComposite(alpha);
        }
        if (real) {
            int srcX = (int) (art.getWidth() * u0);
            int srcW = Math.max(1, (int) (art.getWidth() * (u1 - u0)));
            int srcY = (int) (art.getHeight() * v0);
            int srcH = Math.max(1, (int) (art.getHeight() * (v1 - v0)));
            g.drawRotatedImage(art, x, y, w, h, x, y + h, srcX, srcY, srcW, srcH, angle);
        } else {
            g.startRotateTransform(x, y + h, angle);
            g.fillRect(FSkinColor.getStandardColor(new Color(0.19f, 0.16f, 0.26f, 1f)).getColor(), x, y, w, h);
            g.endTransform();
        }
        if (alpha < 1) {
            g.resetAlphaComposite();
        }
    }

    /** Stable per-column zigzag offset in [-1, 1] — the same pack always tears the same way. */
    private float jag(int i) {
        int h = (packIndex * 73856093) ^ (i * 19349663);
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return ((h & 0xFFFF) / 65535f) * 2 - 1;
    }

    private void drawPackArt(Graphics g, Texture art, String title, float x, float y, float w, float h, float alpha) {
        boolean real = art != null;
        if (alpha < 1) {
            g.setAlphaComposite(alpha);
        }
        if (real) {
            float texAspect = (float) art.getWidth() / art.getHeight();
            float aw = Math.min(w, h * texAspect);
            g.drawImage(art, x + (w - aw) / 2, y, aw, h);
        } else {
            g.fillRect(FSkinColor.getStandardColor(new Color(0.16f, 0.14f, 0.22f, 1f)).getColor(), x, y, w, h);
            g.drawText(title, FSkinFont.get(14), Color.WHITE, x, y, w, h, true, Align.center, true);
        }
        if (alpha < 1) {
            g.resetAlphaComposite();
        }
    }

    /** Face-down pile depth: a couple of offset backs behind the live card. */
    private void drawStack(Graphics g, float x, float y, float w, float h, int remaining) {
        for (int i = Math.min(2, remaining - 1); i >= 1; i--) {
            float off = Utils.scale(2.5f * i);
            g.setAlphaComposite(0.85f);
            g.drawImage(cardBack, x + off, y + off, w, h);
            g.resetAlphaComposite();
        }
    }

    private void drawBack(Graphics g, float x, float y, float w, float h, float pulse, float shiverDeg) {
        float pw = w * pulse;
        float ph = h * pulse;
        float px = x - (pw - w) / 2;
        float py = y - (ph - h) / 2;
        if (shiverDeg != 0) {
            g.startRotateTransform(px + pw / 2, py + ph / 2, shiverDeg);
        }
        g.drawImage(cardBack, px, py, pw, ph);
        if (shiverDeg != 0) {
            g.endTransform();
        }
    }

    private void drawFace(Graphics g, RevealCard rc, float x, float y, float w, float h, boolean withBadges) {
        CardRenderer.drawCard(g, rc.card, x, y, w, h, CardStackPosition.Top);
        if (!withBadges) {
            return;
        }
        //first-pull glint: a light band sweeping the card diagonally, once
        if (rc.firstPull && glintT < GLINT_DURATION) {
            float t = glintT / GLINT_DURATION;
            if (g.startClip(x, y, w, h)) {
                float bandW = w * 0.35f;
                float sweep = -bandW + (w + 2 * bandW) * t;
                g.startRotateTransform(x + sweep + bandW / 2, y + h / 2, -18);
                g.setAlphaComposite(0.38f * (float) Math.sin(t * Math.PI));
                g.fillGradientRect(new Color(1, 1, 1, 0), Color.WHITE, false,
                        x + sweep, y - h * 0.3f, bandW / 2, h * 1.6f);
                g.fillGradientRect(Color.WHITE, new Color(1, 1, 1, 0), false,
                        x + sweep + bandW / 2, y - h * 0.3f, bandW / 2, h * 1.6f);
                g.resetAlphaComposite();
                g.endTransform();
                g.endClip();
            }
        }
        //NEW badge, held while the card is face-up. Above the card, not on it -
        //at phone card sizes an on-card badge sits exactly over the name line,
        //hiding what the card IS the first time it's ever seen
        if (rc.firstPull) {
            float fade = Math.min(1, glintT / 0.3f);
            float bw = Utils.scale(34);
            float bh = Utils.scale(16);
            float bx = x + (w - bw) / 2;
            float by = y - bh - Utils.scale(4);
            if (by < 0) {
                by = y + PADDING; //no headroom (shouldn't happen for the centered ceremony card)
                bx = x + PADDING;
            }
            g.setAlphaComposite(fade);
            g.fillRoundRect(FSkinColor.getStandardColor(new Color(0.9f, 0.75f, 0.2f, 1f)).getColor(),
                    bx, by, bw, bh, Utils.scale(3));
            g.drawText("NEW", FSkinFont.get(11), Color.BLACK,
                    bx, by, bw, bh, false, Align.center, true);
            g.resetAlphaComposite();
        }
    }

    private float rowLandingX(float w) {
        float miniW = (getHeight() * 0.16f - PADDING) / FCardPanel.ASPECT_RATIO;
        float step = miniW * 0.55f;
        int shown = Math.min(revealedRow.size() + 1, 8);
        return w / 2 + (shown - 1) * step / 2 - miniW / 2 + step;
    }

    private void drawRevealedRow(Graphics g, float w, float h, float rowH) {
        if (revealedRow.isEmpty()) {
            return;
        }
        float miniH = rowH - PADDING;
        float miniW = miniH / FCardPanel.ASPECT_RATIO;
        float step = miniW * 0.55f;
        int shown = Math.min(revealedRow.size(), 8);
        float totalW = miniW + (shown - 1) * step;
        float x = (w - totalW) / 2;
        float y = h - rowH;
        for (int i = revealedRow.size() - shown; i < revealedRow.size(); i++) {
            CardRenderer.drawCard(g, revealedRow.get(i).card, x, y, miniW, miniH, CardStackPosition.Top);
            x += step;
        }
        if (revealedRow.size() > shown) {
            g.drawText("+" + (revealedRow.size() - shown), FSkinFont.get(11),
                    FSkinColor.getStandardColor(Color.GRAY),
                    PADDING, y, w / 4, miniH, false, Align.left, true);
        }
    }

    private void drawHint(Graphics g, String text, float w, float h, float rowH) {
        g.drawText(text, FSkinFont.get(11), FSkinColor.getStandardColor(Color.GRAY),
                0, h - rowH - Utils.scale(16), w, Utils.scale(14), false, Align.center, true);
    }

    private String hintText(String key, String fallback) {
        return Forge.getLocalizer().getMessageorUseDefault(key, fallback);
    }
}
