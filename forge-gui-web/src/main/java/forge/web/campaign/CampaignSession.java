package forge.web.campaign;

import forge.deck.DeckGroup;
import forge.game.GameType;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.planarconquest.ConquestEvent.ChaosWheelOutcome;
import forge.gamemodes.quest.QuestEventDraft;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.IHasForgeLog;
import forge.util.storage.IStorage;
import forge.web.Campaigns;
import forge.web.WebGuiBase;
import forge.web.WebSession;
import forge.web.WebSession.InCampaign;
import forge.web.WebSession.Menu;
import forge.web.WebSession.Playing;
import forge.web.WebSession.Stage;
import forge.web.deck.DeckSession;
import forge.web.limited.DraftOwner;
import forge.web.match.LocalGame;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.CampaignProtocol.*;
import forge.web.protocol.Commands;
import forge.web.protocol.Commands.Handler;
import forge.web.protocol.FromBrowser.Bare;
import forge.web.protocol.FromBrowser.CatalogueQuery;
import forge.web.protocol.FromBrowser.Plain;
import forge.web.protocol.ToBrowser.Notice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/** One session's campaign modes. It holds the commands their pages share, the thread they run on, and what they show as the browser moves between pages. */
public final class CampaignSession implements Campaigns, IHasForgeLog {
    private final Campaigns.Host session;
    /** The open conquest's state that only this session holds: its selection and the last move. */
    private final ConquestGame conquest = new ConquestGame();
    private final QuestGame quest = new QuestGame();
    /** One thread for all campaign work, because the campaign model is Forge's own and has no locking. */
    private final Executor work = WebGuiBase.worker("WebCampaign");

    public CampaignSession(final Campaigns.Host session) {
        this.session = session;
    }

    private Campaign campaign(final String mode) {
        return "quest".equals(mode) ? quest : "conquest".equals(mode) ? conquest : null;
    }

    private final Campaign.Host host = new Campaign.Host() {
        @Override
        public Record error(final String text) {
            return WebSession.error(text);
        }

        @Override
        public DeckSession decks() {
            return session.decks();
        }

        @Override
        public void opened(final String save) {
            final Stage from = session.stage();
            if (from instanceof InCampaign c && c.save() == null && session.move(from, new InCampaign(c.mode(), save))) {
                show();
            }
        }

        @Override
        public void startMatch(final Supplier<PreparedMatch> prepare, final Runnable failed) {
            if (!(session.stage() instanceof InCampaign back)) {
                return;
            }
            // A game's result belongs to the campaign that started it, wherever the browser is when the result is recorded
            final Campaign campaign = campaign(back.mode());
            session.startMatch(back, gui -> {
                final PreparedMatch prepared = prepare.get();
                if (prepared == null) {
                    throw new IllegalStateException("A match is already being played");
                }
                // The game names its winner by this name. The board finds the seat by the session's player name
                prepared.human().getPlayer().setName(session.playerName());
                // Quest plays as Forge's one shared human player. Its avatar and sleeve are whatever the last match set
                prepared.human().getPlayer().setAvatarIndex(session.avatarIndex());
                prepared.human().getPlayer().setSleeveIndex(LocalGame.storedIndex(FPref.UI_SLEEVES, 0));
                final LocalGame local = session.local();
                local.openAlone(session.playerName(), gui).startPrepared(prepared, local.webSeat());
            }, failed, game -> run(() -> {
                final Record result = campaign.gameOver(game);
                if (result != null) {
                    session.tell(result);
                    campaign.resultSent();
                }
            }));
        }

        @Override
        public void startDraft(final BoosterDraft draft) {
            session.startDraft(draft, new DraftOwner() {
                @Override
                public String deckName() {
                    return QuestEventDraft.DECK_NAME;
                }

                @Override
                public void saved(final DeckGroup decks) {
                    run(() -> {
                        final IStorage<DeckGroup> pools = quest.drafted(decks);
                        show();
                        session.decks().openPool(decks.getHumanDeck(), pools, GameType.QuestDraft, session.browser());
                    });
                }

                @Override
                public void discarded() {
                    // Left as desktop leaves it, with the fee refunded
                    run(() -> {
                        quest.cancelDraft();
                        show();
                    });
                }
            });
        }
    };

    public void run(final Runnable job) {
        work.execute(job);
    }

