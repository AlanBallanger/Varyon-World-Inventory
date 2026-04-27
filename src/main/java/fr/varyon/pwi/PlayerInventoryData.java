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
        try {
            return fromHolderUnsafe(holder, data);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            return data;
        }
    }

    private static PlayerInventoryData fromHolderUnsafe(Holder<EntityStore> holder, PlayerInventoryData data) {
        try {
            InventoryComponent.Hotbar hotbar = holder.getComponent(InventoryComponent.Hotbar.getComponentType());
            if (hotbar != null) {
                data.activeHotbarSlot = hotbar.getActiveSlot();
            }
        } catch (Exception ignored) {}

        try {
            InventoryComponent.Utility utility = holder.getComponent(InventoryComponent.Utility.getComponentType());
            if (utility != null) {
                data.activeUtilitySlot = utility.getActiveSlot();
            }
        } catch (Exception ignored) {}

        try {
            InventoryComponent.Tool tool = holder.getComponent(InventoryComponent.Tool.getComponentType());
            if (tool != null) {
                data.activeToolsSlot = tool.getActiveSlot();
                data.usingToolsItem = tool.isUsingToolsItem();
            }
        } catch (Exception ignored) {}

        for (int sectionId : SECTION_IDS) {
            InventoryComponent section = (InventoryComponent) holder.getComponent(
                    InventoryComponent.getComponentTypeById(sectionId));
            if (section == null) continue;

            ItemContainer container = section.getInventory();
            if (container == null) continue;
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
        try {
            applyToInventoryUnsafe(holder);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable ignored) {
        }
    }

    private void applyToInventoryUnsafe(Holder<EntityStore> holder) {
        List<ItemData> list = items;
        if (list == null) {
            list = List.of();
        }
        Map<Integer, Map<Integer, ItemStack>> bySection = new HashMap<>();
        for (ItemData itemData : list) {
            if (itemData == null) continue;
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
            if (container == null) continue;
            short capacity = container.getCapacity();
            if (capacity <= 0) continue;

            Map<Integer, ItemStack> sectionItems = bySection.getOrDefault(sectionId, Map.of());
            for (short i = 0; i < capacity; i++) {
                try {
                    container.setItemStackForSlot(i, sectionItems.getOrDefault((int) i, ItemStack.EMPTY), false);
                } catch (Exception ignored) {}
            }
            try {
                section.markDirty();
            } catch (Exception ignored) {}
        }

        InventoryComponent.Hotbar hotbar = holder.getComponent(InventoryComponent.Hotbar.getComponentType());
        if (hotbar != null && slotInRange(activeHotbarSlot, InventoryComponent.HOTBAR_SECTION_ID, holder)) {
            try { hotbar.setActiveSlot((byte) activeHotbarSlot); } catch (Exception ignored) {}
        }

        InventoryComponent.Utility utility = holder.getComponent(InventoryComponent.Utility.getComponentType());
        if (utility != null && slotInRange(activeUtilitySlot, InventoryComponent.UTILITY_SECTION_ID, holder)) {
            try { utility.setActiveSlot((byte) activeUtilitySlot); } catch (Exception ignored) {}
        }

        InventoryComponent.Tool tool = holder.getComponent(InventoryComponent.Tool.getComponentType());
        if (tool != null) {
            try {
                if (slotInRange(activeToolsSlot, InventoryComponent.TOOLS_SECTION_ID, holder)) {
                    tool.setActiveSlot((byte) activeToolsSlot);
                }
                tool.setUsingToolsItem(usingToolsItem);
            } catch (Exception ignored) {}
        }
    }

    public static boolean inventoriesReadyForData(Holder<EntityStore> holder, PlayerInventoryData data) {
        try {
            if (data == null || data.getItems() == null || data.getItems().isEmpty()) {
                return true;
            }
            Map<Integer, Integer> maxSlotBySection = new HashMap<>();
            for (ItemData id : data.getItems()) {
                if (id == null) continue;
                maxSlotBySection.merge(id.getSectionId(), id.getSlot(), Math::max);
            }
            for (Integer sectionId : maxSlotBySection.keySet()) {
                InventoryComponent section = (InventoryComponent) holder.getComponent(
                        InventoryComponent.getComponentTypeById(sectionId));
                if (section == null) return false;
                ItemContainer container = section.getInventory();
                if (container == null || container.getCapacity() <= 0) return false;
            }
            return true;
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean slotInRange(int slot, int sectionId, Holder<EntityStore> holder) {
        if (slot < 0) return false;
        InventoryComponent section = (InventoryComponent) holder.getComponent(
                InventoryComponent.getComponentTypeById(sectionId));
        if (section == null) return false;
        ItemContainer container = section.getInventory();
        if (container == null) return false;
        short cap = container.getCapacity();
        return cap > 0 && slot < cap;
    }

    public static void clearInventory(Holder<EntityStore> holder) {
        try {
            for (int sectionId : SECTION_IDS) {
                InventoryComponent section = (InventoryComponent) holder.getComponent(
                        InventoryComponent.getComponentTypeById(sectionId));
                if (section == null) continue;

                ItemContainer container = section.getInventory();
                if (container == null) continue;
                short capacity = container.getCapacity();
                if (capacity <= 0) continue;
                for (short i = 0; i < capacity; i++) {
                    try {
                        container.setItemStackForSlot(i, ItemStack.EMPTY, false);
                    } catch (Exception ignored) {}
                }
                try {
                    section.markDirty();
                } catch (Exception ignored) {}
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable ignored) {
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
