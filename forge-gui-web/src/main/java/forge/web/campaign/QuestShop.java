package forge.web.campaign;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.quest.QuestController;
import forge.gamemodes.quest.QuestMode;
import forge.gamemodes.quest.QuestSpellShop;
import forge.gamemodes.quest.QuestUtil;
import forge.gamemodes.quest.bazaar.IQuestBazaarItem;
import forge.gamemodes.quest.bazaar.QuestBazaarManager;
import forge.gamemodes.quest.bazaar.QuestItemBasic;
import forge.gamemodes.quest.bazaar.QuestPetController;
import forge.gamemodes.quest.bazaar.QuestStallDefinition;
import forge.gamemodes.quest.data.QuestAssets;
import forge.item.InventoryItem;
import forge.item.PaperCard;
import forge.itemmanager.SItemManagerUtil;
import forge.util.ItemPool;
import forge.util.Localizer;
import forge.web.WebGuiBase;
import forge.web.deck.CardCatalog;
import forge.web.protocol.CampaignProtocol.*;
import forge.web.protocol.FromBrowser.CatalogueQuery;
import forge.web.protocol.ToBrowser.*;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Quest's two shops, as QuestGame offers them: the spell shop, which buys and sells cards, and the bazaar's stalls. */
final class QuestShop {
    private QuestShop() {
    }

    /** The bazaar with one stall open. Only Fantasy mode has a bazaar, as on desktop and mobile. */
    static QuestBazaar bazaar(final String wanted) {
        final QuestController quest = QuestGame.quest();
        if (quest.getMode() != QuestMode.Fantasy) {
            return new QuestBazaar(List.of(), null, List.of());
        }
        final QuestBazaarManager bazaar = quest.getBazaar();
        final List<QuestStallRow> stalls = new ArrayList<>();
        for (final String name : bazaar.getStallNames()) {
            final QuestStallDefinition stall = bazaar.getStall(name);
            stalls.add(new QuestStallRow(stall.getName(), stall.getDisplayName(), stall.getFluff(), stall.getIcon().name()));
        }
        final String open = stalls.stream().anyMatch(s -> s.name().equals(wanted)) ? wanted : stalls.isEmpty() ? null : stalls.get(0).name();
        final List<QuestItemRow> items = new ArrayList<>();
        if (open != null) {
            final QuestAssets assets = quest.getAssets();
            for (final IQuestBazaarItem item : bazaar.getItems(quest, open)) {
                items.add(itemRow(item, assets));
            }
        }
        return new QuestBazaar(stalls, open, items);
    }

    private static QuestItemRow itemRow(final IQuestBazaarItem item, final QuestAssets assets) {
        String icon = null;
        String card = null;
        if (item.getIcon(assets) instanceof WebGuiBase.WebSkinImage image) {
            if (image.prop() != null) {
                icon = image.prop().name();
            } else if (image.path() != null) {
                // A pet's picture is its token's picture. The image route serves it by the token's key
                final String file = new File(image.path()).getName();
                card = "t:" + (file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file);
            }
        }
        // The description is written for desktop's label, with line breaks as markup
        final String description = item.getPurchaseDescription(assets).replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").trim();
        if (item instanceof QuestPetController pet) {
            // A pet's purchase description repeats its stats. The row sends them as now and next instead
            return new QuestItemRow(item.getPurchaseName(), pet.getDescription(), icon, card, item.getBuyingPrice(assets), assets.getPetLevel(pet.getSaveFileKey()),
                    pet.getMaxLevel(), pet.getStats(assets), pet.getUpgradedStats(assets));
        }
        final QuestItemBasic basic = (QuestItemBasic) item;
        return new QuestItemRow(item.getPurchaseName(), description, icon, card, item.getBuyingPrice(assets), assets.getItemLevel(basic.getItemType()),
                basic.getMaxLevel(), null, null);
    }