    /** A command that works on the open save of a campaign. */
    private interface OpenHandler<T> {
        void run(CampaignSession campaigns, BrowserChannel channel, Campaign campaign, T command);
    }

    /** Runs on the campaign's thread, and only while one of its saves is open. */
    private static <T extends Record> Handler<CampaignSession, T> inOpen(final OpenHandler<T> handler) {
        return (s, ch, command) -> s.run(() -> {
            if (s.session.stage() instanceof InCampaign c && c.save() != null) {
                handler.run(s, ch, s.campaign(c.mode()), command);
            }
        });
    }

    /** Runs on the campaign's thread while a mode is open, with or without a save. A mode's preferences belong to the process, not to a save. */
    private static <T extends Record> Handler<CampaignSession, T> inMode(final OpenHandler<T> handler) {
        return (s, ch, command) -> s.run(() -> {
            if (s.session.stage() instanceof InCampaign c) {
                handler.run(s, ch, s.campaign(c.mode()), command);
            }
        });
    }

    /** The commands every mode's pages share. Each mode's own are in its table. */
    public static final Commands<CampaignSession> COMMANDS = new Commands<CampaignSession>()
            // A campaign is Forge's own, with one per process, so only the host may open it
            .on(CampaignOpen.class, (s, ch, o) -> {
                final Campaign campaign = s.campaign(o.mode());
                if (s.session.hosting() && campaign != null) {
                    s.run(() -> s.open(o.mode(), o.resume() ? campaign.current() : null));
                }
            })
            .on(CampaignLoad.class, (s, ch, l) -> s.run(() -> {
                if (s.session.stage() instanceof InCampaign c) {
                    s.open(c.mode(), l.name());
                }
            }))
            .on(CampaignPlain.campaignLeave, (s, ch, l) -> s.run(() -> {
                final Stage now = s.session.stage();
                if (now instanceof InCampaign c) {
                    if (c.save() == null) {
                        s.session.move(now, new Menu());
                    } else {
                        s.open(c.mode(), null);
                    }
                }
            }))
            .on(CampaignRename.class, (s, ch, r) -> s.run(() -> s.shelf(ch, campaign -> campaign.rename(r.name(), r.to()))))
            .on(CampaignDelete.class, (s, ch, d) -> s.run(() -> s.shelf(ch, campaign -> {
                campaign.delete(d.name());
                return null;
            })))
            .on(CampaignPlain.rewardClaim, inOpen((s, ch, campaign, c) -> campaign.claim(s.host)))
            .on(CampaignStatsQuery.class, inOpen((s, ch, campaign, q) -> ch.send(campaign.stats(q.scope()))))
            .on(CampaignPlain.campaignPrefs, inMode((s, ch, campaign, c) -> ch.send(campaign.prefs(null))))
            .on(CampaignPref.class, inMode((s, ch, campaign, p) -> ch.send(campaign.prefs(campaign.setPref(p.key(), p.value())))))
            .on(CampaignPlain.campaignPrefsReset, inMode((s, ch, campaign, c) -> {
                campaign.resetPrefs();
                ch.send(campaign.prefs(null));
            }))
            .on(CampaignPlain.trading, inOpen((s, ch, campaign, c) -> ch.send(campaign.trading())))
            .on(Trade.class, inOpen((s, ch, campaign, trade) -> {
                final String problem = campaign.trade(trade.source(), trade.picks());
                if (problem != null) {
                    ch.send(new Notice(problem, null, false));
                }
                // The traded cards may have been on any of the campaign's pages
                campaign.page().forEach(m -> ch.send(m));
                final Reward opened = campaign.reward();
                if (opened != null) {
                    ch.send(opened);
                }
                ch.send(campaign.trading());
            }))
            // The wheel belongs to the conquest, not to a game, so this cheat is not one of DevMode's
            .on(DevConquestWheel.class, (s, ch, w) -> {
                if (s.session.hosting() && FModel.getPreferences().getPrefBoolean(FPref.DEV_MODE_ENABLED)) {
                    try {
                        s.conquest.setNextWheel(w.outcome() == null || w.outcome().isEmpty() ? null : ChaosWheelOutcome.valueOf(w.outcome()));
                    } catch (final IllegalArgumentException e) {
                        netLog.warn("Not a wheel outcome: {}", w.outcome());
                    }
                }
            });

