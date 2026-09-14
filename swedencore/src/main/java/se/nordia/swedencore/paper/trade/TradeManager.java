package se.nordia.swedencore.paper.trade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.core.NordiaCore;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.inventory.ItemTransfer;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.text.Messages;
import se.nordia.swedencore.trade.TradeState;
import se.nordia.swedencore.trade.TradeState.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Secure direct trading between two nearby players.
 *
 * <p>Security model:
 * <ul>
 *   <li>Every click in a trade window is cancelled and re-implemented server-side (click a stack to offer or withdraw
 *       it). The cursor never holds trade items; drags, number keys and collect-to-cursor cannot move anything.</li>
 *   <li>Offered items leave the player's inventory and live only in the trade until it completes or is cancelled.</li>
 *   <li>Money and the trade record commit atomically with a per-trade token before any item changes hands.</li>
 *   <li>Closing the window, quitting, moving apart or timing out cancels and returns items (offline ⇒ stash).</li>
 * </ul>
 * Main-thread confined.
 */
public final class TradeManager implements Listener {

    private static final int MAX_OFFER = 16;
    private static final long CONFIRM_COOLDOWN_MILLIS = 3_000;
    private static final long REQUEST_TTL_MILLIS = 60_000;
    private static final long SESSION_TTL_MILLIS = 5 * 60_000;
    private static final double MAX_REQUEST_DISTANCE = 10;
    private static final double MAX_SESSION_DISTANCE = 16;

    private static final int[] OWN_SLOTS = slots(0);
    private static final int[] PARTNER_SLOTS = slots(5);
    private static final Map<Integer, Long> MONEY_BUTTONS = Map.of(
            36, -1_000_000L, 37, -100_000L, 38, 100_000L, 39, 1_000_000L, 45, -10_000L, 46, 10_000L);
    private static final int OWN_MONEY = 47;
    private static final int CONFIRM = 48;
    private static final int CANCEL = 49;
    private static final int PARTNER_STATUS = 50;
    private static final int PARTNER_MONEY = 51;

    private static int[] slots(int firstColumn) {
        int[] result = new int[MAX_OFFER];
        int i = 0;
        for (int row = 0; row < 4; row++) {
            for (int col = firstColumn; col < firstColumn + 4; col++) {
                result[i++] = row * 9 + col;
            }
        }
        return result;
    }

    private final class Session {
        final TradeState<ItemStack> state;
        final UUID token = UUID.randomUUID();
        final long startedAt = System.currentTimeMillis();
        final Map<Side, Inventory> inventories = new HashMap<>();
        boolean closing;

        Session(TradeState<ItemStack> state) {
            this.state = state;
        }
    }

