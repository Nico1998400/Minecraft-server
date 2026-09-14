package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.player.ProfileService;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** {@code /profile [player]} (alias {@code /profil}): a player's identity and story. Balance only shown to oneself. */
public final class ProfileCommand {

    private final CommandServices svc;

    public ProfileCommand(CommandServices svc) {
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(node(), "Profil / Profile", List.of("profil"));
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("profile")
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player != null) {
                        UUID uuid = player.getUniqueId();
                        show(player, () -> svc.core().profiles().profile(uuid), true);
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(CommandServices.onlinePlayerNames())
                        .executes(c -> {
                            CommandSender sender = c.getSource().getSender();
                            String name = StringArgumentType.getString(c, "player");
                            boolean self = sender instanceof Player p && p.getName().equalsIgnoreCase(name);
                            show(sender, () -> svc.core().profiles().profile(svc.core().players().requireByName(name).uuid()), self);
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void show(CommandSender viewer, java.util.concurrent.Callable<ProfileService.Profile> load, boolean self) {
        svc.tasks().run(viewer, load, p -> {
            SupportedLocale locale = svc.messages().localeOf(viewer);
            svc.messages().send(viewer, "profile.header", "player", p.player().name(), "reputation", p.player().reputation(),
                    "since", p.player().firstSeen());
            if (self) {
                svc.messages().send(viewer, "profile.balance", "balance", p.balance());
            }
            if (p.firstJob() != null) {
                svc.messages().send(viewer, "profile.started_as", "job", p.firstJob(), "company", p.firstJobCompany());
            }
            if (!p.topSkills().isEmpty()) {
                String skills = p.topSkills().stream()
                        .map(s -> svc.messages().raw(locale, "skill." + s.skill().name()) + " " + s.level())
                        .collect(Collectors.joining(", "));
                svc.messages().send(viewer, "profile.skills", "skills", skills);
            }
            if (!p.companies().isEmpty()) {
                svc.messages().send(viewer, "profile.companies", "companies", String.join(", ", p.companies()));
            }
            if (!p.shareholdings().isEmpty()) {
                svc.messages().send(viewer, "profile.shareholdings", "companies", String.join(", ", p.shareholdings()));
            }
            if (p.settlement() != null) {
                svc.messages().send(viewer, "profile.settlement", "settlement", p.settlement());
            }
            svc.messages().send(viewer, "profile.record", "founded", p.companiesFounded(), "properties", p.propertiesOwned(),
                    "contracts", p.contractsCompleted(), "shops", p.shopsRun());
        });
    }
}
