package forge.gui.interfaces;

import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;

import java.util.List;

public interface IWinLoseView<T extends IButton> {
    T getBtnContinue();
    T getBtnRestart();
    T getBtnQuit();
    void hide();

    void showRewards(Runnable runnable);
    void showCards(String title, List<PaperCard> cards);

    /** The cards the player lost to ante, which a view can set apart from those won. */
    default void showLostCards(String title, List<PaperCard> cards) {
        showCards(title, cards);
    }
    void showMessage(String message, String title, FSkinProp icon);
}