    @Override
    public boolean handles(final Record command) {
        return COMMANDS.handles(command) || ConquestGame.COMMANDS.handles(command) || QuestGame.COMMANDS.handles(command);
    }

    @Override
    public void run(final BrowserChannel channel, final Record command) {
        if (COMMANDS.run(this, channel, command)) {
            return;
        }
        // Every other command from a campaign's pages belongs to the open mode
        run(() -> {
            if (session.stage() instanceof InCampaign c) {
                campaign(c.mode()).handle(channel, command, c.save(), host);
            }
        });
    }

    @Override
    public boolean deckCommand(final BrowserChannel channel, final Record command) {
        if (command instanceof CatalogueQuery q && q.source() != null) {
            // A campaign's own cards, which do not need an open deck
            inOpen((s, ch, campaign, c) -> ch.send(campaign.cards(q))).run(this, channel, command);
            return true;
        }
        if (!(session.stage() instanceof InCampaign)) {
            return false;
        }
        // A campaign's collection editor reads and saves the campaign's cards. Only the campaign thread may touch them
        run(() -> session.decks().onCommand(channel, command));
        // A deck may have changed size or become playable, and the campaign's pages show both
        if (command instanceof Bare b && b.t() == Plain.editorClose) {
            inOpen((s, ch, campaign, c) -> campaign.page().forEach(m -> ch.send(m))).run(this, channel, command);
        }
        return true;
    }

    @Override
    public void reconnected(final BrowserChannel channel, final Stage now) {
        if (now instanceof Playing p && p.back() instanceof InCampaign c) {
            final Record result = campaign(c.mode()).result();
            if (result != null) {
                channel.send(result);
            }
        } else if (now instanceof InCampaign) {
            run(this::show);
        }
    }

    @Override
    public Map<String, String> resume() {
        final Map<String, String> saves = new LinkedHashMap<>();
        final String conquestSave = conquest.current();
        if (conquestSave != null) {
            saves.put("conquest", conquestSave);
        }
        final String questSave = quest.current();
        if (questSave != null) {
            saves.put("quest", questSave);
        }
        return saves;
    }

    @Override
    public void becameHost() {
        // The cards Forge shows the host outside a match are the ones a quest purchase or prize opened
        session.ui().setCardsSink(quest::shown);
    }

    @Override
    public void left(final Playing from, final InCampaign back) {
        // Queued after the last game's result, so the campaign records the game before it is told the match was left
        final Campaign campaign = campaign(back.mode());
        run(() -> {
            if (session.stage() == from) {
                campaign.left();
                if (session.move(from, back) && session.browser() != null) {
                    show();
                }
            }
        });
    }

    /** Sends what the campaign stage draws: the saved games, or the open save's pages and any reward not yet shown. */
    @Override
    public void show() {
        if (!(session.stage() instanceof InCampaign c)) {
            return;
        }
        final Campaign campaign = campaign(c.mode());
        if (c.save() == null) {
            session.tell(campaign.saves());
        } else {
            campaign.page().forEach(session::tell);
            final Reward pending = campaign.reward();
            if (pending != null) {
                session.tell(pending);
            }
        }
    }

    /** Opens a campaign's list of saved games, or one save. Reading a save is slow, so this runs as campaign work. */
    private void open(final String mode, final String save) {
        final Stage from = session.stage();
        final Campaign campaign = campaign(mode);
        if (!session.hosting() || campaign == null || !(from instanceof Menu || from instanceof InCampaign)) {
            return;
        }
        // A deck from the campaign being left can no longer be built
        session.decks().closeCollectionDeck(session.browser());
        if (session.move(from, new InCampaign(mode, save == null ? null : campaign.open(save)))) {
            show();
        }
    }

    /** Applies a change from the menu of a saved game in the saves list. change returns why it could not be made, or null. */
    private void shelf(final BrowserChannel channel, final Function<Campaign, String> change) {
        if (!session.hosting() || !(session.stage() instanceof InCampaign c) || c.save() != null) {
            return;
        }
        final Campaign campaign = campaign(c.mode());
        final String problem = change.apply(campaign);
        channel.send(campaign.saves());
        // The start page's Resume button names the save played last
        channel.send(session.hello());
        // Sent last, because the browser clears any error on each hello
        if (problem != null) {
            channel.send(WebSession.error(problem));
        }
    }
}
