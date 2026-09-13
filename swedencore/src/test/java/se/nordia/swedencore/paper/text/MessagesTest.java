package se.nordia.swedencore.paper.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.localization.LocalizationService;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.session.PlayerSessions;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesTest {

    private final Messages messages = new Messages(
            LocalizationService.loadBundled(Logger.getLogger("test"), getClass().getClassLoader(), null),
            new PlayerSessions(), SupportedLocale.SV_SE);

    @Test
    void playerControlledTextIsNeverParsedAsMiniMessage() {
        String evil = "<click:run_command:'/op Evil'><red>Pwned</red></click>";
        Component rendered = messages.render(SupportedLocale.EN_US, "economy.pay.received",
                "amount", Money.ofSek(5), "player", evil);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
        assertThat(plain).contains(evil);
        assertThat(clickEvents(rendered)).isEmpty();
    }

    @Test
    void moneyIsFormattedPerLocale() {
        String sv = PlainTextComponentSerializer.plainText().serialize(
                messages.render(SupportedLocale.SV_SE, "economy.balance.self", "balance", Money.ofOre(123_450)));
        String en = PlainTextComponentSerializer.plainText().serialize(
                messages.render(SupportedLocale.EN_US, "economy.balance.self", "balance", Money.ofOre(123_450)));
        assertThat(sv).contains("1 234,50 SEK").contains("Ditt saldo");
        assertThat(en).contains("1,234.50 SEK").contains("Your balance");
    }

    private static List<ClickEvent<?>> clickEvents(Component component) {
        List<ClickEvent<?>> events = new ArrayList<>();
        if (component.clickEvent() != null) {
            events.add(component.clickEvent());
        }
        for (Component child : component.children()) {
            events.addAll(clickEvents(child));
        }
        return events;
    }
}