    /** Buys an item from a stall. It refuses first when the credits are short, because Quest's own purchase then does nothing. */
    static String buy(final String stall, final String name) {
        final QuestController quest = QuestGame.quest();
        if (quest.getMode() != QuestMode.Fantasy || stall == null || !quest.getBazaar().getStallNames().contains(stall)) {
            return null;
        }
        for (final IQuestBazaarItem item : quest.getBazaar().getItems(quest, stall)) {
            if (item.getPurchaseName().equals(name)) {
                final int price = item.getBuyingPrice(quest.getAssets());
                if (price < 0) {
                    return null;
                }
                final long short_ = price - quest.getAssets().getCredits();
                if (short_ > 0) {
                    return Localizer.getInstance().getMessage("lblWebQuestCreditsShort", short_);
                }
                QuestUtil.buyQuestItem(item);
                return null;
            }
        }
        return null;
    }

    /** What a card sells for, computed the same way as the shop's own sale. */
    private static int salePrice(final PaperCard card, final double multiplier) {
        return Math.max(Math.min((int) (multiplier * QuestSpellShop.getCardValue(card)), QuestGame.quest().getCards().getSellPriceLimit()), 1);
    }

    /** How many of the quest's decks hold a card, in either section. */
    private static Map<PaperCard, Integer> decksUsing() {
        final Map<PaperCard, Integer> using = new HashMap<>();
        for (final Deck deck : QuestGame.quest().getMyDecks()) {
            final Set<PaperCard> cards = new HashSet<>();
            deck.getMain().forEach(e -> cards.add(e.getKey()));
            if (deck.has(DeckSection.Sideboard)) {
                deck.get(DeckSection.Sideboard).forEach(e -> cards.add(e.getKey()));
            }
            cards.forEach(c -> using.merge(c, 1, Integer::sum));
        }
        return using;
    }

    /** A card's key in a trade: its printing, and whether it is foil. The image key does not show foil, but the price depends on it. */
    private static String key(final PaperCard card) {
        return card.getImageKey(false) + (card.isFoil() ? "|foil" : "");
    }

    private static int copies(final ItemPool<? extends InventoryItem> pool) {
        int n = 0;
        for (final Map.Entry<? extends InventoryItem, Integer> e : pool) {
            if (e.getKey() instanceof PaperCard) {
                n += e.getValue();
            }
        }
        return n;
    }

    private static List<PaperCard> cardsIn(final ItemPool<? extends InventoryItem> pool) {
        final List<PaperCard> cards = new ArrayList<>();
        pool.forEach(e -> {
            if (e.getKey() instanceof PaperCard card) {
                cards.add(card);
            }
        });
        return cards;
    }

    /** A page of cards. A shop card has its price, stock and copies owned. One of the player's cards has its sale price, copies and decks. */
    static CataloguePage cards(final CatalogueQuery q) {
        final boolean shop = "shop".equals(q.source());
        final ItemPool<InventoryItem> stock = QuestGame.quest().getCards().getShopList();
        final ItemPool<PaperCard> owned = QuestGame.quest().getCards().getCardpool();
        // Without this the shop's statics are unset, and every card would sell for 1 credit
        final double multiplier = QuestSpellShop.updateMultiplier();
        final Map<PaperCard, Integer> using = shop ? Map.of() : decksUsing();
        final CataloguePage page = CardCatalog.of(shop ? cardsIn(stock) : cardsIn(owned)).query(q.request(), new CardCatalog.Query(q.text(),
                q.colours(), q.type(), q.filters(), q.sort(), q.offset(), true), c -> null, null, name -> 0, c -> shop
                ? new CardCatalog.Extra(null, QuestSpellShop.getCardValue(c), stock.count(c), owned.count(c), null, key(c))
                : new CardCatalog.Extra(QuestGame.quest().getCards().isNew(c) ? Boolean.TRUE : null, salePrice(c, multiplier), owned.count(c), null,
                        !using.containsKey(c) ? null : using.get(c) == 1 ? Localizer.getInstance().getMessage("lblWebQuestInOneDeck")
                        : Localizer.getInstance().getMessage("lblWebQuestInDecks", using.get(c)), key(c)));
        return page.from(q.source());
    }