    private record Holder(Session session, Side side) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return session.inventories.get(side);
        }
    }

    private record RequestKey(UUID from, UUID to) {
    }

    private final NordiaCore core;
    private final Messages messages;
    private final Tasks tasks;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<RequestKey, Long> requests = new HashMap<>();

    public TradeManager(NordiaCore core, Messages messages, Tasks tasks) {
        this.core = core;
        this.messages = messages;
        this.tasks = tasks;
    }

    // ------------------------------------------------------------------ requests

    public void request(Player from, Player to) {
        if (from.equals(to)) {
            messages.send(from, "error.trade.self");
            return;
        }
        if (sessions.containsKey(from.getUniqueId()) || sessions.containsKey(to.getUniqueId())) {
            messages.send(from, "trade.busy");
            return;
        }
        if (!near(from, to, MAX_REQUEST_DISTANCE)) {
            messages.send(from, "trade.too_far");
            return;
        }
        requests.put(new RequestKey(from.getUniqueId(), to.getUniqueId()), System.currentTimeMillis() + REQUEST_TTL_MILLIS);
        messages.send(from, "trade.request_sent", "player", to.getName());
        to.sendMessage(messages.render(to, "trade.request_received", "player", from.getName())
                .clickEvent(ClickEvent.runCommand("/trade accept " + from.getName())));
    }

    public void accept(Player to, Player from) {
        Long expires = requests.remove(new RequestKey(from.getUniqueId(), to.getUniqueId()));
        if (expires == null || expires < System.currentTimeMillis()) {
            messages.send(to, "trade.no_request", "player", from.getName());
            return;
        }
        if (sessions.containsKey(from.getUniqueId()) || sessions.containsKey(to.getUniqueId())) {
            messages.send(to, "trade.busy");
            return;
        }
        if (!near(from, to, MAX_REQUEST_DISTANCE)) {
            messages.send(to, "trade.too_far");
            return;
        }
        Session session = new Session(new TradeState<>(from.getUniqueId(), to.getUniqueId(), MAX_OFFER,
                CONFIRM_COOLDOWN_MILLIS, core.config().economy().maxTransferAmount(), System.currentTimeMillis()));
        sessions.put(from.getUniqueId(), session);
        sessions.put(to.getUniqueId(), session);
        for (Side side : Side.values()) {
            Player player = Bukkit.getPlayer(session.state.player(side));
            Player partner = Bukkit.getPlayer(session.state.player(side.other()));
            Inventory inventory = Bukkit.createInventory(new Holder(session, side), 54,
                    messages.render(player, "trade.title", "player", partner.getName()));
            session.inventories.put(side, inventory);
        }
        render(session);
        for (Side side : Side.values()) {
            Bukkit.getPlayer(session.state.player(side)).openInventory(session.inventories.get(side));
        }
    }

    public void deny(Player to, Player from) {
        if (requests.remove(new RequestKey(from.getUniqueId(), to.getUniqueId())) != null) {
            messages.send(from, "trade.denied", "player", to.getName());
            messages.send(to, "trade.you_denied", "player", from.getName());
        }
    }

    // ------------------------------------------------------------------ window events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        Session session = holder.session();
        if (!(event.getWhoClicked() instanceof Player player) || session.closing || event.getClickedInventory() == null) {
            return;
        }
        Side side = holder.side();
        long now = System.currentTimeMillis();
        TradeState<ItemStack> state = session.state;
        if (event.getClickedInventory().equals(event.getView().getBottomInventory())) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) {
                return;
            }
            ItemStack offered = clicked.clone();
            if (state.addItem(side, offered, now)) {
                event.getClickedInventory().setItem(event.getSlot(), null);
                render(session);
            }
            return;
        }
        int slot = event.getRawSlot();
        int ownIndex = indexOf(OWN_SLOTS, slot);
        if (ownIndex >= 0) {
            if (ownIndex < state.items(side).size()) {
                if (player.getInventory().firstEmpty() < 0) {
                    player.sendActionBar(messages.render(player, "trade.inventory_full"));
                    return;
                }
                ItemStack removed = state.removeItem(side, ownIndex, now);
                if (removed != null) {
                    player.getInventory().addItem(removed);
                    render(session);
                }
            }
            return;
        }
        Long delta = MONEY_BUTTONS.get(slot);
        if (delta != null) {
            long next = Math.max(0, state.money(side).ore() + delta);
            if (state.setMoney(side, Money.ofOre(next), now)) {
                render(session);
            }
            return;
        }
        if (slot == CONFIRM) {
            switch (state.confirm(side, now)) {
                case TOO_SOON -> player.sendActionBar(messages.render(player, "trade.wait"));
                case CONFIRMED -> render(session);
                case READY -> complete(session);
                case NOT_OPEN -> {
                }
            }
        } else if (slot == CANCEL) {
            cancel(session, "trade.cancelled");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder && !holder.session().closing
                && holder.session().state.status() == TradeState.Status.OPEN) {
            cancel(holder.session(), "trade.cancelled");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.state.status() == TradeState.Status.OPEN) {
            cancel(session, "trade.cancelled");
        }
    }

    /** Called every second: distance, timeouts and expired requests. */
    public void tick() {
        long now = System.currentTimeMillis();
        requests.values().removeIf(expires -> expires < now);
        for (Session session : List.copyOf(new java.util.HashSet<>(sessions.values()))) {
            if (session.state.status() != TradeState.Status.OPEN) {
                continue;
            }
            Player a = Bukkit.getPlayer(session.state.player(Side.A));
            Player b = Bukkit.getPlayer(session.state.player(Side.B));
            if (a == null || b == null || !near(a, b, MAX_SESSION_DISTANCE) || a.isDead() || b.isDead()) {
                cancel(session, "trade.cancelled_distance");
            } else if (now - session.startedAt > SESSION_TTL_MILLIS) {
                cancel(session, "trade.cancelled_timeout");
            }
        }
    }

    // ------------------------------------------------------------------ completion

    private void complete(Session session) {
        TradeState<ItemStack> state = session.state;
        render(session);
        // Each side needs room for what they receive (their own offered items have already left their inventory).
        for (Side side : Side.values()) {
            Player player = Bukkit.getPlayer(state.player(side));
            if (player == null || ItemTransfer.freeStorageSlots(player) < state.items(side.other()).size()) {
                state.reopen(System.currentTimeMillis());
                render(session);
                broadcast(session, "trade.needs_space", "player", player == null ? "?" : player.getName());
                return;
            }
        }
        UUID a = state.player(Side.A);
        UUID b = state.player(Side.B);
        Money aToB = state.money(Side.A);
        Money bToA = state.money(Side.B);
        String itemsA = summary(state.items(Side.A));
        String itemsB = summary(state.items(Side.B));
        tasks.async(() -> core.trades().complete(session.token, a, b, aToB, bToA, itemsA, itemsB)).whenComplete((id, error) -> {
            Throwable cause = error == null ? null : Tasks.unwrap(error);
            boolean recorded = error == null
                    || (cause instanceof DomainException d && d.code().equals("trade.already_completed"));
            if (!recorded && !(cause instanceof DomainException)) {
                try {
                    recorded = core.trades().tradeRecorded(session.token);
                } catch (RuntimeException checkFailed) {
                    core.logger().log(Level.SEVERE, "[AUDIT] Trade outcome unknown, token " + session.token, checkFailed);
                }
            }
            final boolean done = recorded;
            tasks.sync(() -> {
                if (done) {
                    deliver(session);
                    return;
                }
                state.reopen(System.currentTimeMillis());
                render(session);
                if (cause instanceof DomainException domain) {
                    for (Side side : Side.values()) {
                        Player p = Bukkit.getPlayer(state.player(side));
                        if (p != null) {
                            messages.sendError(p, domain);
                        }
                    }
                } else {
                    core.logger().log(Level.SEVERE, "Trade completion failed", cause);
                    broadcast(session, "error.internal");
                }
            });
        });
    }

    private void deliver(Session session) {
        TradeState<ItemStack> state = session.state;
        state.markDone();
        session.closing = true;
        for (Side side : Side.values()) {
            UUID receiver = state.player(side);
            List<ItemStack> incoming = new ArrayList<>(state.items(side.other()));
            give(receiver, incoming, "TRADE", session.token);
        }
        end(session);
        for (Side side : Side.values()) {
            Player p = Bukkit.getPlayer(state.player(side));
            if (p != null) {
                messages.send(p, "trade.completed", "received", summary(state.items(side.other())),
                        "money", state.money(side.other()));
            }
        }
    }

    private void cancel(Session session, String reasonKey) {
        if (session.closing) {
            return;
        }
        if (session.state.status() == TradeState.Status.COMPLETING) {
            return; // never cancel while money may be moving; completion decides
        }
        session.closing = true;
        Map<Side, List<ItemStack>> refunds = session.state.cancel();
        for (Side side : Side.values()) {
            give(session.state.player(side), refunds.get(side), "TRADE_CANCEL", session.token);
        }
        broadcast(session, reasonKey);
        end(session);
    }

    private void give(UUID playerId, List<ItemStack> items, String source, UUID token) {
        if (items.isEmpty()) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            ItemTransfer.giveOrDrop(player, items);
            return;
        }
        List<ItemStashService.StashItem> stash = ItemTransfer.toStash(items);
        tasks.async("trade items to stash for " + playerId, () ->
                core.stash().deposit(ItemStashService.Owner.player(playerId), stash, source, token.toString()));
    }

    private void end(Session session) {
        for (Side side : Side.values()) {
            UUID id = session.state.player(side);
            sessions.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.getOpenInventory().getTopInventory().getHolder() instanceof Holder h && h.session() == session) {
                player.closeInventory();
            }
        }
    }

    /** Returns all items of open trades (plugin shutdown). */
    public void cancelAll() {
        for (Session session : List.copyOf(new java.util.HashSet<>(sessions.values()))) {
            if (session.state.status() == TradeState.Status.OPEN) {
                cancel(session, "trade.cancelled");
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private void render(Session session) {
        TradeState<ItemStack> state = session.state;
        for (Side side : Side.values()) {
            Inventory inv = session.inventories.get(side);
            Player viewer = Bukkit.getPlayer(state.player(side));
            SupportedLocale locale = viewer == null ? messages.defaultLocale() : messages.localeOf(viewer);
            inv.clear();
            ItemStack separator = button(Material.GRAY_STAINED_GLASS_PANE, Component.space());
            for (int row = 0; row < 6; row++) {
                inv.setItem(row * 9 + 4, separator);
            }
            for (int i = 40; i <= 44; i++) {
                inv.setItem(i, separator);
            }
            inv.setItem(52, separator);
            inv.setItem(53, separator);
            fill(inv, OWN_SLOTS, state.items(side));
            fill(inv, PARTNER_SLOTS, state.items(side.other()));
            for (Map.Entry<Integer, Long> button : MONEY_BUTTONS.entrySet()) {
                Money amount = Money.ofOre(Math.abs(button.getValue()));
                inv.setItem(button.getKey(), button(button.getValue() < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                        messages.render(locale, button.getValue() < 0 ? "trade.button.money_minus" : "trade.button.money_plus", "amount", amount)));
            }
            inv.setItem(OWN_MONEY, button(Material.GOLD_INGOT, messages.render(locale, "trade.button.own_money", "amount", state.money(side))));
            inv.setItem(PARTNER_MONEY, button(Material.GOLD_INGOT, messages.render(locale, "trade.button.partner_money", "amount", state.money(side.other()))));
            inv.setItem(CONFIRM, button(state.confirmed(side) ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE,
                    messages.render(locale, state.confirmed(side) ? "trade.button.confirmed" : "trade.button.confirm")));
            inv.setItem(CANCEL, button(Material.BARRIER, messages.render(locale, "trade.button.cancel")));
            inv.setItem(PARTNER_STATUS, button(state.confirmed(side.other()) ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                    messages.render(locale, state.confirmed(side.other()) ? "trade.button.partner_ready" : "trade.button.partner_waiting")));
        }
    }

    private static void fill(Inventory inv, int[] slots, List<ItemStack> items) {
        for (int i = 0; i < items.size() && i < slots.length; i++) {
            inv.setItem(slots[i], items.get(i).clone());
        }
    }

    private static ItemStack button(Material material, Component name) {
        ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> meta.displayName(name.decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    private void broadcast(Session session, String key, Object... args) {
        for (Side side : Side.values()) {
            Player p = Bukkit.getPlayer(session.state.player(side));
            if (p != null) {
                messages.send(p, key, args);
            }
        }
    }

    private static String summary(List<ItemStack> items) {
        if (items.isEmpty()) {
            return "-";
        }
        return items.stream().map(i -> i.getAmount() + "x " + i.getType().name()).collect(Collectors.joining(", "));
    }

    private static int indexOf(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private static boolean near(Player a, Player b, double distance) {
        return a.getWorld().equals(b.getWorld()) && a.getLocation().distance(b.getLocation()) <= distance;
    }
}
