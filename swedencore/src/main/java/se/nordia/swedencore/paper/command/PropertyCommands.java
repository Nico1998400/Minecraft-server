package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.swedencore.cities.City;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.Permissions;
import se.nordia.swedencore.paper.properties.ProtectionIndex;
import se.nordia.swedencore.paper.session.PlayerSession;
import se.nordia.swedencore.player.NordiaPlayer;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.PropertyService;
import se.nordia.swedencore.properties.Region;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** {@code /property} (alias {@code /fastighet}) and {@code /city} (alias {@code /stad}). */
public final class PropertyCommands {

    private static final int PAGE_SIZE = 8;
    private static final long CONFIRM_WINDOW_MILLIS = 30_000;

    private final CommandServices svc;
    private final ProtectionIndex index;

    public PropertyCommands(CommandServices svc, ProtectionIndex index) {
        this.svc = svc;
        this.index = index;
    }

    public void register(Commands commands) {
        commands.register(property(), "Fastigheter / Properties", List.of("fastighet", "prop"));
        commands.register(city(), "Städer / Cities", List.of("stad"));
    }

    private PropertyService properties() {
        return svc.core().properties();
    }

    private LiteralCommandNode<CommandSourceStack> property() {
        return Commands.literal("property")
                .executes(this::here)
                .then(Commands.literal("help").executes(c -> {
                    svc.messages().send(c.getSource().getSender(), "property.help");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("info").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(this::info)))
                .then(Commands.literal("market")
                        .executes(c -> market(c.getSource().getSender(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 1000))
                                .executes(c -> market(c.getSource().getSender(), IntegerArgumentType.getInteger(c, "page")))))
                .then(Commands.literal("list").executes(this::mine))
                .then(Commands.literal("buy")
                        .then(Commands.literal("confirm").executes(this::confirmBuy))
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(c -> prepareBuy(c, false))
                                .then(Commands.literal("company").executes(c -> prepareBuy(c, true)))))
                .then(Commands.literal("sell").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .then(Commands.argument("price", StringArgumentType.word()).executes(this::sell))))
                .then(Commands.literal("unlist").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(this::unlist)))
                .then(Commands.literal("trust").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(CommandServices.onlinePlayerNames())
                                .executes(c -> trust(c, true)))))
                .then(Commands.literal("untrust").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(CommandServices.onlinePlayerNames())
                                .executes(c -> trust(c, false)))))
                .then(Commands.literal("admin")
                        .requires(CommandServices.permission(Permissions.ADMIN_PROPERTY))
                        .then(Commands.literal("pos1").executes(c -> selection(c, true)))
                        .then(Commands.literal("pos2").executes(c -> selection(c, false)))
                        .then(Commands.literal("create")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (Property.Type t : Property.Type.values()) {
                                                String name = t.name().toLowerCase(Locale.ROOT);
                                                if (name.startsWith(b.getRemainingLowerCase())) {
                                                    b.suggest(name);
                                                }
                                            }
                                            return b.buildFuture();
                                        })
                                        .then(Commands.argument("price", StringArgumentType.word())
                                                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::adminCreate)))))
                        .then(Commands.literal("delete").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(this::adminDelete))))
                .build();
    }

    // ------------------------------------------------------------------ viewing

    private int here(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Location loc = player.getLocation();
        var access = index.propertyAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (access.isPresent()) {
            showProperty(player, access.get().property());
        } else {
            index.cityAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ()).ifPresentOrElse(
                    city -> svc.messages().send(player, "property.here.city_land", "city", city.name()),
                    () -> svc.messages().send(player, "property.here.wilderness"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(sender, () -> properties().find(id).orElseThrow(() -> new DomainException("property.not_found")),
                p -> showProperty(sender, p));
        return Command.SINGLE_SUCCESS;
    }

    private void showProperty(CommandSender viewer, Property p) {
        SupportedLocale locale = svc.messages().localeOf(viewer);
        Region r = p.region();
        svc.messages().send(viewer, "property.info.header", "id", p.id(), "name", p.name(),
                "type", type(locale, p.type()), "status", svc.messages().render(locale, "property.status." + p.status().name()));
        svc.messages().send(viewer, "property.info.details",
                "city", p.cityName() == null ? svc.messages().render(locale, "property.no_city") : Component.text(p.cityName()),
                "owner", p.ownerName() == null ? "-" : p.ownerName(), "value", p.marketValue(),
                "size", (r.maxX() - r.minX() + 1) + "×" + (r.maxZ() - r.minZ() + 1) + "×" + (r.maxY() - r.minY() + 1));
        if (p.status() != Property.Status.OWNED) {
            viewer.sendMessage(svc.messages().render(locale, "property.info.for_sale", "id", p.id(), "price", p.price())
                    .clickEvent(ClickEvent.suggestCommand("/property buy " + p.id())));
        }
    }

    private int market(CommandSender sender, int page) {
        svc.tasks().run(sender, () -> properties().market(PAGE_SIZE, (page - 1) * PAGE_SIZE), list -> {
            if (list.isEmpty()) {
                svc.messages().send(sender, "property.market.empty");
                return;
            }
            SupportedLocale locale = svc.messages().localeOf(sender);
            svc.messages().send(sender, "property.market.header", "page", page);
            for (Property p : list) {
                sender.sendMessage(svc.messages().render(locale, "property.market.entry", "id", p.id(), "name", p.name(),
                                "type", type(locale, p.type()), "city", p.cityName() == null ? "-" : p.cityName(), "price", p.price())
                        .clickEvent(ClickEvent.runCommand("/property info " + p.id())));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int mine(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> properties().ownedBy(uuid), list -> {
            if (list.isEmpty()) {
                svc.messages().send(player, "property.list.empty");
                return;
            }
            SupportedLocale locale = svc.messages().localeOf(player);
            svc.messages().send(player, "property.list.header");
            for (Property p : list) {
                svc.messages().send(player, "property.list.entry", "id", p.id(), "name", p.name(), "type", type(locale, p.type()),
                        "owner", p.ownerName(), "status", svc.messages().render(locale, "property.status." + p.status().name()));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ market actions

    /** Step 1: show the price and remember it; step 2 ({@code /property buy confirm}) buys at exactly that price. */
    private int prepareBuy(CommandContext<CommandSourceStack> c, boolean company) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(player, () -> properties().find(id).orElseThrow(() -> new DomainException("property.not_found")), p -> {
            if (p.status() == Property.Status.OWNED) {
                svc.messages().sendError(player, new DomainException("property.not_for_sale"));
                return;
            }
            svc.sessions().get(uuid).ifPresent(s -> s.pendingConfirmation(new PlayerSession.PendingConfirmation(
                    "property-buy:" + p.price().ore() + ":" + company, p.id(), System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS)));
            svc.messages().send(player, company ? "property.buy.confirm_company" : "property.buy.confirm",
                    "name", p.name(), "price", p.price());
        });
        return Command.SINGLE_SUCCESS;
    }

    private int confirmBuy(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        PlayerSession session = svc.sessions().get(uuid).orElse(null);
        PlayerSession.PendingConfirmation pending = session == null ? null : session.pendingConfirmation();
        if (pending == null || !pending.action().startsWith("property-buy:") || System.currentTimeMillis() > pending.expiresAtMillis()) {
            svc.messages().send(player, "company.dissolve.nothing_to_confirm");
            return Command.SINGLE_SUCCESS;
        }
        session.pendingConfirmation(null);
        String[] parts = pending.action().split(":");
        Money expected = Money.ofOre(Long.parseLong(parts[1]));
        boolean company = Boolean.parseBoolean(parts[2]);
        Long selected = session.selectedCompanyId();
        svc.tasks().run(player, () -> {
            Long companyId = null;
            if (company) {
                companyId = selected != null ? selected : svc.core().companies().resolveForActor(uuid, null, CompanyRole.OWNER).id();
            }
            return properties().buy(uuid, pending.targetId(), companyId, expected);
        }, p -> svc.messages().send(player, "property.bought", "name", p.name(), "price", p.price(), "owner", p.ownerName()));
        return Command.SINGLE_SUCCESS;
    }

    private int sell(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        String priceInput = StringArgumentType.getString(c, "price");
        svc.tasks().run(player, () -> properties().listForSale(uuid, id, Money.parsePositive(priceInput)),
                p -> svc.messages().send(player, "property.listed", "name", p.name(), "price", p.price()));
        return Command.SINGLE_SUCCESS;
    }

    private int unlist(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(player, () -> properties().unlist(uuid, id), p -> svc.messages().send(player, "property.unlisted", "name", p.name()));
        return Command.SINGLE_SUCCESS;
    }

    private int trust(CommandContext<CommandSourceStack> c, boolean add) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        String targetName = StringArgumentType.getString(c, "player");
        svc.tasks().run(player, () -> {
            NordiaPlayer target = svc.core().players().requireByName(targetName);
            if (add) {
                properties().trust(uuid, id, target.uuid());
            } else {
                properties().untrust(uuid, id, target.uuid());
            }
            return target.name();
        }, name -> svc.messages().send(player, add ? "property.trusted" : "property.untrusted", "player", name, "id", id));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ admin

    private int selection(CommandContext<CommandSourceStack> c, boolean first) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Location loc = player.getLocation();
        PlayerSession.BlockPos pos = new PlayerSession.BlockPos(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        svc.sessions().get(player.getUniqueId()).ifPresent(s -> {
            if (first) {
                s.selection1(pos);
            } else {
                s.selection2(pos);
            }
        });
        svc.messages().send(player, "property.admin.selected", "point", first ? 1 : 2, "x", pos.x(), "y", pos.y(), "z", pos.z());
        return Command.SINGLE_SUCCESS;
    }

    private int adminCreate(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        PlayerSession session = svc.sessions().get(player.getUniqueId()).orElse(null);
        PlayerSession.BlockPos a = session == null ? null : session.selection1();
        PlayerSession.BlockPos b = session == null ? null : session.selection2();
        if (a == null || b == null || !a.world().equals(b.world())) {
            svc.messages().send(player, "property.admin.need_selection");
            return Command.SINGLE_SUCCESS;
        }
        Property.Type type;
        try {
            type = Property.Type.valueOf(StringArgumentType.getString(c, "type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            svc.messages().send(player, "property.admin.unknown_type");
            return Command.SINGLE_SUCCESS;
        }
        String priceInput = StringArgumentType.getString(c, "price");
        String name = StringArgumentType.getString(c, "name");
        Region region = Region.of(a.world(), a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
        svc.tasks().run(player, () -> properties().create(name, type, region,
                        priceInput.equals("0") ? Money.ZERO : Money.parsePositive(priceInput)),
                p -> {
                    svc.core().logger().info("[AUDIT] " + player.getName() + " created property #" + p.id() + " " + p.name());
                    svc.messages().send(player, "property.admin.created", "id", p.id(), "name", p.name(), "price", p.price());
                });
        return Command.SINGLE_SUCCESS;
    }

    private int adminDelete(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(sender, () -> {
            properties().delete(id);
            return id;
        }, deleted -> svc.messages().send(sender, "property.admin.deleted", "id", deleted));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ cities

    private LiteralCommandNode<CommandSourceStack> city() {
        return Commands.literal("city")
                .executes(c -> cityList(c.getSource().getSender()))
                .then(Commands.literal("list").executes(c -> cityList(c.getSource().getSender())))
                .then(Commands.literal("info").then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::cityInfo)))
                .then(Commands.literal("admin")
                        .requires(CommandServices.permission(Permissions.ADMIN_CITY))
                        .then(Commands.literal("create")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 10_000))
                                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::cityCreate)))))
                .build();
    }

    private int cityList(CommandSender sender) {
        svc.tasks().run(sender, () -> svc.core().cities().all(), cities -> {
            if (cities.isEmpty()) {
                svc.messages().send(sender, "city.list.empty");
                return;
            }
            svc.messages().send(sender, "city.list.header");
            for (City city : cities) {
                sender.sendMessage(svc.messages().render(sender, "city.list.entry", "name", city.name(), "x", city.centerX(), "z", city.centerZ())
                        .clickEvent(ClickEvent.runCommand("/city info " + city.name())));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int cityInfo(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        svc.tasks().run(sender, () -> svc.core().cities().stats(svc.core().cities().requireByName(name).id()), s -> {
            svc.messages().send(sender, "city.info.header", "name", s.city().name());
            svc.messages().send(sender, "city.info.details", "treasury", s.treasury(), "properties", s.properties(),
                    "owned", s.ownedProperties(), "residents", s.residents(), "businesses", s.businesses());
        });
        return Command.SINGLE_SUCCESS;
    }

    private int cityCreate(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Location loc = player.getLocation();
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int radius = IntegerArgumentType.getInteger(c, "radius");
        String name = StringArgumentType.getString(c, "name");
        svc.tasks().run(player, () -> svc.core().cities().create(name, world, x, z, radius), city -> {
            index.reloadCities();
            svc.core().logger().info("[AUDIT] " + player.getName() + " created city " + city.name());
            svc.messages().send(player, "city.created", "name", city.name(), "radius", city.radius());
        });
        return Command.SINGLE_SUCCESS;
    }

    private Component type(SupportedLocale locale, Property.Type type) {
        return svc.messages().render(locale, "property.type." + type.name());
    }
}
