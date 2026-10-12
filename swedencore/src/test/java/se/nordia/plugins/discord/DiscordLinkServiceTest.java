package se.nordia.plugins.discord;

import org.junit.jupiter.api.Test;
import se.nordia.plugins.testing.CoreTest;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordLinkServiceTest extends CoreTest {

    private static final String DISCORD_A = "123456789012345678";
    private static final String DISCORD_B = "223456789012345678";

    @Test
    void aCodeLinksTheAccountExactlyOnce() {
        UUID anna = player("Anna");
        String code = core.discordLinks().createCode(anna);
        assertThat(code).hasSize(6).matches("[A-Z2-9]+");
        assertThat(core.discordLinks().completeLink(DISCORD_A, code.toLowerCase())).isEqualTo(anna);
        assertThat(core.discordLinks().linkedDiscordId(anna)).contains(DISCORD_A);
        assertDomainError(() -> core.discordLinks().completeLink(DISCORD_B, code), "discord_code_invalid");
    }

    @Test
    void codesExpireAndUnknownCodesFail() {
        UUID anna = player("Anna");
        String code = core.discordLinks().createCode(anna);
        clock.advance(Duration.ofMinutes(11));
        assertDomainError(() -> core.discordLinks().completeLink(DISCORD_A, code), "discord_code_invalid");
        assertDomainError(() -> core.discordLinks().completeLink(DISCORD_A, "ZZZZZZ"), "discord_code_invalid");
        assertDomainError(() -> core.discordLinks().completeLink(DISCORD_A, null), "discord_code_invalid");
    }

    @Test
    void aNewCodeReplacesTheOldOne() {
        UUID anna = player("Anna");
        String first = core.discordLinks().createCode(anna);
        String second = core.discordLinks().createCode(anna);
        assertDomainError(() -> core.discordLinks().completeLink(DISCORD_A, first), "discord_code_invalid");
        core.discordLinks().completeLink(DISCORD_A, second);
        assertThat(core.discordLinks().linkedDiscordId(anna)).isPresent();
    }

    @Test
    void oneDiscordAccountCannotBeLinkedToTwoPlayers() {
        UUID anna = player("Anna");
        UUID bo = player("Bo");
        core.discordLinks().completeLink(DISCORD_A, core.discordLinks().createCode(anna));
        String code = core.discordLinks().createCode(bo);
        assertDomainError(() -> core.discordLinks().completeLink(DISCORD_A, code), "discord_account_taken");
        assertThat(core.discordLinks().linkedDiscordId(bo)).isEmpty();
    }

    @Test
    void aLinkedPlayerCannotLinkAgainButCanUnlink() {
        UUID anna = player("Anna");
        core.discordLinks().completeLink(DISCORD_A, core.discordLinks().createCode(anna));
        assertDomainError(() -> core.discordLinks().createCode(anna), "discord_already_linked");
        core.discordLinks().unlink(anna);
        assertDomainError(() -> core.discordLinks().unlink(anna), "discord_not_linked");
        core.discordLinks().completeLink(DISCORD_B, core.discordLinks().createCode(anna));
        assertThat(core.discordLinks().linkedDiscordId(anna)).contains(DISCORD_B);
    }

    @Test
    void aMalformedDiscordIdIsRejectedByTheDatabase() {
        UUID anna = player("Anna");
        String code = core.discordLinks().createCode(anna);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> core.discordLinks().completeLink("not-a-number", code))
                .isInstanceOf(RuntimeException.class);
        assertThat(core.discordLinks().linkedDiscordId(anna)).isEmpty();
    }
}
