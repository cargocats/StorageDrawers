package com.jaquadro.minecraft.storagedrawers.block.tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.*;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.Constants;

import com.jaquadro.minecraft.storagedrawers.StorageDrawers;
import com.jaquadro.minecraft.storagedrawers.api.inventory.IDrawerInventory;
import com.jaquadro.minecraft.storagedrawers.api.security.ISecurityProvider;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup;
import com.jaquadro.minecraft.storagedrawers.api.storage.IFractionalDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.INetworked;
import com.jaquadro.minecraft.storagedrawers.api.storage.IPriorityGroup;
import com.jaquadro.minecraft.storagedrawers.api.storage.ISmartGroup;
import com.jaquadro.minecraft.storagedrawers.block.BlockDrawers;
import com.jaquadro.minecraft.storagedrawers.block.BlockSlave;
import com.jaquadro.minecraft.storagedrawers.security.SecurityManager;
import com.jaquadro.minecraft.storagedrawers.util.ItemMetaListRegistry;
import com.mojang.authlib.GameProfile;

public class TileEntityController extends TileEntity
        implements IDrawerGroup, IPriorityGroup, ISmartGroup, ISidedInventory {

    private static final int PRI_VOID = 0;
    private static final int PRI_LOCKED = 1;
    private static final int PRI_NORMAL = 2;
    private static final int PRI_EMPTY = 3;
    private static final int PRI_LOCKED_EMPTY = 4;
    private static final int PRI_DISABLED = 5;

    private static final int DEPTH_LIMIT = 12;
    private static final int[] emptySlots = new int[0];

    private static class StorageRecord {

        public IDrawerGroup storage;
        public boolean mark;
        public int invStorageSize;
        public int drawerStorageSize;
        public int distance = Integer.MAX_VALUE;

        public void clear() {
            storage = null;
            mark = false;
            invStorageSize = 0;
            drawerStorageSize = 0;
            distance = Integer.MAX_VALUE;
        }
    }

    private static class SlotRecord {

        public BlockCoord coord;
        public int slot;

        public int index;
        public int priority;

        public SlotRecord(BlockCoord coord, int slot) {
            this.coord = coord;
            this.slot = slot;
        }
    }

    private Comparator<SlotRecord> slotRecordComparator = new Comparator<SlotRecord>() {

        @Override
        public int compare(SlotRecord o1, SlotRecord o2) {
            return o1.priority - o2.priority;
        }
    };

    private int getSlotPriority(SlotRecord record, boolean invBased) {
        IDrawerGroup group = getGroupForCoord(record.coord);
        if (group == null) {
            return PRI_DISABLED;
        }

        int drawerSlot = (invBased) ? group.getDrawerInventory().getDrawerSlot(record.slot) : record.slot;
        if (!group.isDrawerEnabled(drawerSlot)) {
            return PRI_DISABLED;
        }

        IDrawer drawer = group.getDrawer(drawerSlot);
        if (drawer.isEmpty()) {
            if ((drawer instanceof ILockable && ((ILockable) drawer).isLocked(LockAttribute.LOCK_EMPTY))
                    || (group instanceof ILockable && ((ILockable) group).isLocked(LockAttribute.LOCK_EMPTY))) {
                return PRI_LOCKED_EMPTY;
            } else return PRI_EMPTY;
        }

        if ((drawer instanceof IVoidable && ((IVoidable) drawer).isVoid())
                || (group instanceof IVoidable && ((IVoidable) group).isVoid())) {
            return PRI_VOID;
        }

        if ((drawer instanceof ILockable && ((ILockable) drawer).isLocked(LockAttribute.LOCK_POPULATED))
                || (group instanceof ILockable && ((ILockable) group).isLocked(LockAttribute.LOCK_POPULATED))) {
            return PRI_LOCKED;
        }

        return PRI_NORMAL;
    }

    private Queue<BlockCoord> searchQueue = new LinkedList<BlockCoord>();
    private Set<BlockCoord> searchDiscovered = new HashSet<BlockCoord>();

    private Map<BlockCoord, StorageRecord> storage = new HashMap<BlockCoord, StorageRecord>();
    private List<SlotRecord> invSlotList = new ArrayList<SlotRecord>();
    private List<SlotRecord> drawerSlotList = new ArrayList<SlotRecord>();

    private ItemMetaListRegistry<SlotRecord> invPrimaryLookup = new ItemMetaListRegistry<SlotRecord>();
    private ItemMetaListRegistry<SlotRecord> drawerPrimaryLookup = new ItemMetaListRegistry<SlotRecord>();

    private int[] inventorySlots = new int[0];
    private int[] drawerSlots = new int[0];
    private int[] autoSides = new int[] { 0, 1, 2, 3, 4, 5 };
    private int direction;

    private int drawerSize = 0;
    private int range;
    private int maxDrawers;
    private int drawersCount;

    private long lastClickTime;
    private UUID lastClickUUID;

    private String customName;

    public TileEntityController() {
        invSlotList.add(new SlotRecord(null, 0));
        inventorySlots = new int[] { 0 };
        range = StorageDrawers.config.getControllerRange();
        maxDrawers = StorageDrawers.config.getControllerMaxDrawers();
    }

    public int getDirection() {
        return direction;
    }

    public void setDirection(int direction) {
        this.direction = direction % 6;
    }

    public int interactPutItemsIntoInventory(EntityPlayer player) {
        if (inventorySlots.length == 0) updateCache();

        boolean dumpInventory = worldObj.getTotalWorldTime() - lastClickTime < 10
                && player.getPersistentID().equals(lastClickUUID);
        int count = 0;

        if (!dumpInventory) {
            ItemStack currentStack = player.inventory.getCurrentItem();
            if (currentStack != null) {
                count = insertItems(currentStack, player.getGameProfile());
                if (currentStack.stackSize == 0)
                    player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
            }
        } else {
            for (int i = 0, n = player.inventory.getSizeInventory(); i < n; i++) {
                ItemStack subStack = player.inventory.getStackInSlot(i);
                if (subStack != null) {
                    count += insertItems(subStack, player.getGameProfile());
                    if (subStack.stackSize == 0) player.inventory.setInventorySlotContents(i, null);
                }
            }

            if (count > 0) StorageDrawers.proxy.updatePlayerInventory(player);
        }

        lastClickTime = worldObj.getTotalWorldTime();
        lastClickUUID = player.getPersistentID();

        return count;
    }

    private int insertItems(ItemStack stack, GameProfile profile) {
        int itemsLeft = stack.stackSize;

        for (int slot : enumerateDrawersForInsertion(stack, false)) {
            IDrawerGroup group = getGroupForDrawerSlot(slot);
            if (group instanceof IProtectable) {
                if (!SecurityManager.hasAccess(profile, (IProtectable) group)) continue;
            }

            IDrawer drawer = getDrawer(slot);
            ItemStack itemProto = drawer.getStoredItemPrototype();
            if (itemProto == null) break;

            itemsLeft = insertItemsIntoDrawer(drawer, itemsLeft);

            if (drawer instanceof IVoidable && ((IVoidable) drawer).isVoid()) itemsLeft = 0;
            if (itemsLeft == 0) break;
        }

        int count = stack.stackSize - itemsLeft;
        stack.stackSize = itemsLeft;

        return count;
    }

    private int insertItemsIntoDrawer(IDrawer drawer, int itemCount) {
        int capacity = drawer.getMaxCapacity();
        int storedItems = drawer.getStoredItemCount();

        int storableItems = capacity - storedItems;
        if (drawer instanceof IFractionalDrawer) {
            IFractionalDrawer fracDrawer = (IFractionalDrawer) drawer;
            if (!fracDrawer.isSmallestUnit() && fracDrawer.getStoredItemRemainder() > 0) storableItems--;
        }

        if (storableItems == 0) return itemCount;

        int remainder = Math.max(itemCount - storableItems, 0);
        storedItems += Math.min(itemCount, storableItems);
        drawer.setStoredItemCount(storedItems);

        return remainder;
    }

    public void toggleProtection(GameProfile profile, ISecurityProvider provider) {
        IProtectable template = null;
        UUID state = null;

        for (StorageRecord record : storage.values()) {
            if (record.storage == null) continue;

            if (record.storage instanceof IProtectable) {
                IProtectable protectable = (IProtectable) record.storage;
                if (!SecurityManager.hasOwnership(profile, protectable)) continue;

                if (template == null) {
                    template = protectable;

                    if (template.getOwner() == null) state = profile.getId();
                    else {
                        state = null;
                        provider = null;
                    }
                }

                protectable.setOwner(state);
                protectable.setSecurityProvider(provider);
            }
        }
    }

    public void toggleShroud(GameProfile profile) {
        IShroudable template = null;
        boolean state = false;

        for (StorageRecord record : storage.values()) {
            if (record.storage == null) continue;

            if (record.storage instanceof IProtectable) {
                if (!SecurityManager.hasAccess(profile, (IProtectable) record.storage)) continue;
            }

            for (int i = 0, n = record.storage.getDrawerCount(); i < n; i++) {
                if (!record.storage.isDrawerEnabled(i)) continue;

                IDrawer drawer = record.storage.getDrawer(i);
                if (!(drawer instanceof IShroudable)) continue;

                IShroudable shroudableStorage = (IShroudable) drawer;
                if (template == null) {
                    template = shroudableStorage;
                    state = !template.isShrouded();
                }

                shroudableStorage.setIsShrouded(state);
            }
        }
    }

    public void toggleQuantify(GameProfile profile) {
        IQuantifiable template = null;
        boolean state = false;

        for (StorageRecord record : storage.values()) {
            if (record.storage == null) continue;

            if (record.storage instanceof IProtectable) {
                if (!SecurityManager.hasAccess(profile, (IProtectable) record.storage)) continue;
            }

            if (record.storage instanceof IQuantifiable) {
                IQuantifiable quantifiableStorage = (IQuantifiable) storage;
                if (template == null) {
                    template = quantifiableStorage;
                    state = !template.isQuantified();
                }

                quantifiableStorage.setIsQuantified(state);
            }
        }
    }

    public void toggleLock(EnumSet<LockAttribute> attributes, LockAttribute key, GameProfile profile) {
        ILockable template = null;
        boolean state = false;

        for (StorageRecord record : storage.values()) {
            if (record.storage == null) continue;

            if (record.storage instanceof IProtectable) {
                if (!SecurityManager.hasAccess(profile, (IProtectable) record.storage)) continue;
            }

            if (record.storage instanceof ILockable) {
                ILockable lockableStorage = (ILockable) record.storage;
                if (template == null) {
                    template = lockableStorage;
                    state = !template.isLocked(key);
                }

                for (LockAttribute attr : attributes) lockableStorage.setLocked(attr, state);
            } else {
                for (int i = 0, n = record.storage.getDrawerCount(); i < n; i++) {
                    if (!record.storage.isDrawerEnabled(i)) continue;

                    IDrawer drawer = record.storage.getDrawer(i);
                    if (!(drawer instanceof IShroudable)) continue;

                    ILockable lockableStorage = (ILockable) drawer;
                    if (template == null) {
                        template = lockableStorage;
                        state = !template.isLocked(key);
                    }

                    for (LockAttribute attr : attributes) lockableStorage.setLocked(attr, state);
                }
            }
        }
    }

    private void resetCache() {
        storage.clear();
        invSlotList.clear();
        drawerSlotList.clear();
        drawerSize = 0;
    }

    public boolean isValidSlave(BlockCoord coord) {
        StorageRecord record = storage.get(coord);
        if (record == null || !record.mark) return false;

        return record.storage == null;
    }

    public void updateCache() {
        int preCount = inventorySlots.length;

        resetCache();

        populateNodes(xCoord, yCoord, zCoord);

        flattenLists();
        inventorySlots = sortSlotRecords(invSlotList, true);
        drawerSlots = sortSlotRecords(drawerSlotList, false);

        rebuildPrimaryLookup(invPrimaryLookup, invSlotList, true);
        rebuildPrimaryLookup(drawerPrimaryLookup, drawerSlotList, false);

        if (preCount != inventorySlots.length && (preCount == 0 || inventorySlots.length == 0)) {
            if (!worldObj.isRemote) markDirty();
        }
    }

    private void indexSlotRecords(List<SlotRecord> records, boolean invBased) {
        for (int i = 0, n = records.size(); i < n; i++) {
            SlotRecord record = records.get(i);
            if (record != null) {
                record.index = i;
                record.priority = getSlotPriority(record, invBased);
            }
        }
    }

    private int[] sortSlotRecords(List<SlotRecord> records, boolean invBased) {
        indexSlotRecords(records, invBased);

        List<SlotRecord> copied = new ArrayList<SlotRecord>(records);
        Collections.sort(copied, slotRecordComparator);

        int[] slotMap = new int[copied.size()];
        for (int i = 0; i < slotMap.length; i++) slotMap[i] = copied.get(i).index;

        return slotMap;
    }

    private void rebuildPrimaryLookup(ItemMetaListRegistry<SlotRecord> lookup, List<SlotRecord> records,
            boolean invBased) {
        lookup.clear();

        for (SlotRecord record : records) {
            IDrawerGroup group = getGroupForCoord(record.coord);
            if (group == null) continue;

            int drawerSlot = (invBased) ? group.getDrawerInventory().getDrawerSlot(record.slot) : record.slot;
            if (!group.isDrawerEnabled(drawerSlot)) continue;

            IDrawer drawer = group.getDrawer(drawerSlot);
            if (drawer.isEmpty()) continue;

            ItemStack item = drawer.getStoredItemPrototype();
            lookup.register(item.getItem(), item.getItemDamage(), record);
        }
    }

    private boolean containsNullEntries(List<SlotRecord> list) {
        int nullCount = 0;
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i) == null) nullCount++;
        }

        return nullCount > 0;
    }

    private void flattenLists() {
        if (containsNullEntries(invSlotList)) {
            List<SlotRecord> newInvSlotList = new ArrayList<SlotRecord>();

            for (int i = 0, n = invSlotList.size(); i < n; i++) {
                SlotRecord record = invSlotList.get(i);
                if (record != null) newInvSlotList.add(record);
            }

            invSlotList = newInvSlotList;
        }

        if (containsNullEntries(drawerSlotList)) {
            List<SlotRecord> newDrawerSlotList = new ArrayList<SlotRecord>();

            for (int i = 0, n = drawerSlotList.size(); i < n; i++) {
                SlotRecord record = drawerSlotList.get(i);
                if (record != null) newDrawerSlotList.add(record);
            }

            drawerSlotList = newDrawerSlotList;
        }
    }

    private void clearRecordInfo(BlockCoord coord, StorageRecord record) {
        record.clear();

        for (int i = 0; i < invSlotList.size(); i++) {
            SlotRecord slotRecord = invSlotList.get(i);
            if (slotRecord != null && coord.equals(slotRecord.coord)) invSlotList.set(i, null);
        }

        for (int i = 0; i < drawerSlotList.size(); i++) {
            SlotRecord slotRecord = drawerSlotList.get(i);
            if (slotRecord != null && coord.equals(slotRecord.coord)) drawerSlotList.set(i, null);
        }
    }

    private void updateRecordInfo(BlockCoord coord, StorageRecord record, TileEntity te) {
        if (te == null) {
            if (record.storage != null) clearRecordInfo(coord, record);

            return;
        }

        if (te instanceof TileEntityController) {
            if (record.storage == null && record.invStorageSize > 0) return;

            if (record.storage != null) clearRecordInfo(coord, record);

            record.storage = null;
            record.invStorageSize = 1;

            invSlotList.add(new SlotRecord(null, 0));
        } else if (te instanceof TileEntitySlave) {
            if (record.storage == null && record.invStorageSize == 0) {
                if (((TileEntitySlave) te).getController() == this) return;
            }

            if (record.storage != null) clearRecordInfo(coord, record);

            record.storage = null;
            record.invStorageSize = 0;

            ((TileEntitySlave) te).bindController(xCoord, yCoord, zCoord);
        } else if (te instanceof IDrawerGroup) {
            IDrawerGroup group = (IDrawerGroup) te;
            if (record.storage == group) return;

            if (record.storage != null && record.storage != group) clearRecordInfo(coord, record);

            IDrawerInventory inventory = group.getDrawerInventory();
            if (inventory == null) return;

            record.storage = group;
            record.invStorageSize = inventory.getSizeInventory();
            record.drawerStorageSize = group.getDrawerCount();

            for (int i = 0, n = record.invStorageSize; i < n; i++) invSlotList.add(new SlotRecord(coord, i));

            for (int i = 0, n = record.drawerStorageSize; i < n; i++) drawerSlotList.add(new SlotRecord(coord, i));

            drawerSize += record.drawerStorageSize;
        } else {
            if (record.storage != null) clearRecordInfo(coord, record);
        }
    }

    private void populateNodes(int x, int y, int z) {
        BlockCoord root = new BlockCoord(x, y, z);

        searchQueue.clear();
        searchQueue.add(root);

        searchDiscovered.clear();
        searchDiscovered.add(root);

        drawersCount = 0;

        while (!searchQueue.isEmpty()) {
            BlockCoord coord = searchQueue.remove();
            int depth = Math.max(Math.max(Math.abs(coord.x() - x), Math.abs(coord.y() - y)), Math.abs(coord.z() - z));
            if (depth > range) continue;

            Block block = worldObj.getBlock(coord.x(), coord.y(), coord.z());
            if (!(block instanceof INetworked)) continue;

            StorageRecord record = storage.get(coord);
            if (record == null) {
                record = new StorageRecord();
                storage.put(coord, record);
            }

            if (block instanceof BlockSlave) {
                ((BlockSlave) block).getTileEntitySafe(worldObj, coord.x(), coord.y(), coord.z());
            } else if (block instanceof BlockDrawers && ++drawersCount > maxDrawers) break;

            updateRecordInfo(coord, record, worldObj.getTileEntity(coord.x(), coord.y(), coord.z()));
            record.mark = true;
            record.distance = depth;

            BlockCoord[] neighbors = new BlockCoord[] { new BlockCoord(coord.x() + 1, coord.y(), coord.z()),
                    new BlockCoord(coord.x() - 1, coord.y(), coord.z()),
                    new BlockCoord(coord.x(), coord.y(), coord.z() + 1),
                    new BlockCoord(coord.x(), coord.y(), coord.z() - 1),
                    new BlockCoord(coord.x(), coord.y() + 1, coord.z()),
                    new BlockCoord(coord.x(), coord.y() - 1, coord.z()), };

            for (BlockCoord n : neighbors) {
                if (!searchDiscovered.contains(n)) {
                    searchQueue.add(n);
                    searchDiscovered.add(n);
                }
            }
        }
    }

    private IDrawerGroup getGroupForInvSlot(int invSlot) {
        if (invSlot >= invSlotList.size()) return null;

        SlotRecord record = invSlotList.get(invSlot);
        if (record == null) return null;

        return getGroupForCoord(record.coord);
    }

    private IDrawerGroup getGroupForDrawerSlot(int drawerSlot) {
        if (drawerSlot >= drawerSlotList.size()) return null;

        SlotRecord record = drawerSlotList.get(drawerSlot);
        if (record == null) return null;

        return getGroupForCoord(record.coord);
    }

    private IDrawerGroup getGroupForCoord(BlockCoord coord) {
        if (coord == null) return null;

        StorageRecord record = storage.get(coord);
        if (record == null) return null;

        if (record.storage instanceof TileEntity) {
            TileEntity tile = (TileEntity) record.storage;
            if (tile.isInvalid() && tile != worldObj.getTileEntity(coord.x(), coord.y(), coord.z())) {
                storage.remove(coord);
                return null;
            }
        }

        return record.storage;
    }

    private int getLocalInvSlot(int invSlot) {
        if (invSlot >= invSlotList.size()) return 0;

        SlotRecord record = invSlotList.get(invSlot);
        if (record == null) return 0;

        return record.slot;
    }

    private int getLocalDrawerSlot(int drawerSlot) {
        if (drawerSlot >= drawerSlotList.size()) return 0;

        SlotRecord record = drawerSlotList.get(drawerSlot);
        if (record == null) return 0;

        return record.slot;
    }

    private IDrawerInventory getDrawerInventory(int invSlot) {
        IDrawerGroup group = getGroupForInvSlot(invSlot);
        if (group == null) return null;

        return group.getDrawerInventory();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);

        setDirection(tag.getByte("Dir"));

        if (tag.hasKey("CustomName", Constants.NBT.TAG_STRING)) customName = tag.getString("CustomName");

        if (worldObj != null && !worldObj.isRemote) updateCache();
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);

        tag.setByte("Dir", (byte) direction);

        if (hasCustomInventoryName()) tag.setString("CustomName", customName);
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeToNBT(tag);

        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 5, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readFromNBT(pkt.func_148857_g());
        getWorldObj().func_147479_m(xCoord, yCoord, zCoord); // markBlockForRenderUpdate
    }

    @Override
    public IDrawerInventory getDrawerInventory() {
        return null;
    }

    @Override
    public int getDrawerCount() {
        return drawerSlotList.size();
    }

    @Override
    public IDrawer getDrawer(int slot) {
        IDrawerGroup group = getGroupForDrawerSlot(slot);
        if (group == null) return null;

        return group.getDrawer(getLocalDrawerSlot(slot));
    }

    @Override
    public boolean isDrawerEnabled(int slot) {
        IDrawerGroup group = getGroupForDrawerSlot(slot);
        if (group == null) return false;

        return group.isDrawerEnabled(getLocalDrawerSlot(slot));
    }

    @Override
    public int[] getAccessibleDrawerSlots() {
        return drawerSlots;
    }

    @Override
    public void markDirty() {
        for (StorageRecord record : storage.values()) {
            IDrawerGroup group = record.storage;
            if (group != null && group.getDrawerInventory() != null) group.markDirtyIfNeeded();
        }

        super.markDirty();
    }

    @Override
    public boolean markDirtyIfNeeded() {
        boolean synced = false;

        for (StorageRecord record : storage.values()) {
            IDrawerGroup group = record.storage;
            if (group != null && group.getDrawerInventory() != null) synced |= group.markDirtyIfNeeded();
        }

        if (synced) super.markDirty();

        return synced;
    }

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        for (int aside : autoSides) {
            if (side == aside) return inventorySlots;
        }

        return emptySlots;
    }

    @Override
    public boolean canInsertItem(int slot, ItemStack stack, int side) {
        IDrawerInventory inventory = getDrawerInventory(slot);
        if (inventory == null) return false;

        if (slot >= invSlotList.size()) return false;

        SlotRecord record = invSlotList.get(slot);
        List<SlotRecord> primaryRecords = invPrimaryLookup.getEntries(stack.getItem(), stack.getItemDamage());
        if (primaryRecords != null && !primaryRecords.contains(record)) {
            for (SlotRecord candidate : primaryRecords) {
                IDrawerGroup candidateGroup = getGroupForCoord(candidate.coord);
                if (candidateGroup == null) continue;

                IDrawerInventory candidateInventory = candidateGroup.getDrawerInventory();
                if (candidateInventory.canInsertItem(candidate.slot, stack)) {
                    IDrawer drawer = candidateGroup.getDrawer(candidate.slot);
                    if (drawer instanceof IVoidable && ((IVoidable) drawer).isVoid()) return false;
                    if (drawer.getRemainingCapacity() > 0) return false;
                }
            }
        }

        return inventory.canInsertItem(getLocalInvSlot(slot), stack);
    }

    @Override
    public boolean canExtractItem(int slot, ItemStack stack, int side) {
        IDrawerInventory inventory = getDrawerInventory(slot);
        if (inventory == null) return false;

        return inventory.canExtractItem(getLocalInvSlot(slot), stack);
    }

    @Override
    public int getSizeInventory() {
        return inventorySlots.length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        IDrawerInventory inventory = getDrawerInventory(slot);
        if (inventory == null) return null;

        return inventory.getStackInSlot(getLocalInvSlot(slot));
    }

    @Override
    public ItemStack decrStackSize(int slot, int count) {
        IDrawerInventory inventory = getDrawerInventory(slot);
        if (inventory == null) return null;

        return inventory.decrStackSize(getLocalInvSlot(slot), count);
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        IDrawerInventory inventory = getDrawerInventory(slot);
        if (inventory == null) return null;

        return inventory.getStackInSlotOnClosing(getLocalInvSlot(slot));
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        IDrawerInventory inventory = getDrawerInventory(slot);
        if (inventory == null) return;

        inventory.setInventorySlotContents(getLocalInvSlot(slot), stack);
        inventory.markDirty();
    }

    public void setInventoryName(String name) {
        customName = name;
    }

    @Override
    public String getInventoryName() {
        return hasCustomInventoryName() ? customName : "storageDrawers.container.controller";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return customName != null && customName.length() > 0;
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return false;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        IDrawerInventory inventory = getDrawerInventory(slot);
        if (inventory == null) return false;

        if (slot >= invSlotList.size()) return false;

        SlotRecord record = invSlotList.get(slot);
        List<SlotRecord> primaryRecords = invPrimaryLookup.getEntries(stack.getItem(), stack.getItemDamage());
        if (primaryRecords != null && !primaryRecords.contains(record)) {
            for (SlotRecord candidate : primaryRecords) {
                IDrawerGroup candidateGroup = getGroupForCoord(candidate.coord);
                if (candidateGroup == null) continue;

                IDrawerInventory candidateInventory = candidateGroup.getDrawerInventory();
                if (candidateInventory.isItemValidForSlot(candidate.slot, stack)) {
                    IDrawer drawer = candidateGroup.getDrawer(candidate.slot);
                    if (drawer instanceof IVoidable && ((IVoidable) drawer).isVoid()) return false;
                    if (drawer.getRemainingCapacity() > 0) return false;
                }
            }
        }

        return inventory.isItemValidForSlot(getLocalInvSlot(slot), stack);
    }

    private class DrawerStackIterator implements Iterable<Integer> {

        private ItemStack stack;
        private boolean strict;
        private boolean insert;

        public DrawerStackIterator(ItemStack stack, boolean strict, boolean insert) {
            this.stack = stack;
            this.strict = strict;
            this.insert = insert;
        }

        @Override
        public Iterator<Integer> iterator() {
            if (this.stack == null) return new ArrayList<Integer>(0).iterator();

            return new Iterator<Integer>() {

                List<SlotRecord> primaryRecords = drawerPrimaryLookup
                        .getEntries(stack.getItem(), stack.getItemDamage());
                Iterator<SlotRecord> iter1;
                int index2;
                Integer nextSlot = null;

                @Override
                public boolean hasNext() {
                    if (nextSlot == null) advance();
                    return nextSlot != null;
                }

                @Override
                public Integer next() {
                    if (nextSlot == null) advance();

                    Integer slot = nextSlot;
                    nextSlot = null;
                    return slot;
                }

                private void advance() {
                    if (iter1 == null && primaryRecords != null && primaryRecords.size() > 0)
                        iter1 = primaryRecords.iterator();

                    if (iter1 != null) {
                        while (iter1.hasNext()) {
                            SlotRecord candidate = iter1.next();
                            IDrawerGroup candidateGroup = getGroupForCoord(candidate.coord);
                            if (candidateGroup == null) continue;

                            IDrawer drawer = candidateGroup.getDrawer(candidate.slot);

                            if (insert) {
                                boolean voiding = (drawer instanceof IVoidable) ? ((IVoidable) drawer).isVoid() : false;
                                if (!(drawer.canItemBeStored(stack)
                                        && (drawer.isEmpty() || drawer.getRemainingCapacity() > 0 || voiding)))
                                    continue;
                            } else {
                                if (!(drawer.canItemBeExtracted(stack) && drawer.getStoredItemCount() > 0)) continue;
                            }

                            int slot = drawerSlotList.indexOf(candidate);
                            if (slot > -1) {
                                nextSlot = slot;
                                return;
                            }
                        }
                    }

                    for (; index2 < drawerSlots.length; index2++) {
                        int slot = drawerSlots[index2];
                        if (!isDrawerEnabled(slot)) continue;

                        IDrawer drawer = getDrawer(slot);
                        if (strict) {
                            ItemStack proto = drawer.getStoredItemPrototype();
                            if (proto != null && !proto.isItemEqual(stack)) continue;
                        }

                        if (insert) {
                            boolean voiding = (drawer instanceof IVoidable) ? ((IVoidable) drawer).isVoid() : false;
                            if (!(drawer.canItemBeStored(stack)
                                    && (drawer.isEmpty() || drawer.getRemainingCapacity() > 0 || voiding)))
                                continue;
                        } else {
                            if (!(drawer.canItemBeExtracted(stack) && drawer.getStoredItemCount() > 0)) continue;
                        }

                        SlotRecord record = drawerSlotList.get(slot);
                        if (primaryRecords != null && primaryRecords.contains(record)) continue;

                        nextSlot = slot;
                        index2++;
                        return;
                    }
                }
            };
        }
    };

    public Iterable<Integer> enumerateDrawersForInsertion(ItemStack stack, boolean strict) {
        return new DrawerStackIterator(stack, strict, true);
    }

    public Iterable<Integer> enumerateDrawersForExtraction(ItemStack stack, boolean strict) {
        return new DrawerStackIterator(stack, strict, false);
    }
}
