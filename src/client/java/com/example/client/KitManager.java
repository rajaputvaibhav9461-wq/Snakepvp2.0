package com.snakepvp;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.function.Predicate;
import java.util.function.ToIntFunction;

public class KitManager implements ClientModInitializer {
    static final double RANGE = 100.0;

    KeyMapping toggleKey;
    KeyMapping menuKey;
    final SwordPvp sword = new SwordPvp();
    final NethpotPvp nethpot = new NethpotPvp();
    final MacePvp mace = new MacePvp();
    final TntMinecartPvp tnt = new TntMinecartPvp();
    final ElytraMacePvp elytraMace = new ElytraMacePvp();
    final PCrtingPvp pcrt = new PCrtingPvp();
    final SmpPvp smp = new SmpPvp();
    final BreachSweepPvp breach = new BreachSweepPvp();
    final CrystalPvp crystal = new CrystalPvp();
    // 0 OFF, 1 SWORD, 2 NETHPOT, 3 MACE, 4 TNT_CART, 5 ELYTRA_MACE, 6 P_CRTING, 7 SMP, 8 BREACH_SWEEP, 9 CRYSTAL
    int mode = 0;
    static Player locked = null;

    @Override
    public void onInitializeClient() {
        FriendList.load();
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.snakepvp.toggle", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z, "category.snakepvp"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.snakepvp.menu", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_C, "category.snakepvp"));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            mode = 0;
            locked = null;
            return;
        }

        while (menuKey.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new FriendList.Menu());
        }
        if (mc.screen != null) { // menu khula hai to PvP band
            if (mode != 0) stop(mc, false);
            return;
        }

        while (toggleKey.consumeClick()) {
            if (mode != 0) stop(mc, true);
            else start(mc);
        }
        if (mode == 0) return;

        if (!mc.player.isAlive()) {
            stop(mc, false);
            return;
        }

        Player target = findTarget(mc);
        if (mode == 1) sword.tick(mc, target);
        else if (mode == 2) nethpot.tick(mc, target);
        else if (mode == 3) mace.tick(mc, target);
        else if (mode == 4) tnt.tick(mc, target);
        else if (mode == 5) elytraMace.tick(mc, target);
        else if (mode == 6) pcrt.tick(mc, target);
        else if (mode == 7) smp.tick(mc, target);
        else if (mode == 8) breach.tick(mc, target);
        else if (mode == 9) crystal.tick(mc, target);
    }

    void start(Minecraft mc) {
        String sel = FriendList.selectedPvp;
        if (sel == null) {
            msg(mc, "Pehle C menu me PvP select karo");
            return;
        }
        switch (sel) {
            case "SWORD" -> {
                if (!sword.matches(mc)) {
                    msg(mc, "Inventory me sword nahi hai");
                    return;
                }
                locked = null;
                mode = 1;
                sword.onActivate(mc);
            }
            case "NETHPOT" -> {
                if (!nethpot.matches(mc)) {
                    msg(mc, "Inventory me sword nahi hai");
                    return;
                }
                locked = null;
                mode = 2;
                nethpot.onActivate(mc);
            }
            case "MACE" -> {
                if (!mace.matches(mc)) {
                    msg(mc, "Inventory me mace ya wind charge nahi hai");
                    return;
                }
                locked = null;
                mode = 3;
                mace.onActivate(mc);
            }
            case "TNT_CART" -> {
                if (!tnt.matches(mc)) {
                    msg(mc, "Inventory me tnt minecart, rail, flint, crossbow/bow ya arrow kam hai");
                    return;
                }
                locked = null;
                mode = 4;
                tnt.onActivate(mc);
            }
            case "ELYTRA_MACE" -> {
                if (!elytraMace.matches(mc)) {
                    msg(mc, "Inventory me elytra, mace, chestplate ya (rocket / pearl+wind charge) kam hai");
                    return;
                }
                locked = null;
                mode = 5;
                elytraMace.onActivate(mc);
            }
            case "P_CRTING" -> {
                if (!pcrt.matches(mc)) {
                    msg(mc, "Inventory me sword nahi hai");
                    return;
                }
                locked = null;
                mode = 6;
                pcrt.onActivate(mc);
            }
            case "SMP" -> {
                if (!smp.matches(mc)) {
                    msg(mc, "Inventory me sword nahi hai");
                    return;
                }
                locked = null;
                mode = 7;
                smp.onActivate(mc);
            }
            case "BREACH_SWEEP" -> {
                if (!breach.matches(mc)) {
                    msg(mc, "Inventory me sword ya breach enchant wala mace nahi hai");
                    return;
                }
                locked = null;
                mode = 8;
                breach.onActivate(mc);
            }
            case "CRYSTAL" -> {
                if (!crystal.matches(mc)) {
                    msg(mc, "Inventory me sword, obsidian ya end crystal nahi hai");
                    return;
                }
                locked = null;
                mode = 9;
                crystal.onActivate(mc);
            }
            default -> {
                msg(mc, "Ye PvP abhi repo me nahi hai: " + sel);
                return;
            }
        }
        msg(mc, "PvP ON: " + sel);
    }

    void stop(Minecraft mc, boolean show) {
        release(mc);
        mode = 0;
        locked = null;
        if (show) msg(mc, "PvP: OFF");
    }

    void release(Minecraft mc) {
        if (mode == 1) sword.onDeactivate(mc);
        else if (mode == 2) nethpot.onDeactivate(mc);
        else if (mode == 3) mace.onDeactivate(mc);
        else if (mode == 4) tnt.onDeactivate(mc);
        else if (mode == 5) elytraMace.onDeactivate(mc);
        else if (mode == 6) pcrt.onDeactivate(mc);
        else if (mode == 7) smp.onDeactivate(mc);
        else if (mode == 8) breach.onDeactivate(mc);
        else if (mode == 9) crystal.onDeactivate(mc);
    }

    static void msg(Minecraft mc, String text) {
        mc.player.displayClientMessage(Component.literal(text), true);
    }

    // ---------------------------------------------------------------- TARGET (lock)
    // Ek baar lock hua to marne ya range se bahar jaane tak same rahega.
    Player findTarget(Minecraft mc) {
        if (locked != null && validTarget(mc, locked)) return locked;
        locked = null;

        Player me = mc.player;
        Player best = null;
        double bestD = RANGE * RANGE;
        for (Player p : mc.level.players()) {
            if (!validTarget(mc, p)) continue;
            double d = p.distanceToSqr(me);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        locked = best;
        return best;
    }

    static boolean validTarget(Minecraft mc, Player p) {
        Player me = mc.player;
        if (p == me || p.isSpectator() || !p.isAlive() || p.isRemoved()) return false;
        if (FriendList.isSafe(p.getName().getString())) return false;
        return p.distanceToSqr(me) <= RANGE * RANGE;
    }

    // ---------------------------------------------------------------- INVENTORY HELPERS
    static boolean isSword(ItemStack s) { return s.is(ItemTags.SWORDS); }
    static boolean isAxe(ItemStack s) { return s.is(ItemTags.AXES); }
    static boolean isWeb(ItemStack s) { return s.is(Items.COBWEB); }

    static int swordScore(ItemStack s) {
        if (s.is(Items.NETHERITE_SWORD)) return 6;
        if (s.is(Items.DIAMOND_SWORD)) return 5;
        if (s.is(Items.IRON_SWORD)) return 4;
        if (s.is(Items.STONE_SWORD)) return 3;
        if (s.is(Items.GOLDEN_SWORD)) return 2;
        return 1;
    }

    static int zeroScore(ItemStack s) { return 0; }

    static boolean hasFoodComponent(ItemStack s) {
        return s.get(DataComponents.FOOD) != null;
    }

    static boolean isFood(Player p, ItemStack s) {
        FoodProperties f = s.get(DataComponents.FOOD);
        if (f == null) return false;
        if (s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO)
                || s.is(Items.PUFFERFISH) || s.is(Items.CHORUS_FRUIT)) return false;
        return p.canEat(f.canAlwaysEat());
    }

    static int foodScore(ItemStack s) {
        if (s.is(Items.ENCHANTED_GOLDEN_APPLE)) return 110;
        if (s.is(Items.GOLDEN_APPLE)) return 100;
        FoodProperties f = s.get(DataComponents.FOOD);
        return f == null ? 0 : f.nutrition();
    }

    /** Poori inventory me best item ka index (0-35) ya -1. Tie me hotbar ko preference. */
    static int findBest(Player p, Predicate<ItemStack> filter, ToIntFunction<ItemStack> score) {
        Inventory inv = p.getInventory();
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !filter.test(s)) continue;
            int sc = score.applyAsInt(s) * 2 + (i < 9 ? 1 : 0);
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        return best;
    }

    /** Item ko hotbar me laake hath me pakadta hai. */
    static boolean equip(Minecraft mc, int idx) {
        if (idx < 0 || mc.player == null || mc.gameMode == null) return false;
        Inventory inv = mc.player.getInventory();
        if (idx < 9) {
            inv.selected = idx;
            return true;
        }
        int slot = freeHotbarSlot(inv);
        mc.gameMode.handleInventoryMouseClick(
                mc.player.inventoryMenu.containerId, idx, slot, ClickType.SWAP, mc.player);
        inv.selected = slot;
        return true;
    }

    /** Ye items hotbar se hataye nahi jate (naya item lane ke liye). */
    static boolean isKeeper(ItemStack s) {
        return isSword(s) || isAxe(s) || hasFoodComponent(s) || s.is(Items.MACE)
                || s.is(Items.WIND_CHARGE) || s.is(Items.ELYTRA) || s.is(Items.ENDER_PEARL)
                || s.is(Items.FIREWORK_ROCKET) || s.is(Items.SHIELD)
                || s.is(Items.TOTEM_OF_UNDYING) || s.is(Items.SPLASH_POTION)
                || s.is(Items.END_CRYSTAL) || s.is(Items.OBSIDIAN);
    }

    static int freeHotbarSlot(Inventory inv) {
        for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) return i;
        for (int i = 8; i >= 0; i--) {
            if (!isKeeper(inv.getItem(i))) return i;
        }
        return 8;
    }

    // ---------------------------------------------------------------- MOVE / AIM HELPERS
    static void setRotation(Player me, float yaw, float pitch) {
        pitch = Mth.clamp(pitch, -90f, 90f);
        me.setYRot(yaw);
        me.setXRot(pitch);
        me.setYHeadRot(yaw);
        me.yRotO = yaw;
        me.xRotO = pitch;
    }

    static void aimAt(Player me, Vec3 p) {
        Vec3 eye = me.getEyePosition();
        double dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        setRotation(me, yaw, pitch);
    }

    static void stopMovement(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    static void releaseAll(Minecraft mc) {
        stopMovement(mc);
        mc.options.keyUse.setDown(false);
        mc.options.keyAttack.setDown(false);
    }

    static boolean isWebBlock(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.COBWEB);
    }

    static boolean inWeb(Level level, Player me) {
        BlockPos feet = BlockPos.containing(me.getX(), me.getY(), me.getZ());
        return isWebBlock(level, feet) || isWebBlock(level, feet.above());
    }

    /** (wx, wz) direction me aage cobweb hai? */
    static boolean webAhead(Level level, Player me, double wx, double wz) {
        double len = Math.sqrt(wx * wx + wz * wz);
        if (len < 1e-6) return false;
        wx /= len;
        wz /= len;
        int y = Mth.floor(me.getY());
        for (double step : new double[] { 0.7, 1.4 }) {
            int bx = Mth.floor(me.getX() + wx * step);
            int bz = Mth.floor(me.getZ() + wz * step);
            if (isWebBlock(level, new BlockPos(bx, y, bz)) || isWebBlock(level, new BlockPos(bx, y + 1, bz)))
                return true;
        }
        return false;
    }

    /** Point se hitbox tak ki doori (reach check). */
    static double boxDist(Vec3 p, AABB b) {
        double dx = Math.max(Math.max(b.minX - p.x, 0), p.x - b.maxX);
        double dy = Math.max(Math.max(b.minY - p.y, 0), p.y - b.maxY);
        double dz = Math.max(Math.max(b.minZ - p.z, 0), p.z - b.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    }
