package se.nordia.swedencore.production;

import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.companies.CompanyService;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.skills.Skill;
import se.nordia.swedencore.skills.SkillService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Industrial production: companies turn raw materials from their inventory into goods at industrial properties.
 *
 * <p>Each recipe runs at a facility type (factory, industrial land, farm or mine). A run consumes all inputs up front
 * (atomically), takes {@code seconds × batches}, then deposits the outputs into the company inventory and rewards the
 * operator with Engineering XP. Capacity per facility type scales with the number of such properties the company
 * occupies — investing in property increases output, the core company growth loop.
 */
public final class ProductionService {

    /** Property types that can host production. */
    public static final java.util.Set<Property.Type> FACILITIES =
            java.util.EnumSet.of(Property.Type.FACTORY, Property.Type.INDUSTRIAL_LAND, Property.Type.FARM, Property.Type.MINE);

    public record Recipe(String id, Map<String, Integer> inputs, Map<String, Integer> outputs, int seconds,
                         int engineeringLevel, long xpPerBatch, Property.Type facility) {
        public Recipe {
            inputs = Map.copyOf(inputs);
            outputs = Map.copyOf(outputs);
            if (inputs.isEmpty() || outputs.isEmpty() || seconds < 1 || engineeringLevel < 1 || xpPerBatch < 0
                    || facility == null || !FACILITIES.contains(facility)) {
                throw new IllegalArgumentException("Invalid recipe " + id);
            }
            for (var amount : inputs.values()) {
                if (amount < 1 || amount > 1_000) {
                    throw new IllegalArgumentException("Invalid input amount in recipe " + id);
                }
            }
            for (var amount : outputs.values()) {
                if (amount < 1 || amount > 1_000) {
                    throw new IllegalArgumentException("Invalid output amount in recipe " + id);
                }
            }
        }

        public Recipe(String id, Map<String, Integer> inputs, Map<String, Integer> outputs, int seconds, int engineeringLevel, long xpPerBatch) {
            this(id, inputs, outputs, seconds, engineeringLevel, xpPerBatch, Property.Type.FACTORY);
        }
    }

    public record Config(Map<String, Recipe> recipes, int runsPerFactory, int maxBatches) {
        public Config {
            recipes = Map.copyOf(recipes);
            if (runsPerFactory < 1 || maxBatches < 1 || maxBatches > 64) {
                throw new IllegalArgumentException("Invalid production configuration");
            }
        }

        public static Config defaults() {
            Map<String, Recipe> recipes = new HashMap<>();
            recipes.put("iron_smelting", new Recipe("iron_smelting", Map.of("RAW_IRON", 8, "COAL", 1), Map.of("IRON_INGOT", 8), 60, 1, 20));
            recipes.put("copper_smelting", new Recipe("copper_smelting", Map.of("RAW_COPPER", 8, "COAL", 1), Map.of("COPPER_INGOT", 8), 60, 1, 15));
            recipes.put("gold_smelting", new Recipe("gold_smelting", Map.of("RAW_GOLD", 8, "COAL", 1), Map.of("GOLD_INGOT", 8), 90, 10, 30));
            recipes.put("glassworks", new Recipe("glassworks", Map.of("SAND", 8, "COAL", 1), Map.of("GLASS", 8), 60, 1, 15));
            recipes.put("stoneworks", new Recipe("stoneworks", Map.of("COBBLESTONE", 8, "COAL", 1), Map.of("STONE", 8), 45, 1, 10));
            recipes.put("sawmill", new Recipe("sawmill", Map.of("OAK_LOG", 4), Map.of("OAK_PLANKS", 18), 30, 1, 10));
            recipes.put("toolworks", new Recipe("toolworks", Map.of("IRON_INGOT", 3, "STICK", 2), Map.of("IRON_PICKAXE", 1), 120, 20, 60));
            recipes.put("rail_works", new Recipe("rail_works", Map.of("IRON_INGOT", 6, "STICK", 1), Map.of("RAIL", 16), 90, 15, 40));
            recipes.put("brickworks", new Recipe("brickworks", Map.of("CLAY_BALL", 8, "COAL", 1), Map.of("BRICK", 8), 60, 5, 15,
                    Property.Type.INDUSTRIAL_LAND));
            recipes.put("charcoal_kiln", new Recipe("charcoal_kiln", Map.of("OAK_LOG", 8), Map.of("CHARCOAL", 8), 90, 1, 10,
                    Property.Type.INDUSTRIAL_LAND));
            recipes.put("concrete_mixing", new Recipe("concrete_mixing", Map.of("SAND", 4, "GRAVEL", 4, "WHITE_DYE", 1),
                    Map.of("WHITE_CONCRETE_POWDER", 8), 60, 10, 20, Property.Type.INDUSTRIAL_LAND));
            recipes.put("stone_crusher", new Recipe("stone_crusher", Map.of("COBBLESTONE", 8), Map.of("GRAVEL", 8), 45, 1, 10,
                    Property.Type.MINE));
            recipes.put("gravel_sifting", new Recipe("gravel_sifting", Map.of("GRAVEL", 8), Map.of("SAND", 6, "FLINT", 2), 45, 5, 12,
                    Property.Type.MINE));
            recipes.put("bakery", new Recipe("bakery", Map.of("WHEAT", 9), Map.of("BREAD", 4), 60, 5, 15, Property.Type.FARM));
            recipes.put("sugar_mill", new Recipe("sugar_mill", Map.of("SUGAR_CANE", 8), Map.of("SUGAR", 9), 45, 1, 8, Property.Type.FARM));
            recipes.put("composting", new Recipe("composting", Map.of("WHEAT_SEEDS", 16), Map.of("BONE_MEAL", 3), 60, 1, 5, Property.Type.FARM));
            return new Config(recipes, 2, 16);
        }
    }

