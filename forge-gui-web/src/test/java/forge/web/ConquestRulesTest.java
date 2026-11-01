package forge.web;

import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.planarconquest.ConquestAether;
import forge.gamemodes.planarconquest.ConquestAwardPool;
import forge.gamemodes.planarconquest.ConquestBattle;
import forge.gamemodes.planarconquest.ConquestChaosBattle;
import forge.gamemodes.planarconquest.ConquestCommander;
import forge.gamemodes.planarconquest.ConquestController;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestEvent.ChaosWheelOutcome;
import forge.gamemodes.planarconquest.ConquestLocation;
import forge.gamemodes.planarconquest.ConquestPlane;
import forge.gamemodes.planarconquest.ConquestPreferences.CQPref;
import forge.gamemodes.planarconquest.ConquestRegion;
import forge.gamemodes.planarconquest.ConquestReward;
import forge.gamemodes.planarconquest.ConquestRewardStep;
import forge.gamemodes.planarconquest.ConquestRewardStep.Kind;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/** Tests Conquest's rules in the shared package, with no screen. They are tested here because forge-gui's tests have no IGuiBase or FModel setup to load the cards. */
public class ConquestRulesTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() {
        ConquestFixture.cleanUp();
    }

    // Fails if a second conquest can use an existing conquest's name. It would then write into the first one's folder
    @Test
    public void aNameInUseIsRefused() {
        final String name = ConquestFixture.name();
        assertNull(ConquestUtil.nameProblem(name));
        ConquestFixture.create("Zendikar", name);
        assertNotNull(ConquestUtil.nameProblem(name));
        assertNotNull(ConquestUtil.nameProblem("  "));
    }

    // Fails if a created conquest is missing from the list, or reads back with a different place, balance or commander deck
    @Test
    public void aCreatedConquestIsListedAndReadsBack() {
        final ConquestData made = ConquestFixture.create("Zendikar");
        assertSame(FModel.getConquest().getModel(), made);
        final ConquestData read = ConquestController.listSaves().stream()
                .filter(d -> d.getName().equals(made.getName())).findFirst().orElse(null);
        assertNotNull(read, "the new conquest is not listed");
        assertEquals(read.getCurrentPlane().getName(), "Zendikar");
        assertEquals(read.getAEtherShards(), made.getAEtherShards());
        assertEquals(read.getUnlockedCardCount(), made.getUnlockedCardCount());
        final int deckSize = made.getSelectedCommander().getDeck().getMain().countAll();
        FModel.getConquest().load(read);
        assertEquals(read.getSelectedCommander().getDeck().getMain().countAll(), deckSize);
        assertTrue(deckSize >= 40);
    }

    // Fails if a Commander event's players do not start at 30 life with commanders set, or the match is not one game under Conquest's rules
    @Test
    public void aCommanderEventIsPreparedAsConquestPlaysIt() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation loc = eventWhere(data.getCurrentPlane(), v -> v.contains(GameType.Commander));
        final ConquestBattle battle = loc.getEvent().createBattle(loc, 0);
        final PreparedMatch prepared = FModel.getConquest().prepareBattle(battle, null);
        assertEquals(prepared.players().size(), 2);
        for (final RegisteredPlayer player : prepared.players()) {
            assertEquals(player.getStartingLife(), 30);
            assertFalse(player.getCommanders().isEmpty());
        }
        assertSame(prepared.players().get(0), prepared.human());
        assertEquals(prepared.rules().getGameType(), GameType.PlanarConquest);
        assertEquals(prepared.rules().getGamesPerMatch(), 1);
        assertNull(FModel.getConquest().prepareBattle(battle, null), "a second battle was prepared over the first");
    }

    // Fails if preparing an event with no Commander variant changes the commander's stored deck
    @Test
    public void preparingABattleLeavesTheStoredDeckAlone() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final int before = data.getSelectedCommander().getDeck().getMain().countAll();
        final ConquestLocation loc = eventWhere(data.getCurrentPlane(), Set::isEmpty);
        final PreparedMatch prepared =
                FModel.getConquest().prepareBattle(loc.getEvent().createBattle(loc, 0), null);
        assertEquals(prepared.human().getDeck().getMain().countAll(), before + 1);
        assertEquals(data.getSelectedCommander().getDeck().getMain().countAll(), before);
    }

    // Fails if leaving a chaos battle before it ends is not a loss, or stops the controller from starting another battle
    @Test
    public void aChaosBattleLeftEarlyIsALoss() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final int losses = data.getChaosBattleRecord().getLosses();
        final ConquestController conquest = FModel.getConquest();
        final PreparedMatch prepared = conquest.prepareBattle(new ConquestChaosBattle(), null);
        assertEquals(prepared.rules().getGamesPerMatch(), 3);
        conquest.finishBattle();
        assertEquals(data.getChaosBattleRecord().getLosses(), losses + 1);
        assertNull(conquest.getActiveBattle());
        final ConquestLocation loc = new ConquestLocation(data.getCurrentPlane(), 0, 0, 0);
        assertNotNull(conquest.prepareBattle(loc.getEvent().createBattle(loc, 0), null));
    }

    // Fails if the wheel, given an outcome, can stop on a spot that pays a different outcome
    @Test
    public void theWheelRestsOnTheOutcomeItWasGiven() {
        for (final ChaosWheelOutcome outcome : ChaosWheelOutcome.values()) {
            for (int i = 0; i < 20; i++) {
                assertEquals(ChaosWheelOutcome.getWheelOutcome(ChaosWheelOutcome.restingRotation(outcome)), outcome);
            }
        }
    }

    // Fails if a first win does not give its emblem before the wheel, a second win gives one, or the shards spot does not pay the wheel's shards
    @Test
    public void aFirstWinGivesAnEmblemThenTheWheel() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestBattle battle = wonBattle(data);
        final int wheelShards = FModel.getConquestPreferences().getPrefInt(CQPref.AETHER_WHEEL_SHARDS);
        final int conquerEmblems = FModel.getConquestPreferences().getPrefInt(CQPref.PLANESWALK_CONQUER_EMBLEMS);
        final int shards = data.getAEtherShards();
        final List<ConquestRewardStep> steps = FModel.getConquest().claimRewards(battle, ChaosWheelOutcome.SHARDS);
        assertEquals(kinds(steps), List.of(Kind.CONQUER_EMBLEMS, Kind.WHEEL, Kind.SHARDS));
        assertEquals(data.getPlaneswalkEmblems(), conquerEmblems);
        assertEquals(data.getAEtherShards(), shards + wheelShards);
        assertEquals(steps.get(2).amount(), wheelShards);

        // A second win at the same event gives no emblem
        data.addWin(battle);
        assertEquals(kinds(FModel.getConquest().claimRewards(battle, ChaosWheelOutcome.DOUBLE_SHARDS)), List.of(Kind.WHEEL, Kind.SHARDS));
        assertEquals(data.getPlaneswalkEmblems(), conquerEmblems);
        assertEquals(data.getAEtherShards(), shards + 3 * wheelShards);
    }

    // Fails if a commander comes before the region's last event, is missing on it, comes after the wheel, is not a new commander from the region, or comes again on a later win
    @Test
    public void conqueringARegionGivesOneOfItsCommanders() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final List<ConquestLocation> region = regionEvents(data.getCurrentPlane());
        for (final ConquestLocation loc : region.subList(0, region.size() - 1)) {
            assertFalse(kinds(winAt(data, loc, ChaosWheelOutcome.SHARDS)).contains(Kind.REGION_COMMANDER), "a commander before the region was conquered");
        }
        final ConquestLocation last = region.get(region.size() - 1);
        final List<ConquestRewardStep> steps = winAt(data, last, ChaosWheelOutcome.SHARDS);
        assertEquals(kinds(steps), List.of(Kind.CONQUER_EMBLEMS, Kind.REGION_COMMANDER, Kind.WHEEL, Kind.SHARDS));
        final ConquestReward commander = steps.get(1).cards().get(0);
        assertFalse(commander.isDuplicate());
        assertTrue(last.getRegion().getCommanders().contains(commander.getCard()));
        assertTrue(data.hasUnlockedCard(commander.getCard()));

        assertFalse(kinds(winAt(data, last, ChaosWheelOutcome.SHARDS)).contains(Kind.REGION_COMMANDER), "a second commander for the same region");
    }

    // Fails if owning another printing of each commander does not count, a duplicate unlocks a card, or it does not pay the duplicate value
    @Test
    public void aRegionWithEveryCommanderOwnedPaysShards() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final List<ConquestLocation> region = regionEvents(data.getCurrentPlane());
        int reprints = 0;
        for (final PaperCard commander : region.get(0).getRegion().getCommanders()) {
            if (data.hasUnlockedCard(commander)) {
                continue;
            }
            final PaperCard other = FModel.getMagicDb().getCommonCards().getAllCards(commander.getName()).stream()
                    .filter(pc -> !pc.equals(commander)).findFirst().orElse(null);
            if (other != null) {
                reprints++;
            }
            data.unlockCard(other != null ? other : commander);
        }
        assertTrue(reprints > 0, "no commander in the region has another printing");
        for (final ConquestLocation loc : region.subList(0, region.size() - 1)) {
            winAt(data, loc, ChaosWheelOutcome.CHAOS);
        }
        final int cards = data.getUnlockedCardCount();
        final int shards = data.getAEtherShards();
        final List<ConquestRewardStep> steps = winAt(data, region.get(region.size() - 1), ChaosWheelOutcome.CHAOS);
        assertEquals(kinds(steps), List.of(Kind.CONQUER_EMBLEMS, Kind.REGION_COMMANDER, Kind.WHEEL, Kind.CHAOS_BATTLE));
        final ConquestReward commander = steps.get(1).cards().get(0);
        assertEquals(commander.getReplacementShards(), ConquestUtil.getShardValue(commander.getCard(), CQPref.AETHER_BASE_DUPLICATE_VALUE));
        assertEquals(data.getAEtherShards(), shards + commander.getReplacementShards());
        assertEquals(data.getUnlockedCardCount(), cards);
    }

    // Fails if a booster's cards are not unlocked, its duplicates are not paid for, or two boosters are not two separate steps
    @Test
    public void boostersUnlockTheirCardsAndPayForDuplicates() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestBattle battle = wonBattle(data);
        final int shards = data.getAEtherShards();
        final int cards = data.getUnlockedCardCount();
        final List<ConquestRewardStep> steps = FModel.getConquest().claimRewards(battle, ChaosWheelOutcome.DOUBLE_BOOSTER);
        final List<ConquestRewardStep> boosters = steps.stream().filter(s -> s.kind() == Kind.BOOSTER).toList();
        assertEquals(boosters.size(), 2);
        assertEquals(boosters.get(1).number(), 2);
        assertEquals(boosters.get(1).total(), 2);
        int fresh = 0;
        int paid = 0;
        for (final ConquestRewardStep booster : boosters) {
            assertFalse(booster.cards().isEmpty());
            for (final ConquestReward reward : booster.cards()) {
                assertTrue(data.hasUnlockedCard(reward.getCard()));
                if (reward.isDuplicate()) {
                    paid += reward.getReplacementShards();
                } else {
                    fresh++;
                }
            }
        }
        assertEquals(data.getUnlockedCardCount(), cards + fresh);
        assertEquals(data.getAEtherShards(), shards + paid);
        final int owed = paid;
        assertEquals(steps.stream().anyMatch(s -> s.kind() == Kind.DUPLICATE_SHARDS && s.amount() == owed), paid > 0);
    }

    // Fails if a booster from an empty pool is shown as a pack with no cards
    @Test
    public void anEmptyBoosterIsLeftOut() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestBattle battle = wonBattle(data);
        final ConquestAwardPool pool = data.getCurrentPlane().getAwardPool();
        final List<List<PaperCard>> kept = List.of(new ArrayList<>(pool.commons), new ArrayList<>(pool.uncommons),
                new ArrayList<>(pool.rares), new ArrayList<>(pool.mythics));
        pool.commons.clear();
        pool.uncommons.clear();
        pool.rares.clear();
        pool.mythics.clear();
        try {
            final List<ConquestRewardStep> steps = FModel.getConquest().claimRewards(battle, ChaosWheelOutcome.BOOSTER);
            assertFalse(steps.stream().anyMatch(s -> s.kind() == Kind.BOOSTER));
        } finally {
            // The pool is cached on the plane for the whole process
            pool.commons.addAll(kept.get(0));
            pool.uncommons.addAll(kept.get(1));
            pool.rares.addAll(kept.get(2));
            pool.mythics.addAll(kept.get(3));
        }
    }

    // Fails if a lost battle is rewarded
    @Test
    public void aLostBattleGivesNothing() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation loc = new ConquestLocation(data.getCurrentPlane(), 0, 0, 0);
        final ConquestBattle battle = loc.getEvent().createBattle(loc, 0);
        data.addLoss(battle);
        assertTrue(FModel.getConquest().claimRewards(battle, ChaosWheelOutcome.SHARDS).isEmpty());
        assertEquals(data.getPlaneswalkEmblems(), 0);
    }

    // Fails if moving away from an event that opened a secret plane leaves that plane open, or the new place is not saved
    @Test
    public void movingAwayClosesThePortal() {
        final ConquestData data = ConquestFixture.create("Eldraine");
        final ConquestPlane plane = data.getCurrentPlane();
        final ConquestLocation portal = ConquestFixture.portal(plane);
        assertNotNull(portal, "Eldraine has lost its secret plane; pick another plane");
        final ConquestPlane secret = ConquestUtil.getPlaneByName(portal.getEvent().getTemporaryUnlock());
        data.setCurrentLocation(portal);
        data.addWin(portal.getEvent().createBattle(portal, 0));
        ConquestUtil.setPlaneTemporarilyAccessible(secret.getName(), true);
        assertFalse(secret.isUnreachable());

        // A move to the current place is not a move
        assertEquals(data.moveTo(portal).size(), 1);
        assertFalse(secret.isUnreachable());

        final ConquestLocation next = portal.getNeighbors().get(0);
        final List<ConquestLocation> path = data.moveTo(next);
        assertEquals(path.size(), 2);
        assertEquals(path.get(0), portal);
        assertTrue(secret.isUnreachable());
        assertEquals(new ConquestData(data.getDirectory()).getCurrentLocation(), next);
    }

    // Fails if the player can move to a place that is not next to a conquered event
    @Test
    public void anUnreachablePlaceIsNotMovedTo() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation start = data.getCurrentLocation();
        assertNull(data.moveTo(new ConquestLocation(data.getCurrentPlane(), 2, 1, 1)));
        assertEquals(data.getCurrentLocation(), start);
    }

    // Fails if a plane's cost is not the first-unlock price plus the increase for each plane already unlocked past the first
    @Test
    public void eachPlaneCostsMoreEmblems() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final int first = FModel.getConquestPreferences().getPrefInt(CQPref.PLANESWALK_FIRST_UNLOCK);
        final int more = FModel.getConquestPreferences().getPrefInt(CQPref.PLANESWALK_UNLOCK_INCREASE);
        assertEquals(data.getPlaneUnlockCost(), first);
        data.unlockPlane(FModel.getPlanes().get("Alara"));
        assertEquals(data.getPlaneUnlockCost(), first + more);
    }

    // Fails if a pull is free, gives a card the filter does not allow, or leaves the card locked
    @Test
    public void aPullCostsItsPriceAndUnlocksAMatchingCard() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestAether.Filter filter = ConquestAether.Filter.startingFor(data.getSelectedCommander());
        final ConquestAether.Pools pools = ConquestAether.pools(data, filter);
        assertFalse(pools.strict().isEmpty(), "the starting filter matches nothing on Zendikar");
        final int cost = ConquestAether.cost(pools, filter);
        assertEquals(cost, FModel.getConquestPreferences().getPrefInt(CQPref.AETHER_BASE_PULL_COST));
        assertTrue(cost > 0);
        final int shards = data.getAEtherShards();
        final PaperCard card = ConquestAether.pull(data, filter);
        assertNotNull(card);
        assertTrue(pools.filtered().contains(card));
        assertTrue(data.hasUnlockedCard(card));
        assertEquals(data.getAEtherShards(), shards - cost);
        assertFalse(ConquestAether.pools(data, filter).locked().contains(card));
    }

    // Fails if basic lands can be pulled, or a card already owned is offered again
    @Test
    public void theAetherHoldsOnlyLockedCardsThatAreNotBasicLands() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        for (final PaperCard card : ConquestAether.pools(data, ConquestAether.Filter.startingFor(data.getSelectedCommander())).locked()) {
            assertFalse(card.getRules().getType().isBasicLand());
            assertFalse(data.hasUnlockedCard(card));
        }
    }

    // Fails if exile pays the wrong price or accepts a card in a deck or the planeswalker, or a retrieval charges the wrong price or works without the shards
    @Test
    public void exilePaysAndRefusesWhatIsInUse() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final PaperCard inDeck = data.getSelectedCommander().getDeck().getMain().toFlatList().stream()
                .filter(c -> !c.getRules().getType().isBasicLand()).findFirst().orElseThrow();
        assertNotNull(data.getExileProblem(List.of(inDeck)));
        assertNotNull(data.getExileProblem(List.of(data.getPlaneswalker())));

        final PaperCard pulled = ConquestAether.pull(data, ConquestAether.Filter.startingFor(data.getSelectedCommander()));
        final List<PaperCard> cards = List.of(pulled);
        assertNull(data.getExileProblem(cards));
        final int value = data.getExileValue(cards);
        assertEquals(value, ConquestUtil.getShardValue(pulled, CQPref.AETHER_BASE_EXILE_VALUE));
        assertTrue(value > 0);
        final int shards = data.getAEtherShards();
        data.exile(cards);
        assertTrue(data.isInExile(pulled));
        assertEquals(data.getAEtherShards(), shards + value);
        assertFalse(ConquestUtil.getAvailablePool().contains(pulled));

        final int cost = data.getRetrieveCost(cards);
        assertEquals(cost, ConquestUtil.getShardValue(pulled, CQPref.AETHER_BASE_RETRIEVE_COST));
        assertNull(data.getRetrieveProblem(cards));
        data.retrieve(cards);
        assertFalse(data.isInExile(pulled));
        assertEquals(data.getAEtherShards(), shards + value - cost);
        data.exile(cards);
        data.spendAEtherShards(data.getAEtherShards());
        assertNotNull(data.getRetrieveProblem(cards));
    }

    // Fails if a statistics figure does not match the save. A new conquest has no wins and owns its starting cards
    @Test
    public void statisticsReadTheSave() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestData.Stats plane = data.getStats(data.getCurrentPlane());
        assertEquals(plane.shards(), data.getAEtherShards());
        assertEquals(plane.wins(), 0);
        assertEquals(plane.conquered(), 0);
        assertEquals(plane.events(), data.getCurrentPlane().getEventCount());
        assertTrue(plane.commanders() >= 1);
        assertTrue(plane.unlockedCards() > 0 && plane.unlockedCards() <= plane.cards());
        wonBattle(data);
        assertEquals(data.getStats(null).wins(), 1);
        assertEquals(data.getStats(null).conquered(), 1);
        assertTrue(data.getStats(null).events() > plane.events());
    }

    // Fails if a region's art does not name a card. The web requests the picture by that card
    @Test
    public void everyRegionOfAPlaneNamesItsArt() {
        for (final ConquestRegion region : FModel.getPlanes().get("Zendikar").getRegions()) {
            assertNotNull(region.getArtCard().card(), region.getName());
        }
    }

    // Fails if a new conquest's generated deck is not legal for Conquest, or the check accepts an emptied deck
    @Test
    public void theDeckCheckIsConquestsOwn() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        assertNull(data.getSelectedCommander().getDeckProblem());
        data.getSelectedCommander().getDeck().getMain().clear();
        assertNotNull(data.getSelectedCommander().getDeckProblem());
    }

    private static ConquestBattle wonBattle(final ConquestData data) {
        final ConquestLocation loc = new ConquestLocation(data.getCurrentPlane(), 0, 0, 0);
        final ConquestBattle battle = loc.getEvent().createBattle(loc, 0);
        data.addWin(battle);
        return battle;
    }

    /** The events of the plane's first region. */
    private static List<ConquestLocation> regionEvents(final ConquestPlane plane) {
        final List<ConquestLocation> events = new ArrayList<>();
        for (int row = 0; row < plane.getRowsPerRegion(); row++) {
            for (int col = 0; col < plane.getCols(); col++) {
                events.add(new ConquestLocation(plane, 0, row, col));
            }
        }
        return events;
    }

    private static List<ConquestRewardStep> winAt(final ConquestData data, final ConquestLocation loc, final ChaosWheelOutcome outcome) {
        final ConquestBattle battle = loc.getEvent().createBattle(loc, 0);
        data.addWin(battle);
        return FModel.getConquest().claimRewards(battle, outcome);
    }

    private static List<Kind> kinds(final List<ConquestRewardStep> steps) {
        return steps.stream().map(ConquestRewardStep::kind).toList();
    }

    /** The first place on the plane whose event variants match wanted. */
    static ConquestLocation eventWhere(final ConquestPlane plane, final Predicate<Set<GameType>> wanted) {
        for (int region = 0; region < plane.getRegions().size(); region++) {
            for (int row = 0; row < plane.getRowsPerRegion(); row++) {
                for (int col = 0; col < plane.getCols(); col++) {
                    final ConquestLocation loc = new ConquestLocation(plane, region, row, col);
                    if (wanted.test(loc.getEvent().getVariants())) {
                        return loc;
                    }
                }
            }
        }
        throw new AssertionError("no such event on " + plane.getName());
    }

    // Fails if a save written by the current code, which mobile also writes, no longer reads back as written
    @Test
    public void aSavedConquestStillLoads() throws Exception {
        final ConquestData data = ConquestFixture.install();
        assertEquals(data.getCurrentPlane().getName(), "Zendikar");
        assertEquals(data.getPlaneswalkEmblems(), 1);
        assertEquals(data.getAEtherShards(), 3000);
        assertEquals(data.getUnlockedCardCount(), 42);
        assertTrue(data.getCurrentPlaneData().hasConquered(new ConquestLocation(data.getCurrentPlane(), 0, 0, 0)));
        FModel.getConquest().load(data);
        assertEquals(data.getSelectedCommander().getName(), "A-Phylath, World Sculptor");
        assertEquals(data.getSelectedCommander().getDeck().getMain().countAll(), 40);
    }

    // Fails if exiling a commander listed before the selected one leaves a different commander selected
    @Test
    public void exilingACommanderKeepsTheSelection() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final List<PaperCard> more = new ArrayList<>();
        for (final PaperCard card : data.getCurrentPlane().getCommanders()) {
            if (!data.hasUnlockedCard(card) && more.size() < 2) {
                more.add(card);
            }
        }
        data.unlockCards(more);
        ConquestCommander third = null;
        for (final ConquestCommander c : data.getCommanders()) {
            if (c.getCard().equals(more.get(1))) {
                third = c;
            }
        }
        data.setSelectedCommander(third);
        assertNull(data.getExileProblem(List.of(more.get(0))));
        data.exile(List.of(more.get(0)));
        assertSame(data.getSelectedCommander(), third);
    }

    // Fails if the last commander can be exiled. The conquest would then have no commander and could not open
    @Test
    public void theLastCommanderCannotBeExiled() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestCommander only = data.getSelectedCommander();
        only.getDeck().getMain().clear();
        assertNotNull(data.getExileProblem(List.of(only.getCard())));
    }
}
