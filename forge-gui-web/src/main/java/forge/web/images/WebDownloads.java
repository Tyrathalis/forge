package forge.web.images;

import com.google.gson.JsonObject;
import forge.gui.UiCommand;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IButton;
import forge.gui.interfaces.IProgressBar;
import forge.gui.interfaces.ITextField;
import forge.localinstance.skin.FSkinProp;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.web.protocol.ToBrowser;
import forge.web.protocol.Wire;

import java.util.function.Consumer;

/** Runs a download through the shared service. The browser shows the progress instead of the dialog that desktop and mobile use. */
public final class WebDownloads implements IHasForgeLog {
    private WebDownloads() {
    }

    public static void run(final GuiDownloadService service, final Consumer<Boolean> callback,
            final Consumer<JsonObject> toBrowser) {
        final Progress progress = new Progress(service.getTitle(), toBrowser);
        try {
            // No dialog waits for Start and Close here. So the service is set up, then run to completion on this thread
            service.initialize(new Text(""), new Text(""), progress, new StartButton(), () -> { }, null, () -> { });
            service.run();
        } catch (final RuntimeException e) {
            netLog.error(e, "Download failed");
            progress.say(Localizer.getInstance().getMessage("lblWebDownloadFailed"));
            callback.accept(false);
            return;
        }
        progress.finish();
        callback.accept(true);
    }

    /** Reports a download's progress as a notice, which the browser already knows how to show. */
    private static final class Progress implements IProgressBar {
        private final String title;
        private final Consumer<JsonObject> toBrowser;
        private String description;
        private int maximum = 1;

        Progress(final String title, final Consumer<JsonObject> toBrowser) {
            this.title = title;
            this.toBrowser = toBrowser;
        }

        void finish() {
            say(description == null ? Localizer.getInstance().getMessage("lblWebDownloadFinished") : description);
        }

        void say(final String text) {
            toBrowser.accept(Wire.encode(new ToBrowser.Notice(title, text, false)));
        }

        @Override public void setDescription(final String s) {
            description = s;
            say(s);
        }

        @Override public void setValue(final int progress) {
        }

        @Override public void reset() {
        }

        @Override public void setShowETA(final boolean b) {
        }

        @Override public void setShowCount(final boolean b) {
        }

        @Override public void setPercentMode(final boolean percentMode) {
        }

        @Override public void setMaximum(final int maximum0) {
            maximum = maximum0;
        }

        @Override public int getMaximum() {
            return maximum;
        }
    }

    /** The dialog's Start button. Nothing presses it here, because the service is run directly. */
    private static final class StartButton implements IButton {
        private boolean enabled;

        @Override public boolean isEnabled() { return enabled; }
        @Override public void setEnabled(final boolean b) { enabled = b; }
        @Override public boolean isVisible() { return true; }
        @Override public void setVisible(final boolean b) { }
        @Override public String getToolTipText() { return ""; }
        @Override public void setToolTipText(final String s) { }
        @Override public String getText() { return ""; }
        @Override public void setText(final String text) { }
        @Override public void setSelected(final boolean b) { }
        @Override public boolean isSelected() { return false; }
        @Override public void setCommand(final UiCommand command0) { }
        @Override public void setImage(final FSkinProp color) { }
        @Override public void setTextColor(final int r, final int g, final int b) { }
        @Override public boolean requestFocusInWindow() { return false; }
    }

    /** The proxy address and port fields of a download dialog, left empty because no proxy is chosen here. */
    public record Text(String value) implements ITextField {
        @Override public String getText() { return value; }
        @Override public void setText(final String text) { }
        @Override public boolean isEnabled() { return false; }
        @Override public void setEnabled(final boolean b) { }
        @Override public boolean isVisible() { return true; }
        @Override public void setVisible(final boolean b) { }
        @Override public String getToolTipText() { return ""; }
        @Override public void setToolTipText(final String s) { }
        @Override public boolean requestFocusInWindow() { return false; }
    }
}
