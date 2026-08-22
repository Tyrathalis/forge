package forge.card;

import com.badlogic.gdx.graphics.Texture;

import forge.Graphics;
import forge.assets.FImage;
import forge.assets.FTextureImage;
import forge.assets.ImageCache;

/**
 * A custom sleeve's image: the whole picture, cover-cropped to the space it is given.
 *
 * <p>Card art goes through {@link CardRenderer#getCardArt}, which cuts the art window out of a
 * card frame - exactly wrong for an image that is already a sleeve rather than a card. This takes
 * the texture as it is, and shares {@link CardAvatarImage}'s crop-offset convention so the two
 * kinds of sleeve frame themselves the same way.
 */
public class CustomSleeveArt implements FImage {
    private final String imageKey;
    private final int cropOffset;

    public CustomSleeveArt(final String imageKey0, final int cropOffset0) {
        imageKey = imageKey0;
        cropOffset = Math.max(0, Math.min(1000, cropOffset0));
    }

    private Texture texture() {
        return ImageCache.getInstance().getImage(imageKey, false);
    }

    @Override
    public float getWidth() {
        final Texture t = texture();
        return t != null ? t.getWidth() : 360f;
    }

    @Override
    public float getHeight() {
        final Texture t = texture();
        return t != null ? t.getHeight() : 500f;
    }

    @Override
    public void draw(final Graphics g, float x, float y, float w, float h) {
        final Texture t = texture();
        if (t == null) {
            return; // nothing stored for this key: the caller falls back to a built-in sleeve
        }
        g.startClip(x, y, w, h);

        final float aspectRatio = w / h;
        final float imageAspectRatio = (float) t.getWidth() / t.getHeight();
        final float f = cropOffset / 1000f;
        if (imageAspectRatio > aspectRatio) {
            final float w0 = w * imageAspectRatio / aspectRatio;
            x -= (w0 - w) * f;
            w = w0;
        } else {
            final float h0 = h * aspectRatio / imageAspectRatio;
            y -= (h0 - h) * f;
            h = h0;
        }
        new FTextureImage(t).draw(g, x, y, w, h);

        g.endClip();
    }
}
