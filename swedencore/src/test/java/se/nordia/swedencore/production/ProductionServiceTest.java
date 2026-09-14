package se.nordia.swedencore.production;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.Region;
import se.nordia.swedencore.skills.Skill;
import se.nordia.swedencore.testing.CoreTest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionServiceTest extends CoreTest {

    /** Fake codec: bytes encode "MATERIAL:amount"; tools stack to 1. */
    private static final ItemStashService.ItemCodec CODEC = new ItemStashService.ItemCodec() {
        @Override
        public byte[] pristine(String material, int amount) {
            return (material + ":" + amount).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int maxStackSize(String material) {
            return material.endsWith("PICKAXE") ? 1 : 64;
        }

        @Override
        public boolean isKnownMaterial(String material) {
            return true;
        }
    };

    private UUID owner;
    private Company company;
    private ItemStashService.Owner stash;

    @BeforeEach
    void setUp() {
        owner = player("Owner");
        grant(owner, 200_000);
        company = core.companies().found(owner, "Verkstan AB");
        core.companies().deposit(owner, company.id(), Money.ofSek(100_000));
        stash = ItemStashService.Owner.company(company.id());
    }

    private void buyFactory(int x) {
        Property factory = core.properties().create("Fabrik " + x, Property.Type.FACTORY, Region.of("world", x, 0, 0, x + 10, 10, 10), Money.ofSek(10_000));
        core.properties().buy(owner, factory.id(), company.id(), null);
    }

    private void give(String material, int amount, boolean pristine) {
        core.database().inTransactionVoid(tx -> core.stash().deposit(tx, stash,
                List.of(new ItemStashService.StashItem(material, amount, CODEC.pristine(material, amount), pristine)), "TEST", null));
    }

    private long total(String material) {
        return core.stash().summary(stash).stream().filter(s -> s.material().equals(material)).mapToLong(ItemStashService.MaterialCount::total).sum();
    }

    @Test
    void runConsumesInputsAndDeliversOutputsWithXp() {
        buyFactory(0);
        give("RAW_IRON", 64, true);
        give("RAW_IRON", 10, true);
        give("COAL", 5, true);
        var run = core.production().start(owner, company.id(), "iron_smelting", 3, CODEC);
        assertThat(total("RAW_IRON")).isEqualTo(74 - 24);
        assertThat(total("COAL")).isEqualTo(2);
        assertThat(core.production().completeDue(CODEC)).isEmpty();

        clock.advance(Duration.ofSeconds(180));
        assertThat(core.production().completeDue(CODEC)).extracting(ProductionService.Run::id).containsExactly(run.id());
        assertThat(total("IRON_INGOT")).isEqualTo(24);
        assertThat(core.skills().load(owner).get(Skill.ENGINEERING).xp()).isEqualTo(60);
        assertThat(core.production().completeDue(CODEC)).isEmpty();
    }

    @Test
    void rulesForFactoriesInputsOperatorsAndCapacity() {
        give("RAW_IRON", 64, true);
        give("COAL", 64, true);
        assertDomainError(() -> core.production().start(owner, company.id(), "iron_smelting", 1, CODEC), "production.no_factory");
        buyFactory(0);
        assertDomainError(() -> core.production().start(owner, company.id(), "nope", 1, CODEC), "production.unknown_recipe");
        assertDomainError(() -> core.production().start(owner, company.id(), "iron_smelting", 17, CODEC), "production.invalid_batches");
        assertDomainError(() -> core.production().start(owner, company.id(), "gold_smelting", 1, CODEC), "job.skill_too_low");
        assertDomainError(() -> core.production().start(owner, company.id(), "glassworks", 1, CODEC), "production.missing_input");

        UUID worker = player("Worker");
        var miner = core.jobs().createPosition(owner, company.id(), "MINER", "Miner", 1, Money.ZERO, 1);
        core.jobs().accept(owner, core.jobs().apply(worker, miner.id(), null).id());
        assertDomainError(() -> core.production().start(worker, company.id(), "iron_smelting", 1, CODEC), "production.not_operator");

        core.production().start(owner, company.id(), "iron_smelting", 1, CODEC);
        core.production().start(owner, company.id(), "iron_smelting", 1, CODEC);
        assertDomainError(() -> core.production().start(owner, company.id(), "iron_smelting", 1, CODEC), "production.capacity_full");
        buyFactory(100);
        core.production().start(owner, company.id(), "iron_smelting", 1, CODEC);
    }

    @Test
    void onlyPristineStacksAreConsumedAndNothingIsTakenOnFailure() {
        buyFactory(0);
        give("RAW_IRON", 64, false);
        give("COAL", 1, true);
        assertDomainError(() -> core.production().start(owner, company.id(), "iron_smelting", 1, CODEC), "production.missing_input");
        assertThat(total("COAL")).isEqualTo(1);
        assertThat(total("RAW_IRON")).isEqualTo(64);
    }

    @Test
    void engineersCanOperateAndOutputsRespectStackSizes() {
        buyFactory(0);
        UUID engineer = player("Engineer");
        var position = core.jobs().createPosition(owner, company.id(), "ENGINEER", "Engineer", 1, Money.ZERO, 1);
        core.jobs().accept(owner, core.jobs().apply(engineer, position.id(), null).id());
        core.skills().addXp(engineer, Skill.ENGINEERING, core.skills().curve().totalXpForLevel(20));
        give("IRON_INGOT", 9, true);
        give("STICK", 6, true);
        core.production().start(engineer, company.id(), "toolworks", 3, CODEC);
        clock.advance(Duration.ofMinutes(10));
        core.production().completeDue(CODEC);
        assertThat(core.stash().summary(stash).stream().filter(s -> s.material().equals("IRON_PICKAXE")).findFirst().orElseThrow().stacks())
                .isEqualTo(3);
    }
}
