package com.thetowers.lobby;

import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * Marks and recognizes "protected" lobby items via a hidden NBT tag, so they
 * cannot be dropped, moved into containers, crafted, placed in item frames or
 * used in anvils. The tag is invisible to the player.
 *
 * <p>1.12.2 (v1_12_R1): the Bukkit {@link ItemStack} is converted to its NMS
 * counterpart via {@code CraftItemStack.asNMSCopy}, an NBT tag
 * {@code thetowers.lobby} is set on its compound, and the item is converted
 * back with {@code asBukkitCopy}. The check reads the tag the same way. All by
 * reflection so the plugin stays version-agnostic at compile time.
 */
public final class ItemTagger {

    /** NBT key that marks a protected lobby item. */
    private static final String TAG = "thetowers.lobby";

    private static boolean initialized;
    private static boolean available;
    private static Class<?> craftItemStackClass;
    private static Method asNmsCopy;
    private static Method asBukkitCopy;
    private static Class<?> nmsItemStackClass;
    private static Class<?> nbtTagCompoundClass;
    private static Method getTag;
    private static Method setTag;
    private static Method nbtSetBoolean;
    private static Method nbtGetBoolean;
    private static Method nbtHasKey;
    private static Method nbtNew; // newInstance

    private ItemTagger() {
    }

    private static boolean init() {
        if (initialized) {
            return available;
        }
        initialized = true;
        try {
            String base = "net.minecraft.server."
                    + Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3] + ".";
            String cbBase = Bukkit.getServer().getClass().getPackage().getName();
            craftItemStackClass = Class.forName(cbBase + ".inventory.CraftItemStack");
            asNmsCopy = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
            nmsItemStackClass = Class.forName(base + "ItemStack");
            asBukkitCopy = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStackClass);
            nbtTagCompoundClass = Class.forName(base + "NBTTagCompound");
            getTag = nmsItemStackClass.getMethod("getTag");
            setTag = nmsItemStackClass.getMethod("setTag", nbtTagCompoundClass);
            nbtSetBoolean = nbtTagCompoundClass.getMethod("setBoolean", String.class, boolean.class);
            nbtGetBoolean = nbtTagCompoundClass.getMethod("getBoolean", String.class);
            nbtHasKey = nbtTagCompoundClass.getMethod("hasKey", String.class);
            nbtNew = null; // use getDeclaredConstructor().newInstance()
            available = true;
        } catch (Throwable e) {
            available = false;
            Bukkit.getLogger().warning("[TheTowers] ItemTagger unavailable: " + e);
        }
        return available;
    }

    /** Returns a copy of the item marked as a protected lobby item. */
    public static ItemStack mark(ItemStack item) {
        if (item == null || !init()) {
            return item;
        }
        try {
            Object nms = asNmsCopy.invoke(null, item);
            if (nms == null) {
                return item;
            }
            Object tag = getTag.invoke(nms);
            if (tag == null) {
                tag = nbtTagCompoundClass.getDeclaredConstructor().newInstance();
            }
            nbtSetBoolean.invoke(tag, TAG, Boolean.TRUE);
            setTag.invoke(nms, tag);
            return (ItemStack) asBukkitCopy.invoke(null, nms);
        } catch (Throwable e) {
            return item;
        }
    }

    /** True if the item carries the protected lobby-item NBT tag. */
    public static boolean isProtected(ItemStack item) {
        if (item == null || !init()) {
            return false;
        }
        try {
            Object nms = asNmsCopy.invoke(null, item);
            if (nms == null) {
                return false;
            }
            Object tag = getTag.invoke(nms);
            if (tag == null) {
                return false;
            }
            return ((Boolean) nbtGetBoolean.invoke(tag, TAG)).booleanValue()
                    || ((Boolean) nbtHasKey.invoke(tag, TAG)).booleanValue();
        } catch (Throwable e) {
            return false;
        }
    }
}
