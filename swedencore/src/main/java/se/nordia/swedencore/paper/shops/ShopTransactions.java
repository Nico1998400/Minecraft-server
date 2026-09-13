package se.nordia.swedencore.paper.shops;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import se.nordia.swedencore.shops.ShopListing;

import java.util.ArrayList;
import java.util.List;

/** Container stock handling for shop purchases. Main thread only. */
public final class ShopTransactions {

    private ShopTransactions() {
    }

    /** The exact item a listing sells (amount 1). */
    public static ItemStack template(ShopListing listing) {
        ItemStack template = ItemStack.deserializeBytes(listing.item());
        template.setAmount(1);
        return template;
    }

    public static int countMatching(Inventory inventory, ItemStack template) {
        int count = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.isSimilar(template)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** Removes exactly {@code amount} matching items, or nothing if there are fewer. Returns the removed stacks. */
    public static List<ItemStack> take(Inventory inventory, ItemStack template, int amount) {
        if (countMatching(inventory, template) < amount) {
            return List.of();
        }
        ItemStack[] contents = inventory.getContents();
        int remaining = amount;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !stack.isSimilar(template)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            if (take == stack.getAmount()) {
                contents[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
            remaining -= take;
        }
        inventory.setContents(contents);
        List<ItemStack> result = new ArrayList<>();
        int maxStack = Math.max(1, template.getMaxStackSize());
        int left = amount;
        while (left > 0) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(maxStack, left));
            result.add(stack);
            left -= stack.getAmount();
        }
        return result;
    }
}
