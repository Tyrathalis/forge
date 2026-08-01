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

        /** Worth a brief hold in the cascade, not a full stop. */
        boolean softPause() {
            return firstPull && rarityRank() == 2;
        }
    }

    /** One sealed product staged for reveal (a pack, or a starter's card block). */
    public static final class RevealPack {
        public final String title;
        public final String artKey; //booster/tournament-pack art, may miss (placeholder drawn)
        public final List<RevealCard> cards;

        public RevealPack(String title, String artKey, List<RevealCard> cards) {
            this.title = title;
            this.artKey = artKey;
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
    private static final float TEAR_DRAGS_TO_OPEN = 1.1f;   //fraction of wrapper width the finger must travel
    private static final float TEAR_FLY_DURATION = 0.45f;
    private static final float FLIP_DURATION = 0.32f;
    private static final float FLY_DURATION = 0.22f;
    private static final float CASCADE_FLIP_DURATION = 0.13f;
    private static final float CASCADE_FLY_DURATION = 0.09f;
    private static final float CASCADE_DWELL = 0.05f;        //face-up beat between flip and fly in the cascade
    private static final float SOFT_PAUSE_HOLD = 0.7f;
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
                    break; //waiting on the player (ceremony) — cascade never rests here
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
                        float hold = current().softPause() ? SOFT_PAUSE_HOLD : CASCADE_DWELL;
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

    @Override
    public boolean pan(float x, float y, float deltaX, float deltaY, boolean moreVertical) {
        if (phase == Phase.TEAR_IDLE) {
            tearProgress += Math.abs(deltaX) / (getWidth() * TEAR_DRAGS_TO_OPEN);
            if (tearProgress >= 1) {
                tearProgress = 1;
                startTearFly();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean fling(float velocityX, float velocityY) {
        if (phase == Phase.TEAR_IDLE) {
            tearProgress = 1;
            startTearFly();
            return true;
        }
        return false;
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
        if (!shown.isEmpty()) {
            CardZoom.show(shown, shown.size() - 1, null);
        }
        return true;
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

                drawPackArt(g, pack().artKey, pack().title, artX, cardY, cardW, cardH, 1);
                g.drawText(pack().title, FSkinFont.get(13), FLabel.getInlineLabelColor(),
                        0, cardY + cardH + PADDING, w, Utils.scale(18), false, Align.center, false);
                break;
            }
            case FINISHED:
                break;
        }

        drawRevealedRow(g, w, h, rowH);

        if (batch && !cascading && phase == Phase.CARD_UP) {
            drawHint(g, hintText("lblChronicleTapToResume", "Tap to resume"), w, h, rowH);
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
        g.drawText(Forge.getLocalizer().getMessageorUseDefault("lblChronicleTearToOpen", "Drag across to tear open"),
                FSkinFont.get(13), FSkinColor.getStandardColor(Color.GRAY),
                wiggle, cy + stageH / 2 - Utils.scale(2), w, Utils.scale(18), false, Align.center, false);
    }

    /** Wrapper art split at the tear line; flyT > 0 animates strip + body leaving. */
    private void drawTornWrapper(Graphics g, float cx, float cy, float stageH, float flyT) {
        float artH = stageH * 0.8f;
        float artW = artH * 0.72f;
        String artKey = pack().artKey;
        Texture art = artKey == null ? null : ImageCache.getInstance().getImage(artKey, true);
        boolean real = art != null && art != ImageCache.getInstance().getDefaultImage();
        if (real) {
            float texAspect = (float) art.getWidth() / art.getHeight();
            artW = artH * Math.min(texAspect, 0.85f);
        }
        float ax = cx - artW / 2;
        float ay = cy - artH / 2;
        float stripH = artH * TEAR_STRIP_FRACTION;

        //body (below the tear line): drops down and fades once flying
        float bodyDy = flyT * stageH * 0.7f;
        float bodyAlpha = 1 - flyT;
        if (flyT > 0) {
            g.setAlphaComposite(bodyAlpha);
        }
        if (real) {
            int srcY = (int) (art.getHeight() * TEAR_STRIP_FRACTION);
            g.drawRotatedImage(art, ax, ay + stripH + bodyDy, artW, artH - stripH,
                    cx, cy + bodyDy, 0, srcY, art.getWidth(), art.getHeight() - srcY, 0);
        } else {
            g.fillRect(FSkinColor.getStandardColor(new Color(0.16f, 0.14f, 0.22f, 1f)).getColor(),
                    ax, ay + stripH + bodyDy, artW, artH - stripH);
            g.drawText(pack().title, FSkinFont.get(14), Color.WHITE,
                    ax, ay + stripH + bodyDy, artW, artH - stripH, true, Align.center, true);
        }
        if (flyT > 0) {
            g.resetAlphaComposite();
        }

        //strip (above the tear line): shears right with drag, flies up-right when torn
        float dx = tearProgress * artW * 0.35f + flyT * getWidth() * 0.9f;
        float dy = -flyT * getHeight() * 0.35f;
        float angle = tearProgress * 7 + flyT * 40;
        float stripAlpha = 1 - flyT;
        g.setAlphaComposite(stripAlpha);
        if (real) {
            g.drawRotatedImage(art, ax + dx, ay + dy, artW, stripH,
                    ax + dx, ay + dy + stripH, 0, 0,
                    art.getWidth(), (int) (art.getHeight() * TEAR_STRIP_FRACTION), angle);
        } else {
            g.startRotateTransform(ax + dx, ay + dy + stripH, angle);
            g.fillRect(FSkinColor.getStandardColor(new Color(0.22f, 0.19f, 0.3f, 1f)).getColor(),
                    ax + dx, ay + dy, artW, stripH);
            g.endTransform();
        }
        g.resetAlphaComposite();

        //the torn paper edge
        if (tearProgress > 0.02f && flyT < 1) {
            g.setAlphaComposite((0.35f + 0.5f * tearProgress) * (1 - flyT));
            g.drawLine(Utils.scale(1.5f), Color.WHITE, ax, ay + stripH + bodyDy, ax + artW, ay + stripH + bodyDy);
            g.resetAlphaComposite();
        }
    }

    private void drawPackArt(Graphics g, String artKey, String title, float x, float y, float w, float h, float alpha) {
        Texture art = artKey == null ? null : ImageCache.getInstance().getImage(artKey, true);
        boolean real = art != null && art != ImageCache.getInstance().getDefaultImage();
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
        //NEW badge, held while the card is face-up
        if (rc.firstPull) {
            float fade = Math.min(1, glintT / 0.3f);
            float bw = Utils.scale(34);
            float bh = Utils.scale(16);
            g.setAlphaComposite(fade);
            g.fillRoundRect(FSkinColor.getStandardColor(new Color(0.9f, 0.75f, 0.2f, 1f)).getColor(),
                    x + PADDING, y + PADDING, bw, bh, Utils.scale(3));
            g.drawText("NEW", FSkinFont.get(11), Color.BLACK,
                    x + PADDING, y + PADDING, bw, bh, false, Align.center, true);
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
