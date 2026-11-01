package forge.web.limited;

import forge.StaticData;
import forge.card.CardEdition;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.CubeImporter;
import forge.gamemodes.limited.CustomLimited;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.gamemodes.limited.ThemedChaosDraft;
import forge.localinstance.properties.ForgePreferences;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.util.Localizer;
import forge.util.storage.IStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** What a limited event can be built from. It is read without prompting, for a frontend that asks for every choice at once. */
final class DraftProducts {
    private DraftProducts() {
    }

    /** Whether a block is offered for drafting. It must have draft packs and must not be Conspiracy, whose draft-time prompts only a local GUI can answer. */
    static boolean isDraftableBlock(final CardBlock block) {
        return block.getCntBoostersDraft() > 0 && !block.getName().contains("Conspiracy");
    }

    /** Desktop's preset set combinations for a block. The list is empty when desktop asks for one set per pack instead. */
    static List<String> draftCombos(final CardBlock block) {
        final List<String> sets = BoosterDraft.blockSets(block);
        return block.getCntBoostersDraft() == 3 && sets.size() > 1 && sets.size() < 4 ? BoosterDraft.getSetCombos(sets) : new ArrayList<>();
    }

    /** Throws IllegalArgumentException when no set fits the theme. */
    static BoosterDraft chaosDraft(final ThemedChaosDraft theme) {
        final BoosterDraft draft = BoosterDraft.createChaosDraft(theme);
        if (draft == null) {
            throw new IllegalArgumentException(Localizer.getInstance().getMessage("lblWebEventsNoSetForTheme", theme.getLabel()));
        }
        return draft;
    }

    /** Throws IllegalArgumentException, with a message a player can read, when the cube can't be imported. */
    static BoosterDraft cubeCobraDraft(final String cubeId) {
        return BoosterDraft.createCubeDraft(LimitedPoolType.Import, importCube(cubeId));
    }

    private static CustomLimited importCube(final String cubeId) {
        final CustomLimited imported;
        try {
            imported = new CubeImporter(cubeId).importCube();
        } catch (Exception e) {
            throw new IllegalArgumentException(Localizer.getInstance().getMessage("lblErrorImportingCube") + ": " + e.getMessage(), e);
        }
        if (imported == null) {
            throw new IllegalArgumentException(Localizer.getInstance().getMessage("lblFailedToImportCube") + ": " + cubeId);
        }
        return imported;
    }

    /** Throws IllegalArgumentException, with a message a player can read, when the cube can't be imported. */
    static SealedCardPoolGenerator cubeCobraSealed(final String cubeId, final int packs) {
        return SealedCardPoolGenerator.createImported(importCube(cubeId), packs);
    }

    public static DraftLists draft() {
        final List<String> cubes = BoosterDraft.loadCustomDrafts().stream().map(CustomLimited::getName).sorted().toList();
        final List<ThemedChaosDraft> themes = new ArrayList<>();
        FModel.getThemedChaosDrafts().forEach(themes::add);
        Collections.sort(themes);
        return new DraftLists(draftBlocks(FModel.getBlocks()), draftBlocks(FModel.getFantasyBlocks()), cubes,
                themes.stream().map(ThemedChaosDraft::getLabel).toList(),
                FModel.getPreferences().getPref(ForgePreferences.FPref.LAST_IMPORTED_CUBE_ID));
    }

    public static CustomLimited cube(final String name) {
        return BoosterDraft.loadCustomDrafts().stream().filter(c -> c.getName().equals(name)).findFirst().orElse(null);
    }

    public static ThemedChaosDraft theme(final String label) {
        for (final ThemedChaosDraft t : FModel.getThemedChaosDrafts()) {
            if (t.getLabel().equals(label)) {
                return t;
            }
        }
        return null;
    }

    private static List<DraftBlock> draftBlocks(final IStorage<CardBlock> storage) {
        final List<DraftBlock> out = new ArrayList<>();
        for (final CardBlock b : storage) {
            final List<String> sets = isDraftableBlock(b) ? BoosterDraft.blockSets(b) : List.of();
            if (!sets.isEmpty()) {
                out.add(new DraftBlock(b.getName(), b.getCntBoostersDraft(), sets, draftCombos(b)));
            }
        }
        return out;
    }

    public static SealedLists sealed() {
        final List<CardEdition> editions = new ArrayList<>();
        StaticData.instance().getEditions().getPrereleaseEditions().forEach(editions::add);
        editions.sort(Comparator.reverseOrder());
        final List<Edition> prereleases = editions.stream().map(e -> new Edition(e.getCode(), e.getName())).toList();
        final List<String> templates = SealedCardPoolGenerator.loadCustomSealed().stream().map(CustomLimited::getName).toList();
        return new SealedLists(sealedBlocks(FModel.getBlocks()), sealedBlocks(FModel.getFantasyBlocks()), prereleases, templates);
    }

    public static CardBlock block(final String name, final boolean fantasy) {
        return (fantasy ? FModel.getFantasyBlocks() : FModel.getBlocks()).get(name);
    }

    public static CustomLimited sealedTemplate(final String name) {
        return SealedCardPoolGenerator.loadCustomSealed().stream().filter(t -> t.getName().equals(name)).findFirst().orElse(null);
    }

    private static List<Block> sealedBlocks(final IStorage<CardBlock> storage) {
        final List<Block> out = new ArrayList<>();
        for (final CardBlock b : storage) {
            try {
                out.add(new Block(b.getName(), b.getCntBoostersSealed(), SealedCardPoolGenerator.blockCombos(b)));
            } catch (final RuntimeException e) {
                // Desktop's dialog fails on a block with no combo for its pack count, so it is not offered
            }
        }
        return out;
    }

    public record Block(String name, int packs, List<String> combos) {
    }

    public record Edition(String code, String name) {
    }

    public record SealedLists(List<Block> blocks, List<Block> fantasyBlocks, List<Edition> prereleases, List<String> templates) {
    }

    /** A draftable block: its pack count, its sets and desktop's preset combinations. combos is empty when the set of each pack is chosen. */
    public record DraftBlock(String name, int packs, List<String> sets, List<String> combos) {
    }

    public record DraftLists(List<DraftBlock> blocks, List<DraftBlock> fantasyBlocks, List<String> cubes, List<String> themes,
            String lastCube) {
    }
}