    public record Run(long id, long companyId, String recipe, int batches, UUID operator, boolean completed, Instant finishesAt) {
    }

    private final Database database;
    private final CompanyService companies;
    private final ItemStashService stash;
    private final SkillService skills;
    private final Config config;
    private final Clock clock;

    public ProductionService(Database database, CompanyService companies, ItemStashService stash, SkillService skills,
                             Config config, Clock clock) {
        this.database = database;
        this.companies = companies;
        this.stash = stash;
        this.skills = skills;
        this.config = config;
        this.clock = clock;
    }

    public Config config() {
        return config;
    }

    /**
     * Starts a run. The operator must be the owner, a manager or an employee in an ENGINEER position, with the
     * recipe's Engineering level.
     */
    public Run start(UUID operator, long companyId, String recipeId, int batches, ItemStashService.ItemCodec codec) {
        Recipe recipe = config.recipes().get(recipeId == null ? "" : recipeId.toLowerCase(java.util.Locale.ROOT));
        if (recipe == null) {
            throw new DomainException("production.unknown_recipe");
        }
        if (batches < 1 || batches > config.maxBatches()) {
            throw DomainException.of("production.invalid_batches", "max", config.maxBatches());
        }
        return database.inTransaction(tx -> {
            companies.lockActive(tx, companyId);
            CompanyRole role = companies.requireRole(tx, companyId, operator, CompanyRole.values());
            if (role == CompanyRole.EMPLOYEE) {
                boolean engineer = tx.queryOne("""
                        SELECT 1 FROM company_employees e JOIN job_positions p ON p.id = e.position_id
                        WHERE e.company_id = ? AND e.player_uuid = ? AND e.ended_at IS NULL AND p.job_id = 'ENGINEER'""",
                        rs -> true, companyId, operator).isPresent();
                if (!engineer) {
                    throw new DomainException("production.not_operator");
                }
            }
            int level = skills.level(tx, operator, Skill.ENGINEERING);
            if (level < recipe.engineeringLevel()) {
                throw DomainException.of("job.skill_too_low", "level", recipe.engineeringLevel(), "current", level, "skill", Skill.ENGINEERING);
            }
            // Facilities of the recipe's type the company occupies: owned (and not leased out) or rented.
            long facilities = tx.queryLong("""
                    SELECT count(*) FROM properties p
                    LEFT JOIN property_leases le ON le.property_id = p.id AND le.status IN ('ACTIVE', 'OVERDUE')
                    WHERE p.type = ? AND COALESCE(le.tenant_type, p.owner_type) = 'COMPANY'
                      AND COALESCE(le.tenant_id, p.owner_id) = ?""", recipe.facility(), Long.toString(companyId));
            if (facilities == 0) {
                throw DomainException.of("production.no_facility", "facility", recipe.facility());
            }
            long running = tx.queryLong("SELECT count(*) FROM production_runs WHERE company_id = ? AND facility = ? AND status = 'RUNNING'",
                    companyId, recipe.facility());
            if (running >= facilities * config.runsPerFactory()) {
                throw DomainException.of("production.capacity_full", "capacity", facilities * config.runsPerFactory(),
                        "facility", recipe.facility());
            }
            Map<String, Integer> inputs = new HashMap<>();
            recipe.inputs().forEach((material, amount) -> inputs.put(material, Math.multiplyExact(amount, batches)));
            stash.consumePristine(tx, ItemStashService.Owner.company(companyId), inputs, codec, operator);
            // Reserve room for the outputs now so a finished run never overflows the inventory.
            long outputStacks = 0;
            for (Map.Entry<String, Integer> output : recipe.outputs().entrySet()) {
                int maxStack = Math.clamp(codec.maxStackSize(output.getKey()), 1, ItemStashService.MAX_STACK);
                long amount = (long) output.getValue() * batches;
                outputStacks += (amount + maxStack - 1) / maxStack;
            }
            stash.requireCapacity(tx, ItemStashService.Owner.company(companyId), outputStacks);
            Instant now = clock.instant();
            Instant finishes = now.plus(Duration.ofSeconds((long) recipe.seconds() * batches));
            long id = tx.queryLong("""
                            INSERT INTO production_runs (company_id, recipe, batches, operator_uuid, xp, started_at, finishes_at, facility, outputs)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    companyId, recipe.id(), batches, operator, recipe.xpPerBatch() * batches, now, finishes, recipe.facility(),
                    encodeOutputs(recipe.outputs(), batches));
            return new Run(id, companyId, recipe.id(), batches, operator, false, finishes);
        });
    }

    /** Completes finished runs: outputs to the company inventory, XP to the operator. Returns completed runs. */
    public List<Run> completeDue(ItemStashService.ItemCodec codec) {
        List<Long> due = database.inTransaction(tx -> tx.queryList(
                "SELECT id FROM production_runs WHERE status = 'RUNNING' AND finishes_at <= ? ORDER BY finishes_at LIMIT 100",
                rs -> rs.getLong(1), clock.instant()));
        List<Run> completed = new ArrayList<>();
        for (long id : due) {
            Optional<Run> run = database.inTransaction(tx -> complete(tx, id, codec));
            run.ifPresent(completed::add);
        }
        return completed;
    }

    private Optional<Run> complete(Tx tx, long id, ItemStashService.ItemCodec codec) throws SQLException {
        record Row(long companyId, String recipe, int batches, UUID operator, String status, long xp, Instant finishes, String outputs) {
        }
        Row row = tx.queryOne("SELECT * FROM production_runs WHERE id = ? FOR UPDATE",
                rs -> new Row(rs.getLong("company_id"), rs.getString("recipe"), rs.getInt("batches"), Tx.uuid(rs, "operator_uuid"),
                        rs.getString("status"), rs.getLong("xp"), Tx.instant(rs, "finishes_at"), rs.getString("outputs")), id).orElseThrow();
        if (!"RUNNING".equals(row.status()) || row.finishes().isAfter(clock.instant())) {
            return Optional.empty();
        }
        // Prefer the snapshot taken at start; runs from before V14 fall back to the current recipe.
        Map<String, Integer> outputs = row.outputs() != null ? decodeOutputs(row.outputs()) : null;
        if (outputs == null) {
            Recipe recipe = config.recipes().get(row.recipe());
            outputs = new java.util.TreeMap<>();
            if (recipe != null) {
                for (Map.Entry<String, Integer> output : recipe.outputs().entrySet()) {
                    outputs.put(output.getKey(), Math.multiplyExact(output.getValue(), row.batches()));
                }
            }
        }
        for (Map.Entry<String, Integer> output : outputs.entrySet()) {
            stash.depositPristine(tx, ItemStashService.Owner.company(row.companyId()), output.getKey(), output.getValue(), codec,
                    "PRODUCTION", Long.toString(id));
        }
        if (row.xp() > 0) {
            skills.addXp(tx, row.operator(), Skill.ENGINEERING, Math.min(row.xp(), 1_000_000));
        }
        tx.update("UPDATE production_runs SET status = 'COMPLETED', completed_at = now() WHERE id = ?", id);
        return Optional.of(new Run(id, row.companyId(), row.recipe(), row.batches(), row.operator(), true, row.finishes()));
    }

    /** Total output amounts for a run, e.g. {@code "GRAVEL:64;SAND:48"}. Materials match {@link ItemStashService#MATERIAL}. */
    static String encodeOutputs(Map<String, Integer> perBatch, int batches) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> output : new java.util.TreeMap<>(perBatch).entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(output.getKey()).append(':').append(Math.multiplyExact(output.getValue(), batches));
        }
        return sb.toString();
    }

    /** Returns null if the snapshot is malformed, so the caller can fall back to the recipe. */
    static Map<String, Integer> decodeOutputs(String encoded) {
        Map<String, Integer> outputs = new java.util.TreeMap<>();
        for (String part : encoded.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            String material = part.substring(0, colon);
            int amount;
            try {
                amount = Integer.parseInt(part.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (!ItemStashService.MATERIAL.matcher(material).matches() || amount < 1) {
                return null;
            }
            outputs.merge(material, amount, Math::addExact);
        }
        return outputs.isEmpty() ? null : outputs;
    }

    public List<Run> runs(long companyId, int limit) {
        return database.inTransaction(tx -> tx.queryList("""
                        SELECT * FROM production_runs WHERE company_id = ? ORDER BY (status = 'RUNNING') DESC, id DESC LIMIT ?""",
                rs -> new Run(rs.getLong("id"), rs.getLong("company_id"), rs.getString("recipe"), rs.getInt("batches"),
                        Tx.uuid(rs, "operator_uuid"), "COMPLETED".equals(rs.getString("status")), Tx.instant(rs, "finishes_at")),
                companyId, Math.clamp(limit, 1, 20)));
    }
}
