package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.session.PlayerSession;
import se.nordia.swedencore.player.NordiaPlayer;
import se.nordia.swedencore.settlements.Settlement;
import se.nordia.swedencore.settlements.SettlementConfig;
import se.nordia.swedencore.settlements.SettlementService;

import java.util.List;
import java.util.UUID;

/** {@code /settlement} (aliases {@code /bosattning}, {@code /by}). */
public final class SettlementCommands {

    private static final long CONFIRM_WINDOW_MILLIS = 30_000;

    private final CommandServices svc;

    public SettlementCommands(CommandServices svc) {
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(node(), "Bosättningar / Settlements", List.of("bosattning", "by"));
    }

    private SettlementService settlements() {
        return svc.core().settlements();
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("settlement")
                .executes(c -> info(c, null))
                .then(Commands.literal("help").executes(c -> {
                    svc.messages().send(c.getSource().getSender(), "settlement.help");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("info")
                        .executes(c -> info(c, null))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(c -> info(c, StringArgumentType.getString(c, "name")))))
                .then(Commands.literal("found")
                        .then(Commands.literal("confirm").executes(this::confirmFound))
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::prepareFound)))
                .then(Commands.literal("invites").executes(this::invites))
                .then(Commands.literal("invite").then(playerArg((c, actor, s, target) -> {
                    settlements().invite(actor, s.id(), target.uuid());
                    return "settlement.invited";
                })))
                .then(Commands.literal("join").then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::join)))
                .then(Commands.literal("leave").executes(this::leave))
                .then(Commands.literal("kick").then(playerArg((c, actor, s, target) -> {
                    settlements().kick(actor, s.id(), target.uuid());
                    return "settlement.kicked";
                })))
                .then(Commands.literal("promote").then(playerArg((c, actor, s, target) -> {
                    settlements().setRole(actor, s.id(), target.uuid(), Settlement.Role.OFFICER);
                    return "settlement.promoted";
                })))
                .then(Commands.literal("demote").then(playerArg((c, actor, s, target) -> {
                    settlements().setRole(actor, s.id(), target.uuid(), Settlement.Role.RESIDENT);
                    return "settlement.demoted";
                })))
                .then(Commands.literal("leader").then(playerArg((c, actor, s, target) -> {
                    settlements().transferLeadership(actor, s.id(), target.uuid());
                    return "settlement.new_leader";
                })))
                .then(Commands.literal("deposit").then(Commands.argument("amount", StringArgumentType.word()).executes(c -> money(c, true))))
                .then(Commands.literal("withdraw").then(Commands.argument("amount", StringArgumentType.word()).executes(c -> money(c, false))))
                .then(Commands.literal("upgrade").executes(this::upgrade))
                .then(Commands.literal("disband")
                        .executes(this::prepareDisband)
                        .then(Commands.literal("confirm").executes(this::confirmDisband)))
                .build();
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface MemberAction {
        String apply(CommandContext<CommandSourceStack> c, UUID actor, Settlement settlement, NordiaPlayer target);
    }

    private record ActionResult(String key, String settlement, String target, UUID targetUuid) {
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> playerArg(MemberAction action) {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(CommandServices.onlinePlayerNames())
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    UUID actor = player.getUniqueId();
                    String targetName = StringArgumentType.getString(c, "player");
                    svc.tasks().run(player, () -> {
                        Settlement settlement = settlements().memberOf(actor)
                                .orElseThrow(() -> new DomainException("settlement.not_member")).settlement();
                        NordiaPlayer target = svc.core().players().requireByName(targetName);
                        String key = action.apply(c, actor, settlement, target);
                        return new ActionResult(key, settlement.name(), target.name(), target.uuid());
                    }, r -> {
                        svc.messages().send(player, r.key(), "player", r.target(), "settlement", r.settlement());
                        Player online = Bukkit.getPlayer(r.targetUuid());
                        if (online != null && !online.equals(player)) {
                            svc.messages().send(online, r.key() + "_notice", "player", player.getName(), "settlement", r.settlement());
                        }
                    });
                    return Command.SINGLE_SUCCESS;
                });
    }

    private Component tierName(SupportedLocale locale, Settlement.Tier tier) {
        return svc.messages().render(locale, "settlement.tier." + tier.name());
    }

    // ------------------------------------------------------------------ commands

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        svc.tasks().run(sender, () -> settlements().allActive(), list -> {
            if (list.isEmpty()) {
                svc.messages().send(sender, "settlement.list.empty");
                return;
            }
            SupportedLocale locale = svc.messages().localeOf(sender);
            svc.messages().send(sender, "settlement.list.header");
            for (Settlement s : list) {
                svc.messages().send(sender, "settlement.list.entry", "name", s.name(), "tier", tierName(locale, s.tier()),
                        "leader", s.leaderName(), "x", s.centerX(), "z", s.centerZ());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> c, String name) {
        CommandSender sender = c.getSource().getSender();
        UUID actor = sender instanceof Player p ? p.getUniqueId() : null;
        if (actor == null && name == null) {
            svc.messages().send(sender, "error.players_only");
            return Command.SINGLE_SUCCESS;
        }
        svc.tasks().run(sender, () -> {
            long id = name != null ? settlements().requireActiveByName(name).id()
                    : settlements().memberOf(actor).orElseThrow(() -> new DomainException("settlement.not_member")).settlement().id();
            return settlements().summary(id);
        }, summary -> {
            SupportedLocale locale = svc.messages().localeOf(sender);
            Settlement s = summary.settlement();
            svc.messages().send(sender, "settlement.info.header", "name", s.name(), "tier", tierName(locale, s.tier()));
            svc.messages().send(sender, "settlement.info.details", "leader", s.leaderName(), "members", summary.members().size(),
                    "treasury", summary.treasury(), "founded", s.foundedAt(), "radius", s.radius(), "x", s.centerX(), "z", s.centerZ());
            String names = String.join(", ", summary.members().stream().map(SettlementService.Member::name).toList());
            svc.messages().send(sender, "settlement.info.members", "names", names);
            SettlementService.Progress p = summary.next();
            if (p == null) {
                svc.messages().send(sender, "settlement.info.max_tier");
                return;
            }
            SettlementConfig.Requirements r = p.requirements();
            svc.messages().send(sender, "settlement.info.next", "tier", tierName(locale, p.target()), "cost", r.cost());
            requirement(sender, locale, p.membersMet(), "settlement.req.members", "current", p.members(), "required", r.minMembers());
            requirement(sender, locale, p.treasuryMet(), "settlement.req.treasury", "current", p.treasury(),
                    "required", r.minTreasury().isLessThan(r.cost()) ? r.cost() : r.minTreasury());
            requirement(sender, locale, p.ageMet(), "settlement.req.age", "current", p.ageDays(), "required", r.minAgeDays());
            requirement(sender, locale, p.reputationMet(), "settlement.req.reputation", "current", p.leaderReputation(),
                    "required", r.minLeaderReputation());
        });
        return Command.SINGLE_SUCCESS;
    }

    private void requirement(CommandSender sender, SupportedLocale locale, boolean met, String key, Object... args) {
        Object[] withMark = new Object[args.length + 2];
        withMark[0] = "mark";
        withMark[1] = svc.messages().render(locale, met ? "settlement.req.met" : "settlement.req.unmet");
        System.arraycopy(args, 0, withMark, 2, args.length);
        svc.messages().send(sender, key, withMark);
    }

    private int prepareFound(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        String name = StringArgumentType.getString(c, "name").trim();
        svc.sessions().get(player.getUniqueId()).ifPresent(s -> s.pendingConfirmation(new PlayerSession.PendingConfirmation(
                "settlement-found:" + name, 0, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS)));
        svc.messages().send(player, "settlement.found.confirm", "name", name,
                "cost", settlements().config().requirements(Settlement.Tier.OUTPOST).cost());
        return Command.SINGLE_SUCCESS;
    }

    private int confirmFound(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        PlayerSession session = svc.sessions().get(uuid).orElse(null);
        PlayerSession.PendingConfirmation pending = session == null ? null : session.pendingConfirmation();
        if (pending == null || !pending.action().startsWith("settlement-found:") || System.currentTimeMillis() > pending.expiresAtMillis()) {
            svc.messages().send(player, "company.dissolve.nothing_to_confirm");
            return Command.SINGLE_SUCCESS;
        }
        session.pendingConfirmation(null);
        String name = pending.action().substring("settlement-found:".length());
        Location loc = player.getLocation();
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        svc.tasks().run(player, () -> settlements().found(uuid, name, world, x, z), s -> {
            svc.core().logger().info("[SETTLEMENT] " + player.getName() + " founded " + s.name() + " at " + x + "," + z);
            svc.messages().send(player, "settlement.founded", "name", s.name(), "radius", s.radius());
        });
        return Command.SINGLE_SUCCESS;
    }

    private int invites(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> settlements().pendingInvitesFor(uuid), names -> {
            if (names.isEmpty()) {
                svc.messages().send(player, "settlement.invites.empty");
            } else {
                svc.messages().send(player, "settlement.invites.list", "names", String.join(", ", names));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int join(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        String name = StringArgumentType.getString(c, "name");
        svc.tasks().run(player, () -> settlements().join(uuid, name),
                s -> svc.messages().send(player, "settlement.joined", "name", s.name()));
        return Command.SINGLE_SUCCESS;
    }

    private int leave(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            settlements().leave(uuid);
            return true;
        }, ok -> svc.messages().send(player, "settlement.left"));
        return Command.SINGLE_SUCCESS;
    }

    private int money(CommandContext<CommandSourceStack> c, boolean deposit) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        String input = StringArgumentType.getString(c, "amount");
        svc.tasks().run(player, () -> {
            Money amount = Money.parsePositive(input);
            long id = settlements().memberOf(uuid).orElseThrow(() -> new DomainException("settlement.not_member")).settlement().id();
            if (deposit) {
                settlements().deposit(uuid, id, amount);
            } else {
                settlements().withdraw(uuid, id, amount);
            }
            return settlements().summary(id);
        }, s -> svc.messages().send(player, deposit ? "settlement.deposited" : "settlement.withdrew",
                "amount", Money.parsePositive(input), "treasury", s.treasury()));
        return Command.SINGLE_SUCCESS;
    }

    private int upgrade(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            long id = settlements().memberOf(uuid).orElseThrow(() -> new DomainException("settlement.not_member")).settlement().id();
            return settlements().upgrade(uuid, id);
        }, s -> {
            svc.core().logger().info("[SETTLEMENT] " + s.name() + " reached " + s.tier());
            for (Player online : Bukkit.getOnlinePlayers()) {
                svc.messages().send(online, "settlement.upgraded_broadcast", "name", s.name(),
                        "tier", tierName(svc.messages().localeOf(online), s.tier()));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int prepareDisband(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> settlements().memberOf(uuid).orElseThrow(() -> new DomainException("settlement.not_member")), m -> {
            svc.sessions().get(uuid).ifPresent(s -> s.pendingConfirmation(new PlayerSession.PendingConfirmation(
                    "settlement-disband", m.settlement().id(), System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS)));
            svc.messages().send(player, "settlement.disband.confirm", "name", m.settlement().name());
        });
        return Command.SINGLE_SUCCESS;
    }

    private int confirmDisband(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        PlayerSession session = svc.sessions().get(uuid).orElse(null);
        PlayerSession.PendingConfirmation pending = session == null ? null : session.pendingConfirmation();
        if (pending == null || !pending.matches("settlement-disband", System.currentTimeMillis())) {
            svc.messages().send(player, "company.dissolve.nothing_to_confirm");
            return Command.SINGLE_SUCCESS;
        }
        session.pendingConfirmation(null);
        svc.tasks().run(player, () -> settlements().disband(uuid, pending.targetId()),
                payout -> svc.messages().send(player, "settlement.disbanded", "payout", payout));
        return Command.SINGLE_SUCCESS;
    }
}
