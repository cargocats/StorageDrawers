package com.jaquadro.minecraft.storagedrawers.storage;

import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.MinecraftForge;

import com.jaquadro.minecraft.storagedrawers.api.event.DrawerPopulatedEvent;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer;

public class DrawerData extends BaseDrawerData implements IVoidable, IShroudable, ILockable, IQuantifiable {

    private static final ItemStack nullStack = new ItemStack((Item) null);

    private IStorageProvider storageProvider;
    private int slot;

    private ItemStack protoStack;
    private int count;

    public DrawerData(IStorageProvider provider, int slot) {
        storageProvider = provider;
        protoStack = nullStack;
        this.slot = slot;

        postInit();
    }

    @Override
    public ItemStack getStoredItemPrototype() {
        if (protoStack == nullStack) return null;

        return protoStack;
    }

    @Override
    public void setStoredItem(ItemStack itemPrototype, int amount) {
        setStoredItem(itemPrototype, amount, true);
    }

    @Override
    public IDrawer setStoredItemRedir(ItemStack itemPrototype, int amount) {
        setStoredItem(itemPrototype, amount, true);
        return this;
    }

    private void setStoredItem(ItemStack itemPrototype, int amount, boolean mark) {
        if (itemPrototype == null) {
            setStoredItemCount(0, false, true);
            protoStack = nullStack;
            inventoryStack.reset();

            DrawerPopulatedEvent event = new DrawerPopulatedEvent(this);
            MinecraftForge.EVENT_BUS.post(event);

            if (mark) storageProvider.markDirty(slot);
            return;
        }

        protoStack = itemPrototype.copy();
        protoStack.stackSize = 1;

        refreshOreDictMatches();
        setStoredItemCount(amount, mark, false);
        inventoryStack.reset();

        DrawerPopulatedEvent event = new DrawerPopulatedEvent(this);
        MinecraftForge.EVENT_BUS.post(event);

        if (mark) storageProvider.markDirty(slot);
    }

    @Override
    public int getStoredItemCount() {
        if (protoStack != nullStack && storageProvider.isVendingUnlimited(slot)) return Integer.MAX_VALUE;

        return count;
    }

    @Override
    public void setStoredItemCount(int amount) {
        setStoredItemCount(amount, true, true);
    }

    public void setStoredItemCount(int amount, boolean mark, boolean clearOnEmpty) {
        if (storageProvider.isVendingUnlimited(slot)) return;

        count = amount;
        if (count > getMaxCapacity()) count = getMaxCapacity();

        if (amount == 0) {
            if (clearOnEmpty) {
                if (!storageProvider.isLocked(slot, LockAttribute.LOCK_POPULATED)) reset();
                if (mark) storageProvider.markDirty(slot);
            }
        } else if (mark) storageProvider.markAmountDirty(slot);
    }

    @Override
    public int getMaxCapacity() {
        return getMaxCapacity(protoStack);
    }

    @Override
    public int getMaxCapacity(ItemStack itemPrototype) {
        if (itemPrototype == null || itemPrototype.getItem() == null) return 0;

        if (storageProvider.isStorageUnlimited(slot) || storageProvider.isVendingUnlimited(slot))
            return Integer.MAX_VALUE;

        return itemPrototype.getItem().getItemStackLimit(itemPrototype) * storageProvider.getSlotStackCapacity(slot);
    }

    @Override
    public int getRemainingCapacity() {
        if (protoStack.getItem() == null) return 0;

        if (storageProvider.isVendingUnlimited(slot)) return Integer.MAX_VALUE;

        return getMaxCapacity() - getStoredItemCount();
    }

    @Override
    public int getStoredItemStackSize() {
        if (protoStack.getItem() == null) return 0;

        return protoStack.getItem().getItemStackLimit(protoStack);
    }

    @Override
    protected int getItemCapacityForInventoryStack() {
        if (storageProvider.isVoid(slot)) return Integer.MAX_VALUE;
        else return getMaxCapacity();
    }

    @Override
    public boolean canItemBeStored(ItemStack itemPrototype) {
        if (protoStack == nullStack && !isLocked(LockAttribute.LOCK_EMPTY)) return true;

        return areItemsEqual(itemPrototype);
    }

    @Override
    public boolean canItemBeExtracted(ItemStack itemPrototype) {
        if (protoStack == nullStack) return false;

        return areItemsEqual(itemPrototype);
    }

    @Override
    public boolean isEmpty() {
        return protoStack == nullStack;
    }

    public void writeToNBT(NBTTagCompound tag) {
        if (protoStack.getItem() != null) {
            tag.setShort("Item", (short) Item.getIdFromItem(protoStack.getItem()));
            tag.setShort("Meta", (short) protoStack.getItemDamage());
            tag.setInteger("Count", count);

            if (protoStack.getTagCompound() != null) tag.setTag("Tags", protoStack.getTagCompound());
        }
    }

    public void readFromNBT(NBTTagCompound tag) {
        if (tag.hasKey("Item") && tag.hasKey("Count")) {
            Item item = Item.getItemById(tag.getShort("Item"));
            if (item != null) {
                ItemStack stack = new ItemStack(item);
                stack.setItemDamage(tag.getShort("Meta"));
                if (tag.hasKey("Tags")) stack.setTagCompound(tag.getCompoundTag("Tags"));

                setStoredItem(stack, tag.getInteger("Count"), false);
            } else {
                reset();
            }
        } else {
            reset();
        }
    }

    @Override
    protected void reset() {
        protoStack = nullStack;
        super.reset();

        DrawerPopulatedEvent event = new DrawerPopulatedEvent(this);
        MinecraftForge.EVENT_BUS.post(event);
    }

    @Override
    public boolean isVoid() {
        return storageProvider.isVoid(slot);
    }

    @Override
    public boolean isShrouded() {
        return storageProvider.isShrouded(slot);
    }

    @Override
    public boolean setIsShrouded(boolean state) {
        return storageProvider.setIsShrouded(slot, state);
    }

    @Override
    public boolean isQuantified() { return storageProvider.isQuantified(slot); }

    @Override
    public boolean setIsQuantified(boolean state) {
        return storageProvider.setIsQuantified(slot, state);
    }

    @Override
    public boolean isLocked(LockAttribute attr) {
        return storageProvider.isLocked(slot, attr);
    }

    @Override
    public boolean canLock(LockAttribute attr) {
        return false;
    }

    @Override
    public void setLocked(LockAttribute attr, boolean isLocked) {}
}
