package se.nordia.swedencore.player;

import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.Region;
import se.nordia.swedencore.skills.Skill;
import se.nordia.swedencore.testing.CoreTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileServiceTest extends CoreTest {

    @Test
    void profileTellsThePlayersStory() {
        UUID boss = player("Boss");
        UUID sven = player("Sven");
        grant(boss, 100_000);
        grant(sven, 100_000);
        Company first = core.companies().found(boss, "Nordhamn Mining AB");
        var position = core.jobs().createPosition(boss, first.id(), "MINER", "Junior Miner", 1, Money.ofSek(100), 1);
        core.jobs().accept(boss, core.jobs().apply(sven, position.id(), null).id());
        core.skills().addXp(sven, Skill.MINING, 5_000);
        core.skills().addXp(sven, Skill.FISHING, 200);
        core.companies().leave(sven, first.id());
        Company own = core.companies().found(sven, "Svens Stål AB");
        Property house = core.properties().create("Svens hus", Property.Type.HOUSE, Region.of("world", 0, 0, 0, 5, 5, 5), Money.ofSek(1_000));
        core.properties().buy(sven, house.id(), null, null);

        ProfileService.Profile profile = core.profiles().profile(sven);
        assertThat(profile.firstJob()).isEqualTo("Junior Miner");
        assertThat(profile.firstJobCompany()).isEqualTo("Nordhamn Mining AB");
        assertThat(profile.companies()).containsExactly(own.name());
        assertThat(profile.companiesFounded()).isEqualTo(1);
        assertThat(profile.propertiesOwned()).isEqualTo(1);
        assertThat(profile.topSkills()).extracting(p -> p.skill()).containsExactly(Skill.MINING, Skill.FISHING);
        assertThat(profile.settlement()).isNull();
        assertDomainError(() -> core.profiles().profile(UUID.randomUUID()), "player.unknown");
    }
}
