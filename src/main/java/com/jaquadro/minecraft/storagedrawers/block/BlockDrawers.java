package com.jaquadro.minecraft.storagedrawers.block;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.BlockEnderChest;
import net.minecraft.block.BlockWood;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.client.resources.IResource;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.logging.log4j.Level;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jaquadro.minecraft.storagedrawers.StorageDrawers;
import com.jaquadro.minecraft.storagedrawers.api.pack.BlockConfiguration;
import com.jaquadro.minecraft.storagedrawers.api.pack.BlockType;
import com.jaquadro.minecraft.storagedrawers.api.security.ISecurityProvider;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.INetworked;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.LockAttribute;
import com.jaquadro.minecraft.storagedrawers.block.tile.TileEntityDrawers;
import com.jaquadro.minecraft.storagedrawers.block.tile.TileEntityDrawersStandard;
import com.jaquadro.minecraft.storagedrawers.core.ModCreativeTabs;
import com.jaquadro.minecraft.storagedrawers.core.ModItems;
import com.jaquadro.minecraft.storagedrawers.core.handlers.GuiHandler;
import com.jaquadro.minecraft.storagedrawers.item.ItemPersonalKey;
import com.jaquadro.minecraft.storagedrawers.item.ItemTrim;
import com.jaquadro.minecraft.storagedrawers.item.ItemUpgrade;
import com.jaquadro.minecraft.storagedrawers.item.ItemUpgradeCreative;
import com.jaquadro.minecraft.storagedrawers.network.BlockClickMessage;
import com.jaquadro.minecraft.storagedrawers.security.SecurityManager;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class BlockDrawers extends BlockContainer implements IExtendedBlockClickHandler, INetworked {

    private static final ResourceLocation blockConfig = new ResourceLocation(
            StorageDrawers.MOD_ID + ":textures/blocks/block_config.mcmeta");

    public final boolean halfDepth;
    public final int drawerCount;

    private float trimWidth = 0.0625f;
    private float trimDepth = 0.0625f;
    private float indStart = 0;
    private float indEnd = 0;
    private int indSteps = 0;

    private String blockConfigName;

    @SideOnly(Side.CLIENT)
    protected IIcon[] iconSide;

    @SideOnly(Side.CLIENT)
    protected IIcon[] iconSideV;

    @SideOnly(Side.CLIENT)
    protected IIcon[] iconSideH;

    @SideOnly(Side.CLIENT)
    protected IIcon[] iconFront1;

    @SideOnly(Side.CLIENT)
    protected IIcon[] iconFront2;

    @SideOnly(Side.CLIENT)
    protected IIcon[] iconFront4;

    @SideOnly(Side.CLIENT)
    protected IIcon[] iconTrim;

    @SideOnly(Side.CLIENT)
    private IIcon[] iconOverlay;

    @SideOnly(Side.CLIENT)
    private IIcon[] iconOverlayV;

    @SideOnly(Side.CLIENT)
    private IIcon[] iconOverlayH;

    @SideOnly(Side.CLIENT)
    private IIcon[] iconOverlayTrim;

    @SideOnly(Side.CLIENT)
    private IIcon[] iconIndicator1;

    @SideOnly(Side.CLIENT)
    private IIcon[] iconIndicator2;

    @SideOnly(Side.CLIENT)
    private IIcon[] iconIndicator4;

    @SideOnly(Side.CLIENT)
    private IIcon iconLock;

    @SideOnly(Side.CLIENT)
    private IIcon iconClaim;

    @SideOnly(Side.CLIENT)
    private IIcon iconClaimLock;

    @SideOnly(Side.CLIENT)
    private IIcon iconVoid;

    @SideOnly(Side.CLIENT)
    private IIcon iconTaped;

    private long ignoreEventTime;

    public BlockDrawers(String blockName, int drawerCount, boolean halfDepth) {
        this(Material.wood, blockName, drawerCount, halfDepth);
    }

    protected BlockDrawers(Material material, String blockName, int drawerCount, boolean halfDepth) {
        super(material);

        this.drawerCount = drawerCount;
        this.halfDepth = halfDepth;
        this.useNeighborBrightness = true;

        setCreativeTab(ModCreativeTabs.tabStorageDrawers);
        setHardness(5f);
        setStepSound(Block.soundTypeWood);
        setBlockName(blockName);
        setConfigName(blockName);
        setLightOpacity(255);
    }

    public boolean retrimBlock(World world, int x, int y, int z, ItemStack prototype) {
        if (retrimType() == null) return false;

        Block protoBlock = Block.getBlockFromItem(prototype.getItem());
        int protoMeta = prototype.getItemDamage();

        BlockConfiguration config = BlockConfiguration.by(retrimType(), drawerCount, halfDepth);

        Block plankBlock = StorageDrawers.blockRegistry.getPlankBlock(BlockConfiguration.Trim, protoBlock, protoMeta);
        int plankMeta = StorageDrawers.blockRegistry.getPlankMeta(BlockConfiguration.Trim, protoBlock, protoMeta);

        Block newBlock = StorageDrawers.blockRegistry.getBlock(config, plankBlock, plankMeta);
        int newMeta = StorageDrawers.blockRegistry.getMeta(config, plankBlock, plankMeta);

        if (newBlock == null) return false;

        TileEntityDrawers tile = getTileEntity(world, x, y, z);
        if (newBlock == this && newMeta == world.getBlockMetadata(x, y, z) && !tile.shouldHideUpgrades()) {
            tile.setShouldHideUpgrades(true);
            return true;
        }

        if (newBlock == this) world.setBlockMetadataWithNotify(x, y, z, newMeta, 3);
        else {

            TileEntity newDrawer = createNewTileEntity(world, newMeta);

            NBTTagCompound tag = new NBTTagCompound();
            tile.writeToNBT(tag);
            newDrawer.readFromNBT(tag);

            world.removeTileEntity(x, y, z);
            world.setBlockToAir(x, y, z);

            world.setBlock(x, y, z, newBlock, newMeta, 3);
            world.setTileEntity(x, y, z, newDrawer);
        }

        return true;
    }

    public BlockType retrimType() {
        return BlockType.Drawers;
    }

    public float getTrimWidth() {
        return trimWidth;
    }

    public float getTrimDepth() {
        return trimDepth;
    }

    public float getIndStart() {
        return indStart;
    }

    public float getIndEnd() {
        return indEnd;
    }

    public int getIndSteps() {
        return indSteps;
    }

    public BlockDrawers setConfigName(String name) {
        blockConfigName = name;
        return this;
    }

    public String getConfigName() {
        return blockConfigName;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public int getRenderType() {
        return StorageDrawers.proxy.drawersRenderID;
    }

    @Override
    public void setBlockBoundsBasedOnState(IBlockAccess blockAccess, int x, int y, int z) {
        TileEntityDrawers tile = getTileEntity(blockAccess, x, y, z);
        if (tile == null) {
            setBlockBounds(0, 0, 0, 1, 1, 1);
            return;
        }

        float depth = halfDepth ? .5f : 1;
        switch (tile.getDirection()) {
            case 2:
                setBlockBounds(0, 0, 1 - depth, 1, 1, 1);
                break;
            case 3:
                setBlockBounds(0, 0, 0, 1, 1, depth);
                break;
            case 4:
                setBlockBounds(1 - depth, 0, 0, 1, 1, 1);
                break;
            case 5:
                setBlockBounds(0, 0, 0, depth, 1, 1);
                break;
        }
    }

    @Override
    public void setBlockBoundsForItemRender() {
        if (halfDepth) setBlockBounds(0, 0, 0, 1, 1, .5f);
        else setBlockBounds(0, 0, 0, 1, 1, 1);
    }

    @Override
    public void addCollisionBoxesToList(World world, int x, int y, int z, AxisAlignedBB aabb, List list,
            Entity entity) {
        setBlockBoundsBasedOnState(world, x, y, z);
        super.addCollisionBoxesToList(world, x, y, z, aabb, list, entity);
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase entity, ItemStack itemStack) {
        TileEntityDrawers tile = getTileEntitySafe(world, x, y, z);
        if (tile.getDirection() > 1) return;

        int quadrant = MathHelper.floor_double((entity.rotationYaw * 4f / 360f) + .5) & 3;
        switch (quadrant) {
            case 0:
                tile.setDirection(2);
                break;
            case 1:
                tile.setDirection(5);
                break;
            case 2:
                tile.setDirection(3);
                break;
            case 3:
                tile.setDirection(4);
                break;
        }

        if (itemStack.hasDisplayName()) tile.setInventoryName(itemStack.getDisplayName());

        if (world.isRemote) {
            tile.invalidate();
            world.markBlockForUpdate(x, y, z);
        }
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
            float hitY, float hitZ) {
        if (world.isRemote && Minecraft.getMinecraft().getSystemTime() == ignoreEventTime) {
            ignoreEventTime = 0;
            return false;
        }

        TileEntityDrawers tileDrawers = getTileEntitySafe(world, x, y, z);
        ItemStack item = player.inventory.getCurrentItem();

        if (!SecurityManager.hasAccess(player.getGameProfile(), tileDrawers)) return false;

        if (StorageDrawers.config.cache.debugTrace) {
            FMLLog.log(StorageDrawers.MOD_ID, Level.INFO, "BlockDrawers.onBlockActivated");
            FMLLog.log(StorageDrawers.MOD_ID, Level.INFO, (item == null) ? "  null item" : "  " + item.toString());
        }

        if (item != null && item.getItem() != null) {
            if (item.getItem() instanceof ItemTrim && player.isSneaking()) {
                if (!retrimBlock(world, x, y, z, item)) return false;

                if (player != null && !player.capabilities.isCreativeMode) {
                    if (--item.stackSize <= 0)
                        player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                }

                return true;
            }
            /**
             * Gee, it'd be nice if we could make all of these Items descend from one thing, almost like children and it
             * would great if we could just find if it was an instance of said thing. Crazy concept!
             */
            else if (item.getItem() == ModItems.upgrade || item.getItem() == ModItems.upgradeStatus
                    || item.getItem() == ModItems.upgradeVoid
                    || item.getItem() == ModItems.upgradeCreative
                    || item.getItem() == ModItems.upgradeRedstone
                    || item.getItem() == ModItems.upgradeDowngrade) {
                        if (!tileDrawers.addUpgrade(item)) {
                            player.addChatMessage(new ChatComponentTranslation("storagedrawers.msg.maxUpgrades"));
                            return false;
                        }

                        world.markBlockForUpdate(x, y, z);

                        if (player != null && !player.capabilities.isCreativeMode) {
                            if (--item.stackSize <= 0)
                                player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                        }

                        return true;
                    } else
                if (item.getItem() == ModItems.upgradeLock) {
                    boolean locked = tileDrawers.isLocked(LockAttribute.LOCK_POPULATED);

                    if (locked) {
                        int slot = getDrawerSlot(side, hitX, hitY, hitZ);
                        IDrawer drawer = tileDrawers.getDrawer(slot);
                        ItemStack stack = drawer.getStoredItemPrototype();
                        int count = drawer.getStoredItemCount();

                        if (stack != null && count == 0) {
                            drawer.setStoredItemRedir(null, 0);
                            return true;
                        }
                    }
                    tileDrawers.setLocked(LockAttribute.LOCK_POPULATED, !locked);
                    tileDrawers.setLocked(LockAttribute.LOCK_EMPTY, !locked);

                    return true;
                } else if (item.getItem() == ModItems.shroudKey) {
                    tileDrawers.setIsShrouded(!tileDrawers.isShrouded());
                    return true;
                } else if (item.getItem() == ModItems.quantifyKey) {
                    tileDrawers.setIsQuantified(!tileDrawers.isQuantified());
                } else if (item.getItem() instanceof ItemPersonalKey) {
                    String securityKey = ((ItemPersonalKey) item.getItem())
                            .getSecurityProviderKey(item.getItemDamage());
                    ISecurityProvider provider = StorageDrawers.securityRegistry.getProvider(securityKey);

                    if (tileDrawers.getOwner() == null) {
                        tileDrawers.setOwner(player.getPersistentID());
                        tileDrawers.setSecurityProvider(provider);
                    } else if (SecurityManager.hasOwnership(player.getGameProfile(), tileDrawers)) {
                        tileDrawers.setOwner(null);
                        tileDrawers.setSecurityProvider(null);
                    } else return false;
                    return true;
                } else if (item.getItem() == ModItems.tape) return false;
        } else if (item == null && player.isSneaking()) {
            if (tileDrawers.isSealed()) {
                tileDrawers.setIsSealed(false);
                return true;
            } else if (StorageDrawers.config.cache.enableDrawerUI) {
                player.openGui(StorageDrawers.instance, GuiHandler.drawersGuiID, world, x, y, z);
                return true;
            }
        }

        if (tileDrawers.getDirection() != side) return false;

        if (tileDrawers.isSealed()) return false;

        int slot = getDrawerSlot(side, hitX, hitY, hitZ);
        IDrawer drawer = tileDrawers.getDrawer(slot);
        ItemStack currentStack = drawer.getStoredItemPrototype();

        int countAdded = tileDrawers.interactPutItemsIntoSlot(slot, player);
        if (countAdded > 0 && currentStack != null) world.markBlockForUpdate(x, y, z);

        return true;
    }

    protected int getDrawerSlot(int side, float hitX, float hitY, float hitZ) {
        if (drawerCount == 1) return 0;
        if (drawerCount == 2) return hitTop(hitY) ? 0 : 1;

        if (hitLeft(side, hitX, hitZ)) return hitTop(hitY) ? 0 : 1;
        else return hitTop(hitY) ? 2 : 3;
    }

    protected boolean hitTop(float hitY) {
        return hitY > .5;
    }

    protected boolean hitLeft(int side, float hitX, float hitZ) {
        switch (side) {
            case 2:
                return hitX > .5;
            case 3:
                return hitX < .5;
            case 4:
                return hitZ < .5;
            case 5:
                return hitZ > .5;
            default:
                return true;
        }
    }

    @Override
    public void onBlockClicked(World world, int x, int y, int z, EntityPlayer player) {
        if (world.isRemote) {
            MovingObjectPosition posn = Minecraft.getMinecraft().objectMouseOver;
            float hitX = (float) (posn.hitVec.xCoord - posn.blockX);
            float hitY = (float) (posn.hitVec.yCoord - posn.blockY);
            float hitZ = (float) (posn.hitVec.zCoord - posn.blockZ);

            StorageDrawers.network.sendToServer(
                    new BlockClickMessage(
                            x,
                            y,
                            z,
                            posn.sideHit,
                            hitX,
                            hitY,
                            hitZ,
                            StorageDrawers.config.cache.invertShift));

            if (StorageDrawers.config.cache.debugTrace)
                FMLLog.log(StorageDrawers.MOD_ID, Level.INFO, "BlockDrawers.onBlockClicked with " + posn.toString());
        }
    }

    @Override
    public void onBlockClicked(World world, int x, int y, int z, EntityPlayer player, int side, float hitX, float hitY,
            float hitZ, boolean invertShift) {
        if (StorageDrawers.config.cache.debugTrace)
            FMLLog.log(StorageDrawers.MOD_ID, Level.INFO, "IExtendedBlockClickHandler.onBlockClicked");

        if (!player.capabilities.isCreativeMode) {
            PlayerInteractEvent event = ForgeEventFactory
                    .onPlayerInteract(player, PlayerInteractEvent.Action.LEFT_CLICK_BLOCK, x, y, z, side, world);
            if (event.isCanceled()) return;
        }

        TileEntityDrawers tileDrawers = getTileEntitySafe(world, x, y, z);
        if (tileDrawers.getDirection() != side) return;

        if (tileDrawers.isSealed()) return;

        if (!SecurityManager.hasAccess(player.getGameProfile(), tileDrawers)) return;

        int slot = getDrawerSlot(side, hitX, hitY, hitZ);
        IDrawer drawer = tileDrawers.getDrawer(slot);

        ItemStack item = null;
        // if invertSHift is true this will happen when the player is not shifting
        if (player.isSneaking() != invertShift)
            item = tileDrawers.takeItemsFromSlot(slot, drawer.getStoredItemStackSize());
        else item = tileDrawers.takeItemsFromSlot(slot, 1);

        if (StorageDrawers.config.cache.debugTrace)
            FMLLog.log(StorageDrawers.MOD_ID, Level.INFO, (item == null) ? "  null item" : "  " + item.toString());

        if (item != null && item.stackSize > 0) {
            if (!player.inventory.addItemStackToInventory(item)) {
                ForgeDirection dir = ForgeDirection.getOrientation(side);
                dropItemStack(world, x + dir.offsetX, y, z + dir.offsetZ, player, item);
                world.markBlockForUpdate(x, y, z);
            } else world.playSoundEffect(
                    x + .5f,
                    y + .5f,
                    z + .5f,
                    "random.pop",
                    .2f,
                    ((world.rand.nextFloat() - world.rand.nextFloat()) * .7f + 1) * 2);
        }
    }

    @Override
    public boolean rotateBlock(World world, int x, int y, int z, ForgeDirection axis) {
        TileEntityDrawers tile = getTileEntitySafe(world, x, y, z);
        if (tile.isSealed()) {
            dropBlockAsItem(world, x, y, z, world.getBlockMetadata(x, y, z), 0);
            world.setBlockToAir(x, y, z);
            return true;
        }

        if (tile.getDirection() == axis.ordinal()) return false;

        if (axis == ForgeDirection.UP || axis == ForgeDirection.DOWN) return false;

        tile.setDirection(axis.ordinal());

        world.markBlockForUpdate(x, y, z);

        if (world.isRemote) ignoreEventTime = Minecraft.getMinecraft().getSystemTime();

        return true;
    }

    @Override
    public boolean isSideSolid(IBlockAccess world, int x, int y, int z, ForgeDirection side) {
        if (halfDepth) return false;

        if (side == ForgeDirection.DOWN) {
            Block blockUnder = world.getBlock(x, y - 1, z);
            if (blockUnder instanceof BlockChest || blockUnder instanceof BlockEnderChest) return false;
        }

        if (getTileEntity(world, x, y, z) == null) return true;
        if (side.ordinal() != getTileEntity(world, x, y, z).getDirection()) return true;

        return false;
    }

    private void dropItemStack(World world, int x, int y, int z, EntityPlayer player, ItemStack stack) {
        EntityItem entity = new EntityItem(world, x + .5f, y + .5f, z + .5f, stack);
        entity.addVelocity(-entity.motionX, -entity.motionY, -entity.motionZ);
        world.spawnEntityInWorld(entity);
    }

    @Override
    public boolean removedByPlayer(World world, EntityPlayer player, int x, int y, int z) {
        if (world.isRemote && player.capabilities.isCreativeMode) {
            TileEntityDrawers tile = getTileEntity(world, x, y, z);
            MovingObjectPosition posn = Minecraft.getMinecraft().objectMouseOver;

            if (tile.getDirection() == posn.sideHit) {
                onBlockClicked(world, x, y, z, player);
                return false;
            }
        }

        return super.removedByPlayer(world, player, x, y, z);
    }

    private void dropBigStackInWorld(World world, int x, int y, int z, ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return;
        Random rand = world.rand;

        float ex = rand.nextFloat() * .8f + .1f;
        float ey = rand.nextFloat() * .8f + .1f;
        float ez = rand.nextFloat() * .8f + .1f;

        EntityItem entity = new EntityItem(world, x + ex, y + ey, z + ez, stack);
        if (stack.hasTagCompound())
            entity.getEntityItem().setTagCompound((NBTTagCompound) stack.getTagCompound().copy());
        world.spawnEntityInWorld(entity);
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        TileEntityDrawers tile = getTileEntity(world, x, y, z);

        if (tile != null && !tile.isSealed()) {
            for (int i = 0; i < tile.getUpgradeSlotCount(); i++) {
                ItemStack stack = tile.getUpgrade(i);
                if (stack != null) {
                    if (stack.getItem() instanceof ItemUpgradeCreative) continue;
                    dropBlockAsItem(world, x, y, z, stack);
                }
            }

            if (!tile.isVending()) {
                switch (StorageDrawers.config.cache.breakDrawerDropMode) {
                    case "merge":
                        /* Only a minimum number of ItemStack are dropped */
                        for (int i = 0; i < tile.getDrawerCount(); i++) {
                            if (!tile.isDrawerEnabled(i)) continue;
                            IDrawer drawer = tile.getDrawer(i);

                            final ItemStack rawStoredItem = drawer.getStoredItemPrototype();
                            if (rawStoredItem != null && rawStoredItem.isStackable()) {
                                dropBigStackInWorld(world, x, y, z, drawer.getStoredItemCopy());
                                drawer.setStoredItemCount(0);
                            } else {
                                forEachSplitStack(tile, i, stack -> dropStackInBatches(world, x, y, z, stack));
                            }
                        }
                        break;
                    case "destroy":
                        /* Destroy excess items */
                        int maxDropNum = 2048 / tile.getDrawerCount();
                        for (int i = 0; i < tile.getDrawerCount(); i++) {
                            if (!tile.isDrawerEnabled(i)) continue;
                            IDrawer drawer = tile.getDrawer(i);
                            if (drawer.getStoredItemCount() > maxDropNum) drawer.setStoredItemCount(maxDropNum);

                            forEachSplitStack(tile, i, stack -> dropStackInBatches(world, x, y, z, stack));
                        }
                        break;
                    case "cluster":
                        /* System.out.println("hello"); */
                        if (cpw.mods.fml.common.Loader.isModLoaded("Avaritia")) {
                            try {
                                Class<?> itemMatterClusterClass = Class
                                        .forName("fox.spiteful.avaritia.items.ItemMatterCluster");
                                Method method = itemMatterClusterClass.getMethod("makeClusters", List.class);
                                /* May not be used */
                                for (int i = 0; i < tile.getDrawerCount(); i++) {
                                    List<ItemStack> stacks = new ArrayList<>();
                                    forEachSplitStack(tile, i, stacks::add);

                                    List<ItemStack> clusters = (List<ItemStack>) method
                                            .invoke(itemMatterClusterClass, stacks);
                                    for (ItemStack stack : clusters) {
                                        dropStackInBatches(world, x, y, z, stack);
                                    }
                                }
                                break; /* switch */
                            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException
                                    | ClassNotFoundException e) {
                                StorageDrawers.config.cache.breakDrawerDropMode = "default";
                                FMLLog.log(
                                        StorageDrawers.MOD_ID,
                                        Level.INFO,
                                        "Avaritia does not exist or cannot build a cluster!");
                                if (StorageDrawers.config.cache.debugTrace)
                                    FMLLog.log(StorageDrawers.MOD_ID, Level.INFO, e.getMessage());
                                // :P
                            }
                        }
                    case "default":
                    default:
                        for (int i = 0; i < tile.getDrawerCount(); i++) {
                            forEachSplitStack(tile, i, stack -> dropStackInBatches(world, x, y, z, stack));
                        }
                }
            }

            world.func_147453_f(x, y, z, block);
        }

        super.breakBlock(world, x, y, z, block, meta);
    }

    private void forEachSplitStack(TileEntityDrawers tile, int index, Consumer<ItemStack> forEachStack) {
        if (!tile.isDrawerEnabled(index)) return;
        IDrawer drawer = tile.getDrawer(index);

        while (drawer.getStoredItemCount() > 0) {
            ItemStack stack = tile.takeItemsFromSlot(index, drawer.getStoredItemStackSize());
            if (stack == null || stack.stackSize == 0) break;

            forEachStack.accept(stack);
        }
    }

    private void dropStackInBatches(World world, int x, int y, int z, ItemStack stack) {
        Random rand = world.rand;

        float ex = rand.nextFloat() * .8f + .1f;
        float ey = rand.nextFloat() * .8f + .1f;
        float ez = rand.nextFloat() * .8f + .1f;

        EntityItem entity;
        for (; stack.stackSize > 0; world.spawnEntityInWorld(entity)) {
            int stackPartSize = rand.nextInt(21) + 10;
            if (stackPartSize > stack.stackSize) stackPartSize = stack.stackSize;

            stack.stackSize -= stackPartSize;
            entity = new EntityItem(
                    world,
                    x + ex,
                    y + ey,
                    z + ez,
                    new ItemStack(stack.getItem(), stackPartSize, stack.getItemDamage()));

            float motionUnit = .05f;
            entity.motionX = rand.nextGaussian() * motionUnit;
            entity.motionY = rand.nextGaussian() * motionUnit + .2f;
            entity.motionZ = rand.nextGaussian() * motionUnit;

            if (stack.hasTagCompound())
                entity.getEntityItem().setTagCompound((NBTTagCompound) stack.getTagCompound().copy());
        }
    }

    @Override
    public int damageDropped(int meta) {
        return meta;
    }

    @Override
    public boolean removedByPlayer(World world, EntityPlayer player, int x, int y, int z, boolean willHarvest) {
        if (willHarvest) return true;
        return super.removedByPlayer(world, player, x, y, z, willHarvest);
    }

    @Override
    public void harvestBlock(World world, EntityPlayer player, int x, int y, int z, int meta) {
        super.harvestBlock(world, player, x, y, z, meta);
        world.setBlockToAir(x, y, z);
    }

    @Override
    public ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int metadata, int fortune) {
        ItemStack dropStack = getMainDrop(world, x, y, z, metadata);

        ArrayList<ItemStack> drops = new ArrayList<ItemStack>();
        drops.add(dropStack);

        TileEntityDrawers tile = getTileEntity(world, x, y, z);
        if (tile == null || !tile.isSealed()) return drops;

        NBTTagCompound tiledata = new NBTTagCompound();
        tile.writeToNBT(tiledata);

        NBTTagCompound data = dropStack.getTagCompound();
        if (data == null) data = new NBTTagCompound();

        data.setTag("tile", tiledata);
        dropStack.setTagCompound(data);

        return drops;
    }

    protected ItemStack getMainDrop(World world, int x, int y, int z, int metadata) {
        return new ItemStack(Item.getItemFromBlock(this), 1, metadata);
    }

    @Override
    public float getExplosionResistance(Entity par1Entity, World world, int x, int y, int z, double explosionX,
            double explosionY, double explosionZ) {
        TileEntityDrawers tile = getTileEntity(world, x, y, z);
        if (tile != null) {
            for (int slot = 0; slot < 5; slot++) {
                ItemStack stack = tile.getUpgrade(slot);
                if (stack == null || !(stack.getItem() instanceof ItemUpgrade) || stack.getItemDamage() != 4) continue;

                return 1000;
            }
        }

        return super.getExplosionResistance(par1Entity, world, x, y, z, explosionX, explosionY, explosionZ);
    }

    @Override
    public TileEntityDrawers createNewTileEntity(World world, int meta) {
        return new TileEntityDrawersStandard();
    }

    public TileEntityDrawers getTileEntity(IBlockAccess blockAccess, int x, int y, int z) {
        TileEntity tile = blockAccess.getTileEntity(x, y, z);
        return (tile instanceof TileEntityDrawers) ? (TileEntityDrawers) tile : null;
    }

    public TileEntityDrawers getTileEntitySafe(World world, int x, int y, int z) {
        TileEntityDrawers tile = getTileEntity(world, x, y, z);
        if (tile == null) {
            tile = createNewTileEntity(world, world.getBlockMetadata(x, y, z));
            world.setTileEntity(x, y, z, tile);
        }

        return tile;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean addHitEffects(World worldObj, MovingObjectPosition target, EffectRenderer effectRenderer) {
        TileEntity tile = worldObj.getTileEntity(target.blockX, target.blockY, target.blockZ);
        if (tile instanceof TileEntityDrawers) {
            if (((TileEntityDrawers) tile).getDirection() == target.sideHit) return true;
        }

        return super.addHitEffects(worldObj, target, effectRenderer);
    }

    /*
     * @Override
     * @SideOnly(Side.CLIENT) public boolean addDestroyEffects (World world, int x, int y, int z, int meta,
     * EffectRenderer effectRenderer) { TileEntity tile = world.getTileEntity(x, y, z); if (tile instanceof
     * TileEntityDrawers) return super.addDestroyEffects(world, x, y, z, meta, effectRenderer); }
     */

    @Override
    public void getSubBlocks(Item item, CreativeTabs creativeTabs, List list) {
        list.add(new ItemStack(item, 1, 0));

        if (StorageDrawers.config.cache.creativeTabVanillaWoods) {
            for (int i = 1; i < BlockWood.field_150096_a.length; i++) list.add(new ItemStack(item, 1, i));
        }
    }

    @Override
    public boolean canProvidePower() {
        return true;
    }

    @Override
    public int isProvidingWeakPower(IBlockAccess blockAccess, int x, int y, int z, int dir) {
        if (!canProvidePower()) return 0;

        TileEntityDrawers tile = getTileEntity(blockAccess, x, y, z);
        if (tile == null || !tile.isRedstone()) return 0;

        return tile.getRedstoneLevel();
    }

    @Override
    public int isProvidingStrongPower(IBlockAccess blockAccess, int x, int y, int z, int dir) {
        return (dir == 1) ? isProvidingWeakPower(blockAccess, x, y, z, dir) : 0;
    }

    @SideOnly(Side.CLIENT)
    public IIcon getIconTrim(int meta) {
        meta = (meta < 0 || meta >= iconTrim.length) ? 0 : meta;
        return iconTrim[meta];
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        meta = (meta < 0 || meta >= iconSide.length) ? 0 : meta;

        switch (side) {
            case 0:
            case 1:
                return halfDepth ? iconSideH[meta] : iconSide[meta];
            case 2:
            case 3:
                return halfDepth ? iconSideV[meta] : iconSide[meta];
            case 4:
                switch (drawerCount) {
                    case 1:
                        return iconFront1[meta];
                    case 2:
                        return iconFront2[meta];
                    case 4:
                        return iconFront4[meta];
                }
                return null;
            case 5:
                return iconSide[meta];
        }

        return null;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess blockAccess, int x, int y, int z, int side) {
        return getIcon(blockAccess, x, y, z, side, 0);
    }

    @SideOnly(Side.CLIENT)
    public IIcon getOverlayIcon(IBlockAccess blockAccess, int x, int y, int z, int side, int level) {
        if (level == 0) return null;

        return getIcon(blockAccess, x, y, z, side, level);
    }

    @SideOnly(Side.CLIENT)
    public IIcon getOverlayIconTrim(int level) {
        if (level == 0) return null;

        level = (level < 0 || level >= iconOverlayTrim.length) ? 0 : level;

        return iconOverlayTrim[level];
    }

    @SideOnly(Side.CLIENT)
    protected IIcon getIcon(IBlockAccess blockAccess, int x, int y, int z, int side, int level) {
        int meta = blockAccess.getBlockMetadata(x, y, z);
        meta = (meta < 0 || meta >= iconSide.length) ? 0 : meta;
        level = (level < 0 || level >= iconOverlay.length) ? 0 : level;

        TileEntityDrawers tile = getTileEntity(blockAccess, x, y, z);
        if (tile == null || side == tile.getDirection()) {
            if (drawerCount == 1) return iconFront1[meta];
            else if (drawerCount == 2) return iconFront2[meta];
            else return iconFront4[meta];
        }

        switch (side) {
            case 0:
            case 1:
                if (halfDepth) {
                    switch (tile.getDirection()) {
                        case 2:
                        case 3:
                        case 4:
                        case 5:
                            return (level > 0) ? iconOverlayH[level] : iconSideH[meta];
                    }
                }
                break;
            case 2:
            case 3:
                if (halfDepth) {
                    switch (tile.getDirection()) {
                        case 2:
                        case 3:
                            return (level > 0) ? iconOverlay[level] : iconSide[meta];
                        case 4:
                        case 5:
                            return (level > 0) ? iconOverlayV[level] : iconSideV[meta];
                    }
                }
                break;
            case 4:
            case 5:
                if (halfDepth) {
                    switch (tile.getDirection()) {
                        case 2:
                        case 3:
                            return (level > 0) ? iconOverlayV[level] : iconSideV[meta];
                        case 4:
                        case 5:
                            return (level > 0) ? iconOverlay[level] : iconSide[meta];
                    }
                }
                break;
        }

        return (level > 0) ? iconOverlay[level] : iconSide[meta];
    }

    @SideOnly(Side.CLIENT)
    public IIcon getIndicatorIcon(int drawerCount, boolean on) {
        int onIndex = on ? 1 : 0;
        switch (drawerCount) {
            case 1:
                return iconIndicator1[onIndex];
            case 2:
                return iconIndicator2[onIndex];
            case 4:
                return iconIndicator4[onIndex];
        }

        return null;
    }

    @SideOnly(Side.CLIENT)
    public IIcon getLockIcon(boolean locked, boolean claimed) {
        if (locked && claimed) return iconClaimLock;
        else if (locked) return iconLock;
        else if (claimed) return iconClaim;
        else return null;
    }

    @SideOnly(Side.CLIENT)
    public IIcon getVoidIcon() {
        return iconVoid;
    }

    @SideOnly(Side.CLIENT)
    public IIcon getTapeIcon() {
        return iconTaped;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        String[] subtex = BlockWood.field_150096_a;

        iconSide = new IIcon[subtex.length];
        iconSideH = new IIcon[subtex.length];
        iconSideV = new IIcon[subtex.length];
        iconFront1 = new IIcon[subtex.length];
        iconFront2 = new IIcon[subtex.length];
        iconFront4 = new IIcon[subtex.length];
        iconTrim = new IIcon[subtex.length];

        for (int i = 0; i < subtex.length; i++) {
            iconFront1[i] = register.registerIcon(StorageDrawers.MOD_ID + ":drawers_" + subtex[i] + "_front_1");
            iconFront2[i] = register.registerIcon(StorageDrawers.MOD_ID + ":drawers_" + subtex[i] + "_front_2");
            iconFront4[i] = register.registerIcon(StorageDrawers.MOD_ID + ":drawers_" + subtex[i] + "_front_4");
            iconSide[i] = register.registerIcon(StorageDrawers.MOD_ID + ":drawers_" + subtex[i] + "_side");
            iconSideV[i] = register.registerIcon(StorageDrawers.MOD_ID + ":drawers_" + subtex[i] + "_side_v");
            iconSideH[i] = register.registerIcon(StorageDrawers.MOD_ID + ":drawers_" + subtex[i] + "_side_h");
            iconTrim[i] = register.registerIcon(StorageDrawers.MOD_ID + ":drawers_" + subtex[i] + "_trim");
        }

        iconTaped = register.registerIcon(StorageDrawers.MOD_ID + ":tape");

        String[] overlays = new String[] { null, null, "iron", "gold", "obsidian", "diamond", "emerald", "ruby",
                "tanzanite" };

        iconOverlay = new IIcon[overlays.length];
        iconOverlayH = new IIcon[overlays.length];
        iconOverlayV = new IIcon[overlays.length];
        iconOverlayTrim = new IIcon[overlays.length];

        for (int i = 2; i < overlays.length; i++) {
            iconOverlay[i] = register.registerIcon(StorageDrawers.MOD_ID + ":overlay_" + overlays[i]);
            iconOverlayV[i] = register.registerIcon(StorageDrawers.MOD_ID + ":overlay_" + overlays[i] + "_v");
            iconOverlayH[i] = register.registerIcon(StorageDrawers.MOD_ID + ":overlay_" + overlays[i] + "_h");
            iconOverlayTrim[i] = register.registerIcon(StorageDrawers.MOD_ID + ":overlay_" + overlays[i] + "_trim");
        }

        iconIndicator1 = new IIcon[2];
        iconIndicator2 = new IIcon[2];
        iconIndicator4 = new IIcon[2];

        iconIndicator1[0] = register.registerIcon(StorageDrawers.MOD_ID + ":indicator/indicator_1_off");
        iconIndicator1[1] = register.registerIcon(StorageDrawers.MOD_ID + ":indicator/indicator_1_on");
        iconIndicator2[0] = register.registerIcon(StorageDrawers.MOD_ID + ":indicator/indicator_2_off");
        iconIndicator2[1] = register.registerIcon(StorageDrawers.MOD_ID + ":indicator/indicator_2_on");
        iconIndicator4[0] = register.registerIcon(StorageDrawers.MOD_ID + ":indicator/indicator_4_off");
        iconIndicator4[1] = register.registerIcon(StorageDrawers.MOD_ID + ":indicator/indicator_4_on");

        iconLock = register.registerIcon(StorageDrawers.MOD_ID + ":indicator/lock_icon");
        iconClaim = register.registerIcon(StorageDrawers.MOD_ID + ":indicator/claim_icon");
        iconClaimLock = register.registerIcon(StorageDrawers.MOD_ID + ":indicator/claim_lock_icon");
        iconVoid = register.registerIcon(StorageDrawers.MOD_ID + ":indicator/void_icon");

        loadBlockConfig();
    }

    @SideOnly(Side.CLIENT)
    protected void loadBlockConfig() {
        try {
            IResource configResource = Minecraft.getMinecraft().getResourceManager().getResource(blockConfig);
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(configResource.getInputStream()));
                JsonObject root = (new JsonParser()).parse(reader).getAsJsonObject();

                JsonObject entry = root.getAsJsonObject(getConfigName());
                if (entry != null) {
                    if (entry.has("trimWidth")) trimWidth = entry.get("trimWidth").getAsFloat();
                    if (entry.has("trimDepth")) trimDepth = entry.get("trimDepth").getAsFloat();
                    if (entry.has("indStart")) indStart = entry.get("indStart").getAsFloat();
                    if (entry.has("indEnd")) indEnd = entry.get("indEnd").getAsFloat();
                    if (entry.has("indSteps")) indSteps = entry.get("indSteps").getAsInt();
                }
            } finally {
                IOUtils.closeQuietly(reader);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