    /** The shop's two lists, what selling pays, the products for sale, and the copies Sell All Extras would sell. */
    static Trading trading() {
        final Localizer text = Localizer.getInstance();
        final ItemPool<InventoryItem> stock = QuestGame.quest().getCards().getShopList();
        final ItemPool<PaperCard> owned = QuestGame.quest().getCards().getCardpool();
        final double multiplier = QuestSpellShop.updateMultiplier();
        final int limit = QuestGame.quest().getCards().getSellPriceLimit();
        // Mobile's text, joined into one line
        final String note = (text.getMessage("lblSellCardsAt") + " " + new DecimalFormat("#.##").format(multiplier * 100) + text.getMessage("lblTheirValue")
                + (limit < Integer.MAX_VALUE ? String.format(text.getMessage("lblMaximumSellingCredits"), limit) : "")).replace('\n', ' ').trim();
        final List<Product> products = new ArrayList<>();
        stock.forEach(e -> {
            if (!(e.getKey() instanceof PaperCard)) {
                products.add(new Product("p:" + e.getKey().getName(), e.getKey().getItemType(), e.getKey().getName(), e.getKey().getImageKey(false),
                        QuestSpellShop.getCardValue(e.getKey())));
            }
        });
        final ItemPool<InventoryItem> ownedItems = new ItemPool<>(InventoryItem.class);
        ownedItems.addAllOfType(owned);
        final ItemPool<InventoryItem> extras = QuestSpellShop.extras(ownedItems);
        final List<CatalogueRow> extraRows = CardCatalog.rows(cardsIn(extras),
                c -> new CardCatalog.Extra(null, salePrice(c, multiplier), extras.count(c), null, null, key(c)));
        // Count copies, not cards, so a trade of part of a stack changes the count. A changed count tells the page its lists changed
        return new Trading(List.of(new TradeList("shop", copies(stock)), new TradeList("inventory", copies(owned))), note, null,
                products, extraRows);
    }

    /** Buys picks from the shop, or sells picks from the player's cards, each limited to what its list holds. Returns why a purchase cannot be made, or null. */
    static String trade(final String source, final List<TradePick> picks) {
        final boolean buying = "shop".equals(source);
        final ItemPool<InventoryItem> stock = QuestGame.quest().getCards().getShopList();
        final ItemPool<InventoryItem> chosen = new ItemPool<>(InventoryItem.class);
        for (final TradePick pick : picks == null ? List.<TradePick>of() : picks) {
            final InventoryItem item = buying ? find(stock, pick.key()) : find(QuestGame.quest().getCards().getCardpool(), pick.key());
            final int held = item == null ? 0 : buying ? stock.count(item) : QuestGame.quest().getCards().getCardpool().count((PaperCard) item);
            if (held > 0 && pick.count() > 0) {
                chosen.add(item, Math.min(pick.count(), held));
            }
        }
        if (chosen.isEmpty()) {
            return null;
        }
        if (buying) {
            final long short_ = QuestSpellShop.getTotalBuyCost(chosen) - QuestGame.quest().getAssets().getCredits();
            if (short_ > 0) {
                // Quest's own sentence
                return "You need " + short_ + " more credits to purchase the following "
                        + SItemManagerUtil.getItemDisplayString(chosen.toFlatList(), 1, true).toLowerCase() + ".\n" + SItemManagerUtil.buildDisplayList(chosen);
            }
            QuestSpellShop.buyItems(chosen);
            // On desktop and mobile, the shop's list widget removes bought items from the stock
            stock.removeAll(chosen);
        } else {
            QuestSpellShop.updateMultiplier();
            QuestSpellShop.sellItems(chosen);
            // On desktop and mobile, the shop's list widget puts sold items back on sale
            stock.addAll(chosen);
        }
        QuestGame.quest().save();
        return null;
    }

    /** Finds a card in a list by its key, or a product by "p:" plus its name. */
    private static InventoryItem find(final ItemPool<? extends InventoryItem> pool, final String key) {
        if (key == null) {
            return null;
        }
        for (final Map.Entry<? extends InventoryItem, Integer> e : pool) {
            final InventoryItem item = e.getKey();
            if (item instanceof PaperCard card ? key(card).equals(key) : ("p:" + item.getName()).equals(key)) {
                return item;
            }
        }
        return null;
    }
}
