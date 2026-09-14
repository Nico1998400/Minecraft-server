package se.nordia.swedencore.paper.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import se.nordia.swedencore.inventory.ItemStashService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Moves items between player inventories and the server stash. Main thread only.
 *
 * <p>Only <em>pristine</em> items count for deliveries: stacks identical to a freshly created item of that material
 * (no custom name, enchantments, damage or other data). This stops renamed junk or broken tools being delivered as
 * goods.
 */
public final class ItemTransfer {

    private ItemTransfer() {
    }

    public static int countPristine(Player player, Material material) {
        ItemStack reference = ItemStack.of(material);
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.isSimilar(reference)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** Removes up to {@code max} pristine items of a material from storage slots and returns them as stacks. */
    public static List<ItemStack> takePristine(Player player, Material material, int max) {
        PlayerInventory inventory = player.getInventory();
        ItemStack reference = ItemStack.of(material);
        ItemStack[] contents = inventory.getStorageContents();
        int remaining = max;
        int taken = 0;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !stack.isSimilar(reference)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            if (take == stack.getAmount()) {
                contents[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
            remaining -= take;
            taken += take;
        }
        inventory.setStorageContents(contents);
        List<ItemStack> result = new ArrayList<>();
        int maxStack = Math.max(1, material.getMaxStackSize());
        while (taken > 0) {
            int amount = Math.min(maxStack, taken);
            result.add(ItemStack.of(material, amount));
            taken -= amount;
        }
        return result;
    }

    /** Gives items back; anything that does not fit is dropped at the player's feet. */
    public static void giveOrDrop(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(items.toArray(ItemStack[]::new));
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    public static int freeStorageSlots(Player player) {
        int free = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                free++;
            }
        }
        return free;
    }

    public static ItemStashService.StashItem toStash(ItemStack stack) {
        boolean pristine = stack.isSimilar(ItemStack.of(stack.getType()));
        return new ItemStashService.StashItem(stack.getType().name(), stack.getAmount(), stack.serializeAsBytes(), pristine);
    }

    /** Paper implementation of the domain's item codec. */
    public static final ItemStashService.ItemCodec CODEC = new ItemStashService.ItemCodec() {
        @Override
        public byte[] pristine(String material, int amount) {
            return ItemStack.of(Material.valueOf(material), amount).serializeAsBytes();
        }

        @Override
        public int maxStackSize(String material) {
            return Material.valueOf(material).getMaxStackSize();
        }

        @Override
        public boolean isKnownMaterial(String material) {
            Material m = Material.matchMaterial(material);
            return m != null && m.isItem() && !m.isAir();
        }
    };

    public static List<ItemStashService.StashItem> toStash(List<ItemStack> stacks) {
        return stacks.stream().map(ItemTransfer::toStash).toList();
    }

    public static ItemStack fromStash(byte[] data) {
        return ItemStack.deserializeBytes(data);
    }
}
