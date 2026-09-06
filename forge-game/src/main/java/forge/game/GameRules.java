package forge.game;

import java.util.EnumSet;
import java.util.Set;

public class GameRules {
    private final GameType gameType;
    private boolean manaBurn;
    private boolean orderCombatants;
    private int poisonCountersToLose = 10; // is commonly 10, but turns into 15 for 2HG
    private int gamesPerMatch = 3;
    private int gamesToWinMatch = 2;
    private boolean playForAnte = false;
    private boolean matchAnteRarity = false;
    private boolean anteIncludeBasicLands = false;
    private boolean AISideboardingEnabled = false;
    private boolean sideboardForAI = false;
    private boolean allowCheatShuffle = false;
    private final Set<GameType> appliedVariants = EnumSet.noneOf(GameType.class);
    private int simTimeout = 120;

    // it's a preference, not rule... but I could hardly find a better place for it
    private boolean useGrayText;

    // whether to warn about cards AI can't play well
    private boolean warnAboutAICards = true;

    public GameRules(final GameType type) {
        this.gameType = type;
    }

    public GameType getGameType() {
        return gameType;
    }

    // Anvil M12 Build 0 (ADR-0102): deterministic, replay-stable game caps in
    // game units — a turn cap and a priority-window cap. 0 = off (upstream
    // behavior). A cap ends the game as a Draw; cap-aware reward is the
    // trainer's (loss/draw/cap = 0 for both seats — a stalling leader
    // forfeits the +1). Wall-clock clocks stay as crash guards only.
    private int anvilTurnCap = 0;
    private int anvilWindowCap = 0;

    public int getAnvilTurnCap() {
        return anvilTurnCap;
    }
    public void setAnvilTurnCap(final int cap) {
        this.anvilTurnCap = cap;
    }
    public int getAnvilWindowCap() {
        return anvilWindowCap;
    }
    public void setAnvilWindowCap(final int cap) {
        this.anvilWindowCap = cap;
    }

    public boolean hasManaBurn() {
        return manaBurn;
    }
    public void setManaBurn(final boolean manaBurn) {
        this.manaBurn = manaBurn;
    }

    public boolean hasOrderCombatants() {
        return orderCombatants;
    }
    public void setOrderCombatants(final boolean ordered) {
        this.orderCombatants = ordered;
    }

    public int getPoisonCountersToLose() {
        return poisonCountersToLose;
    }
    public void setPoisonCountersToLose(final int amount) {
        this.poisonCountersToLose = amount;
    }

    public int getGamesPerMatch() {
        return gamesPerMatch;
    }
    public void setGamesPerMatch(final int gamesPerMatch) {
        this.gamesPerMatch = gamesPerMatch;
        this.gamesToWinMatch = gamesPerMatch / 2 + 1;
    }

    public boolean useAnte() {
        return playForAnte;
    }
    public void setPlayForAnte(final boolean useAnte) {
        this.playForAnte = useAnte;
    }

    public boolean getMatchAnteRarity() {
        return matchAnteRarity;
    }
    public void setMatchAnteRarity(final boolean matchRarity) {
        matchAnteRarity = matchRarity;
    }

    public boolean getAnteIncludeBasicLands() {
        return anteIncludeBasicLands;
    }
    public void setAnteIncludeBasicLands(final boolean includeBasicLands) {
        anteIncludeBasicLands = includeBasicLands;
    }

    public boolean getSideboardForAI() {
        return sideboardForAI;
    }
    public void setSideboardForAI(final boolean sideboard) {
        sideboardForAI = sideboard;
    }

    public boolean getAISideboardingEnabled() {
        return AISideboardingEnabled;
    }
    public void setAISideboardingEnabled(final boolean aiSideboarding) {
        AISideboardingEnabled = aiSideboarding;
    }

    public boolean isAllowCheatShuffle() {
        return allowCheatShuffle;
    }
    public void setAllowCheatShuffle(boolean allowCheatShuffle) {
        this.allowCheatShuffle = allowCheatShuffle;
    }

    public int getGamesToWinMatch() {
        return gamesToWinMatch;
    }

    public void setAppliedVariants(final Set<GameType> appliedVariants) {
        if (appliedVariants != null && !appliedVariants.isEmpty())
            this.appliedVariants.addAll(appliedVariants);
    }

    public void addAppliedVariant(final GameType variant) {
        this.appliedVariants.add(variant);
    }

    public boolean hasAppliedVariant(final GameType variant) {
        return appliedVariants.contains(variant);
    }

    public boolean hasCommander() {
        return appliedVariants.contains(GameType.Commander)
                || appliedVariants.contains(GameType.Oathbreaker)
                || appliedVariants.contains(GameType.TinyLeaders)
                || appliedVariants.contains(GameType.Brawl);
    }

    public boolean useGrayText() {
        return useGrayText;
    }
    public void setUseGrayText(final boolean useGrayText) {
        this.useGrayText = useGrayText;
    }

    public boolean warnAboutAICards() {
        return warnAboutAICards;
    }
    public void setWarnAboutAICards(final boolean warnAboutAICards) {
        this.warnAboutAICards = warnAboutAICards;
    }

    public int getSimTimeout() {
        return this.simTimeout;
    }

    public void setSimTimeout(final int duration) {
        this.simTimeout = duration;
    }
}
