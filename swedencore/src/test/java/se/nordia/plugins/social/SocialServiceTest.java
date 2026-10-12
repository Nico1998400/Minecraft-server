package se.nordia.plugins.social;

import org.junit.jupiter.api.Test;
import se.nordia.plugins.testing.CoreTest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SocialServiceTest extends CoreTest {

    @Test
    void nicknamesAreUniqueInAnyCaseAndNeverEqualARealName() {
        UUID anna = player("Anna");
        UUID bo = player("Bo");
        assertThat(core.social().setNick(anna, "Draken")).isEqualTo("Draken");
        assertDomainError(() -> core.social().setNick(bo, "draken"), "nick_taken");
        assertDomainError(() -> core.social().setNick(bo, "ANNA"), "nick_taken");
        core.social().setNick(anna, "DRAKEN");
        assertThat(core.social().nick(anna)).contains("DRAKEN");
        core.social().setNick(anna, "anna");
        assertThat(core.social().nick(anna)).contains("anna");
    }

    @Test
    void badNicknamesAreRejected() {
        UUID anna = player("Anna");
        for (String bad : List.of("", "ab", "x".repeat(17), "has space", "<red>x", "a;b", "§4nick")) {
            assertDomainError(() -> core.social().setNick(anna, bad), "nick_invalid");
        }
    }

    @Test
    void aNickCanBeClearedAndPlayersAreFoundByNameOrNick() {
        UUID anna = player("Anna");
        core.social().setNick(anna, "Draken");
        assertThat(core.social().findByNameOrNick("draken")).contains(anna);
        assertThat(core.social().findByNameOrNick("ANNA")).contains(anna);
        core.social().clearNick(anna);
        assertThat(core.social().findByNameOrNick("draken")).isEmpty();
        assertThat(core.social().nick(anna)).isEmpty();
    }

    @Test
    void aTemporaryMuteExpiresOnTheClock() {
        UUID anna = player("Anna");
        UUID admin = player("Admin");
        core.social().mute(anna, admin, "spam", Duration.ofMinutes(10));
        assertThat(core.social().activeMute(anna)).isPresent();
        clock.advance(Duration.ofMinutes(9));
        assertThat(core.social().activeMute(anna)).isPresent();
        clock.advance(Duration.ofMinutes(2));
        assertThat(core.social().activeMute(anna)).isEmpty();
        assertDomainError(() -> core.social().unmute(anna), "not_muted");
    }

    @Test
    void aPermanentMuteStaysUntilRemovedAndZeroDurationsAreRefused() {
        UUID anna = player("Anna");
        core.social().mute(anna, null, null, null);
        clock.advance(Duration.ofDays(400));
        assertThat(core.social().activeMute(anna)).isPresent();
        core.social().unmute(anna);
        assertThat(core.social().activeMute(anna)).isEmpty();
        assertDomainError(() -> core.social().mute(anna, null, "x", Duration.ZERO), "mute_duration_invalid");
        assertDomainError(() -> core.social().mute(anna, null, "x", Duration.ofMinutes(-1)), "mute_duration_invalid");
    }

    @Test
    void ignoreListsArePersonalAndNeverIncludeYourself() {
        UUID anna = player("Anna");
        UUID bo = player("Bo");
        core.social().ignore(anna, bo);
        core.social().ignore(anna, bo);
        assertThat(core.social().ignored(anna)).containsExactly(bo);
        assertThat(core.social().ignored(bo)).isEmpty();
        assertDomainError(() -> core.social().ignore(anna, anna), "ignore_self");
        core.social().unignore(anna, bo);
        assertThat(core.social().ignored(anna)).isEmpty();
    }
}
