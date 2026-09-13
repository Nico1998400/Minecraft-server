package se.nordia.swedencore.testing;

import org.junit.jupiter.api.BeforeEach;
import se.nordia.swedencore.core.CoreConfig;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.core.NordiaCore;
import se.nordia.swedencore.database.DatabaseConfig;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.Money;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Base class for tests that need the fully wired {@link NordiaCore} against a fresh database. */
public abstract class CoreTest extends DatabaseTest {

    protected NordiaCore core;
    protected MutableClock clock;

    @BeforeEach
    void buildCore() {
        clock = new MutableClock(Instant.parse("2026-09-01T12:00:00Z"));
        CoreConfig config = configure(CoreConfig.defaults(new DatabaseConfig("unused", 0, "unused", "unused", "", "disable", 1, 1000)));
        core = new NordiaCore(config, database, clock, TestDatabase.logger());
    }

    /** Override to adjust configuration. */
    protected CoreConfig configure(CoreConfig defaults) {
        return defaults;
    }

    protected UUID player(String name) {
        UUID uuid = UUID.randomUUID();
        core.players().register(uuid, name);
        return uuid;
    }

    protected Money balance(UUID player) {
        return core.economy().balance(AccountOwner.player(player));
    }

    protected Money companyBalance(long companyId) {
        return core.economy().balance(AccountOwner.company(companyId));
    }

    protected void grant(UUID player, long sek) {
        core.economy().adminGrant(null, player, Money.ofSek(sek));
    }

    protected static void assertDomainError(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo(code));
    }

    protected void assertLedgerHealthy() {
        assertThat(core.economy().audit().healthy()).as("ledger audit").isTrue();
    }

    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        public void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
