package fr.varyon.pwi;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerInventoryData {

    /**
     * All inventory sections to persist, in order:
     *  Armor (-3), Hotbar (-1), Utility (-5), Storage (-2), Backpack (-9), Tool (-8)
     *
     * Tool (TOOLS_SECTION_ID = -8) is the left-hand / tools slot. It is intentionally
     * absent from InventoryComponent.EVERYTHING (which omits it), so it must be handled
     * explicitly — that was the root cause of the lost items bug in the original mod.
     */
    private static final int[] SECTION_IDS = {
        InventoryComponent.ARMOR_SECTION_ID,
        InventoryComponent.HOTBAR_SECTION_ID,
        InventoryComponent.UTILITY_SECTION_ID,
        InventoryComponent.STORAGE_SECTION_ID,
        InventoryComponent.BACKPACK_SECTION_ID,
        InventoryComponent.TOOLS_SECTION_ID
    };

    private List<ItemData> items = new ArrayList<>();
    private int activeHotbarSlot   = 0;
    private int activeUtilitySlot  = -1;
    private int activeToolsSlot    = -1;
    private boolean usingToolsItem = false;

    public static PlayerInventoryData fromHolder(Holder<EntityStore> holder) {
        PlayerInventoryData data = new PlayerInventoryData();

        InventoryComponent.Hotbar hotbar = holder.getComponent(InventoryComponent.Hotbar.getComponentType());
        if (hotbar != null) {
            data.activeHotbarSlot = hotbar.getActiveSlot();
        }

        InventoryComponent.Utility utility = holder.getComponent(InventoryComponent.Utility.getComponentType());
        if (utility != null) {
            data.activeUtilitySlot = utility.getActiveSlot();
        }

        InventoryComponent.Tool tool = holder.getComponent(InventoryComponent.Tool.getComponentType());
        if (tool != null) {
            data.activeToolsSlot   = tool.getActiveSlot();
            data.usingToolsItem    = tool.isUsingToolsItem();
        }

        for (int sectionId : SECTION_IDS) {
            InventoryComponent section = (InventoryComponent) holder.getComponent(
                    InventoryComponent.getComponentTypeById(sectionId));
            if (section == null) continue;

            ItemContainer container = section.getInventory();
            short capacity = container.getCapacity();
            for (short i = 0; i < capacity; i++) {
                ItemStack item = container.getItemStack(i);
                if (item == null || item.isEmpty()) continue;
                data.items.add(new ItemData(sectionId, i, item));
            }
        }

        return data;
    }

    public void applyToInventory(Holder<EntityStore> holder) {
        Map<Integer, Map<Integer, ItemStack>> bySection = new HashMap<>();
        for (ItemData itemData : items) {
            ItemStack restored = itemData.toItemStack();
            if (restored == null || restored.isEmpty()) continue;
            bySection.computeIfAbsent(itemData.getSectionId(), k -> new HashMap<>())
                     .put(itemData.getSlot(), restored);
        }

        for (int sectionId : SECTION_IDS) {
            InventoryComponent section = (InventoryComponent) holder.getComponent(
                    InventoryComponent.getComponentTypeById(sectionId));
            if (section == null) continue;

            ItemContainer container = section.getInventory();
            Map<Integer, ItemStack> sectionItems = bySection.getOrDefault(sectionId, Map.of());
            short capacity = container.getCapacity();
            for (short i = 0; i < capacity; i++) {
                try {
                    // filter=false: bypass slot filters (armor/utility filters would block
                    // ItemStack.EMPTY and cross-type items, silently aborting the operation)
                    container.setItemStackForSlot(i, sectionItems.getOrDefault((int) i, ItemStack.EMPTY), false);
                } catch (Exception ignored) {}
            }
            section.markDirty();
        }

        InventoryComponent.Hotbar hotbar = holder.getComponent(InventoryComponent.Hotbar.getComponentType());
        if (hotbar != null) {
            try { hotbar.setActiveSlot((byte) activeHotbarSlot); } catch (Exception ignored) {}
        }

        InventoryComponent.Utility utility = holder.getComponent(InventoryComponent.Utility.getComponentType());
        if (utility != null) {
            try { utility.setActiveSlot((byte) activeUtilitySlot); } catch (Exception ignored) {}
        }

        InventoryComponent.Tool tool = holder.getComponent(InventoryComponent.Tool.getComponentType());
        if (tool != null) {
            try {
                tool.setActiveSlot((byte) activeToolsSlot);
                tool.setUsingToolsItem(usingToolsItem);
            } catch (Exception ignored) {}
        }
    }

    public static void clearInventory(Holder<EntityStore> holder) {
        for (int sectionId : SECTION_IDS) {
            InventoryComponent section = (InventoryComponent) holder.getComponent(
                    InventoryComponent.getComponentTypeById(sectionId));
            if (section == null) continue;

            ItemContainer container = section.getInventory();
            short capacity = container.getCapacity();
            for (short i = 0; i < capacity; i++) {
                try {
                    container.setItemStackForSlot(i, ItemStack.EMPTY, false);
                } catch (Exception ignored) {}
            }
            section.markDirty();
        }
    }

    public List<ItemData> getItems() { return items; }
    public void setItems(List<ItemData> items) { this.items = items; }
    public int getActiveHotbarSlot() { return activeHotbarSlot; }
    public void setActiveHotbarSlot(int slot) { this.activeHotbarSlot = slot; }
    public int getActiveUtilitySlot() { return activeUtilitySlot; }
    public void setActiveUtilitySlot(int slot) { this.activeUtilitySlot = slot; }
    public int getActiveToolsSlot() { return activeToolsSlot; }
    public void setActiveToolsSlot(int slot) { this.activeToolsSlot = slot; }
    public boolean isUsingToolsItem() { return usingToolsItem; }
    public void setUsingToolsItem(boolean usingToolsItem) { this.usingToolsItem = usingToolsItem; }
}
