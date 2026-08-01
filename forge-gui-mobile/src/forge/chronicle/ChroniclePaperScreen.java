package forge.chronicle;

import forge.Forge;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChroniclePaper;
import forge.screens.FScreen;
import forge.toolbox.FTextArea;

/**
 * The daily Chronicle issue: a plain full-pane read of what ChroniclePaper
 * composed for the current played day (pre-collection, that's yesterday's
 * paper — the player is still living the previous in-game day).
 */
public class ChroniclePaperScreen extends FScreen {

    private final FTextArea text = add(new FTextArea(false, ""));

    public ChroniclePaperScreen() {
        super(Forge.getLocalizer().getMessageorUseDefault("lblChroniclePaper", "Read the Chronicle"));
    }

    @Override
    public void onActivate() {
        super.onActivate();
        ChronicleController controller = ChronicleHub.controller();
        if (controller == null || controller.getRun() == null) {
            text.setText("");
            return;
        }
        int day = controller.getRun().timeline.getDayIndex();
        ChroniclePaper.Issue issue = ChronicleHub.paper().composeFor(
                controller.getRun().runSeed, day, controller.lgsStock());

        StringBuilder sb = new StringBuilder();
        sb.append("========  THE CHRONICLE  ========\n");
        sb.append(Forge.getLocalizer().getMessageorUseDefault("lblChronicleDay", "Day")).append(' ')
          .append(day + 1).append('\n');
        sb.append("=================================\n\n");
        for (String headline : issue.headlines) {
            sb.append("* ").append(headline).append('\n');
        }
        if (!issue.headlines.isEmpty()) {
            sb.append('\n');
        }
        if (!issue.lgsNotes.isEmpty()) {
            sb.append(Forge.getLocalizer().getMessageorUseDefault("lblChronicleStoreNotes", "At the store:")).append('\n');
            for (String note : issue.lgsNotes) {
                sb.append("* ").append(note).append('\n');
            }
            sb.append('\n');
        }
        if (!issue.flavor.isEmpty()) {
            sb.append("---\n").append(issue.flavor).append('\n');
        }
        text.setText(sb.toString());
        revalidate();
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float padding = width * 0.04f;
        text.setBounds(padding, startY + padding, width - 2 * padding, height - startY - 2 * padding);
    }
}
