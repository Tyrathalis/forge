package forge.screens.planarconquest;

import forge.Forge;
import forge.Graphics;
import forge.animation.ForgeAnimation;
import forge.assets.FSkinImage;
import forge.assets.FSkinTexture;
import forge.gamemodes.planarconquest.ConquestEvent.ChaosWheelOutcome;
import forge.toolbox.FOptionPane;
import forge.toolbox.FOverlay;
import forge.util.Aggregates;

public class ConquestChaosWheel extends FOverlay {
    public static void spin(ChaosWheelOutcome outcome0, Runnable callback0) {
        ConquestChaosWheel wheel = new ConquestChaosWheel(outcome0, callback0);
        wheel.show();
    }

    private final WheelSpinAnimation animation;
    private final Runnable callback;

    private ConquestChaosWheel(ChaosWheelOutcome outcome0, Runnable callback0) {
        animation = new WheelSpinAnimation(outcome0);
        callback = callback0;
    }

    @Override
    public void setVisible(boolean visible0) {
        if (this.isVisible() == visible0) { return; }

        super.setVisible(visible0);

        if (visible0) {
            animation.start();
        }
    }

    @Override
    public void drawOverlay(Graphics g) {
        //draw wheel
        float wheelSize = getWidth() - 2 * FOptionPane.PADDING;
        if (Forge.isLandscapeMode()) {
            wheelSize /= 2;
        }
        float x = (getWidth() - wheelSize) / 2;
        float y = (getHeight() - wheelSize) / 2;
        FSkinTexture.BG_CHAOS_WHEEL.drawRotated(g, x, y, wheelSize, wheelSize, animation.getWheelRotation());

        //draw spoke at top using Planeswalker icon
        float spokeSize = wheelSize * 0.15f;
        FSkinImage.PW_BADGE_UNCOMMON.draw(g, x + (wheelSize - spokeSize) / 2, y - spokeSize * 0.75f, spokeSize, spokeSize);
    }

    @Override
    protected void doLayout(float width, float height) {
    }

    private class WheelSpinAnimation extends ForgeAnimation {
        private static final float WAIT_DURATION = 1f;

        private final float velocity = Aggregates.randomInt(360, 720);
        private final float deceleration = Aggregates.randomInt(50, 100);
        private final float spinDuration = velocity / deceleration;
        private final float start;
        private float time;

        private WheelSpinAnimation(ChaosWheelOutcome outcome) {
            // The wheel's rotation is the negative of this position, so the place to stop is the negative of the outcome's
            float distance = velocity * velocity / (2 * deceleration);
            start = -ChaosWheelOutcome.restingRotation(outcome) - distance;
        }

        private float getWheelRotation() {
            float t = Math.min(time, spinDuration);
            float position = start + velocity * t - deceleration * t * t / 2;
            return -(position % 360f); //use negative so wheel rotates clockwise
        }

        @Override
        protected boolean advance(float dt) {
            time += dt;
            // Wait a bit after the wheel stops spinning before ending
            return time < spinDuration + WAIT_DURATION;
        }

        @Override
        protected void onEnd(boolean endingAll) {
            hide();
            callback.run();
        }
    }

    @Override
    public boolean keyDown(int keyCode) {
        return true; //suppress key pressing while this overlay is open
    }
}
