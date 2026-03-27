package fr.varyon.pwi;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;

public class ItemData {

    private int sectionId;
    private int slot;
    private String itemId;
    private int quantity;
    private double durability;
    private double maxDurability;
    private String metadata;

    public ItemData() {}

    public ItemData(int sectionId, int slot, ItemStack item) {
        this.sectionId = sectionId;
        this.slot = slot;
        this.itemId = item.getItemId();
        this.quantity = item.getQuantity();
        this.durability = item.getDurability();
        this.maxDurability = item.getMaxDurability();
        BsonDocument meta = item.getMetadata();
        if (meta != null) {
            this.metadata = meta.toJson();
        }
    }

    public ItemStack toItemStack() {
        try {
            BsonDocument metaDoc = (metadata != null && !metadata.isEmpty())
                    ? BsonDocument.parse(metadata) : null;
            return new ItemStack(itemId, quantity, durability, maxDurability, metaDoc);
        } catch (Exception e) {
            return null;
        }
    }

    public int getSectionId() { return sectionId; }
    public void setSectionId(int sectionId) { this.sectionId = sectionId; }
    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public double getDurability() { return durability; }
    public void setDurability(double durability) { this.durability = durability; }
    public double getMaxDurability() { return maxDurability; }
    public void setMaxDurability(double maxDurability) { this.maxDurability = maxDurability; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
