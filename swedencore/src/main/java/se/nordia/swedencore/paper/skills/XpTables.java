package se.nordia.swedencore.paper.skills;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** XP values per material, loaded from skills.yml. Immutable after construction. */
public final class XpTables {

    /**
     * Crops that are always planted by players and only count when fully grown. Note that sugar cane, cactus and
     * bamboo also implement {@code Ageable} in Bukkit but their age is a growth timer, so they are placement-checked.
     */
    public static final Set<Material> GROWTH_CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.COCOA,
            Material.TORCHFLOWER_CROP, Material.PITCHER_CROP, Material.NETHER_WART);

    private final Map<Material, Integer> mining;
    private final Map<Material, Integer> forestry;
    private final Map<Material, Integer> farming;
    private final Map<Material, Integer> herbalism;
    private final Map<Material, Integer> herbalismHarvest;
    private final Map<Material, Integer> fishing;
    private final int fishingDefault;
    private final Map<Material, Integer> building;
    private final Set<Material> tracked;

    public XpTables(YamlConfiguration yaml, Logger logger) {
        mining = section(yaml, "mining", logger);
        forestry = section(yaml, "forestry", logger);
        farming = section(yaml, "farming", logger);
        herbalism = section(yaml, "herbalism", logger);
        herbalismHarvest = section(yaml, "herbalism-harvest", logger);
        fishing = section(yaml, "fishing", logger);
        fishingDefault = Math.max(0, yaml.getInt("fishing.default", 0));
        building = section(yaml, "building", logger);

        EnumSet<Material> all = EnumSet.noneOf(Material.class);
        all.addAll(mining.keySet());
        all.addAll(forestry.keySet());
        all.addAll(herbalism.keySet());
        all.addAll(building.keySet());
        // Farming blocks that are not growth-stage crops must be tracked so placed melons, cane etc. give no XP.
        for (Material m : farming.keySet()) {
            if (!GROWTH_CROPS.contains(m)) {
                all.add(m);
            }
        }
        // Growth crops (e.g. nether wart under herbalism) are always planted; maturity, not placement, decides.
        all.removeAll(GROWTH_CROPS);
        tracked = Collections.unmodifiableSet(all);
    }

    private static Map<Material, Integer> section(YamlConfiguration yaml, String path, Logger logger) {
        EnumMap<Material, Integer> result = new EnumMap<>(Material.class);
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            return Collections.unmodifiableMap(result);
        }
        // Tags first so explicit materials override them.
        for (String key : section.getKeys(false)) {
            if (!key.startsWith("#")) {
                continue;
            }
            int xp = section.getInt(key);
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, NamespacedKey.minecraft(key.substring(1)), Material.class);
            if (tag == null) {
                tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, NamespacedKey.minecraft(key.substring(1)), Material.class);
            }
            if (tag == null) {
                logger.warning("skills.yml: unknown tag " + key + " in " + path);
                continue;
            }
            for (Material m : tag.getValues()) {
                result.put(m, xp);
            }
        }
        for (String key : section.getKeys(false)) {
            if (key.startsWith("#") || key.equals("default")) {
                continue;
            }
            Material material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
            if (material == null) {
                logger.warning("skills.yml: unknown material " + key + " in " + path);
                continue;
            }
            result.put(material, Math.max(0, section.getInt(key)));
        }
        return Collections.unmodifiableMap(result);
    }

    public Integer mining(Material m) {
        return mining.get(m);
    }

    public Integer forestry(Material m) {
        return forestry.get(m);
    }

    public Integer farming(Material m) {
        return farming.get(m);
    }

    public Integer herbalism(Material m) {
        return herbalism.get(m);
    }

    public Integer herbalismHarvest(Material m) {
        return herbalismHarvest.get(m);
    }

    public int fishing(Material m) {
        return fishing.getOrDefault(m, fishingDefault);
    }

    public Integer building(Material m) {
        return building.get(m);
    }

    /** Materials whose placement must be remembered by the placed-block tracker. */
    public boolean isTracked(Material m) {
        return tracked.contains(m);
    }
}
