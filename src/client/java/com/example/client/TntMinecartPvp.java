package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

public class TntMinecartPvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final float LOW_HP = 6.0f;          // 6 HP = 3 heart pe bhaag ke heal
    static final float HEAL_DONE_HP = 14.0f;   // itna HP hone pe wapas fight
    static final double REACH = 2.95;          // sword hit range
    static final double FLEE_SAFE_DIST = 10.0; // itni door jaake khana khayega
    static final double PLACE_RANGE = 4.4;     // block rakhne ki range
    static final double STAND_DIST = 6.0;      // cart se itni door se shoot (kam = tumhe bhi dhamaka lagega)
    static final double MIN_STAND = 3.5;       // isse kam nahi
    static final double MELEE_DIST = 4.0;      // enemy itna paas + sword/axe/mace = sword fight
    static final int WEB_INTERVAL = 200;       // 200 tick = 10 second
    static final int NEXT_CD = 120;            // ek round ke baad wait

    // states
    static final int IDLE = 0, MARK = 1, MARK_WAIT = 2, RAIL = 3, CART = 4, POSITION = 5, FIRE = 6, SHOOT = 7;

    private final Random rng = new Random();
    private final ArrayDeque<BlockPos> webQueue = new ArrayDeque<>();
    private int state = IDLE, stateTicks = 0, fireTries = 0;
    private int strafeDir = 1, strafeTimer = 0, backTimer = 0;
    private int webTimer = 0, cd = 0, pearlCd = 0, potCd = 0, meleeTimer = 0;
    private boolean healMode = false, cartPlaced = false;
    private BlockPos refPos = null, railPos = null, firePos = null;
    private double standDist = STAND_DIST;
    private float bestPitch = 0f;

    // ------------------------------------------------------------ ITEM CHECKS
    static boolean isCart(ItemStack s) { return s.is(Items.TNT_MINECART); }
    static boolean isRail(ItemStack s) { return s.is(Items.RAIL); }
    static boolean isFlint(ItemStack s) { return s.is(Items.FLINT_AND_STEEL); }
    static boolean isCross(ItemStack s) { return s.is(Items.CROSSBOW); }
    static boolean isBow(ItemStack s) { return s.is(Items.BOW); }
    static boolean isArrow(ItemStack s) { return s.is(ItemTags.ARROWS); }
    static boolean isPearl(ItemStack s) { return s.is(Items.ENDER_PEARL); }

    private int idx(LocalPlayer me, Predicate<ItemStack> f) {
        return KitManager.findBest(me, f, KitManager::zeroScore);
    }

    private boolean has(LocalPlayer me, Predicate<ItemStack> f) { return idx(me, f) >= 0; }

    private void hold(Minecraft mc, LocalPlayer me, Predicate<ItemStack> f) {
        if (f.test(me.getMainHandItem())) return;
        int i = idx(me, f);
        if (i >= 0) KitManager.equip(mc, i);
    }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        LocalPlayer me = mc.player;
        return has(me, TntMinecartPvp::isCart) && has(me, TntMinecartPvp::isRail)
                && has(me, TntMinecartPvp::isFlint) && has(me, TntMinecartPvp::isArrow)
                && (has(me, TntMinecartPvp::isCross) || has(me, TntMinecartPvp::isBow));
    }

    void onActivate(Minecraft mc) {
        abort(mc);
        webQueue.clear();
        healMode = false;
        meleeTimer = 0;
        cd = 0;
        pearlCd = 0;
        potCd = 0;
        strafeTimer = 0;
        backTimer = 0;
        webTimer = WEB_INTERVAL; // pehla web turant
    }

    void onDeactivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        abort(mc);
        KitManager.releaseAll(mc);
    }

    private void abort(Minecraft mc) {
        state = IDLE;
        stateTicks = 0;
        refPos = null;
        railPos = null;
        firePos = null;
        cartPlaced = false;
        fireTries = 0;
        standDist = STAND_DIST;
        mc.options.keyUse.setDown(false);
    }

    private void to(int s) {
        state = s;
        stateTicks = 0;
    }

    private void finish(Minecraft mc) {
        abort(mc);
        cd = NEXT_CD;
    }

    // ------------------------------------------------------------ MAIN TICK
    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (target == null) {
            KitManager.releaseAll(mc);
            webQueue.clear();
            abort(mc);
            return;
        }
        webTimer++;
        if (cd > 0) cd--;
        if (pearlCd > 0) pearlCd--;
        if (potCd > 0) potCd--;

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        int potIdx = KitManager.findBest(me, NethpotPvp::isHealPot, NethpotPvp::potScore);
        int pearlIdx = idx(me, TntMinecartPvp::isPearl);
        boolean canHeal = foodIdx >= 0 || potIdx >= 0 || pearlIdx >= 0;
        float hp = me.getHealth();

        if (!healMode && hp <= LOW_HP && canHeal) {
            healMode = true;
            abort(mc);
        }
        if (healMode && (hp >= HEAL_DONE_HP || !canHeal)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }
        if (healMode) {
            heal(mc, me, target, foodIdx, potIdx, pearlIdx);
            return;
        }

        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());

        // enemy normal PvP kare (sword/axe/mace leke paas aaye ya hit kare) -> sword se crit fight
        ItemStack et = target.getMainHandItem();
        boolean armed = KitManager.isSword(et) || KitManager.isAxe(et) || et.is(Items.MACE);
        if ((dist <= MELEE_DIST && armed) || (me.hurtTime > 0 && dist <= 5.0)) meleeTimer = 60;
        boolean haveSword = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore) >= 0;
        if (meleeTimer > 0) {
            meleeTimer--;
            if (haveSword) {
                abort(mc);
                melee(mc, me, target, dist);
                return;
            }
        }

        step(mc, me, target);
    }

    // ------------------------------------------------------------ TNT STATE MACHINE
    private void step(Minecraft mc, LocalPlayer me, Player target) {
        stateTicks++;
        boolean cross = has(me, TntMinecartPvp::isCross); // crossbow ho to hamesha crossbow
        double hdist = Math.hypot(target.getX() - me.getX(), target.getZ() - me.getZ());

        switch (state) {
            case IDLE -> {
                if (processWebs(mc, me, target)) return;
                if (cd > 0) {
                    KitManager.stopMovement(mc);
                    return;
                }
                if (hdist > 3.8) { // enemy ke paas jao (sirf direction, aim nahi)
                    KitManager.setRotation(me, yawTo(me, target.position()), 0f);
                    move(mc, me, true, false, 0, true, false);
                    return;
                }
                KitManager.stopMovement(mc);
                refPos = target.blockPosition();
                to(cross ? RAIL : MARK);
            }

            case MARK -> { // bow: pehle zameen pe arrow
                if (!has(me, TntMinecartPvp::isBow) || !has(me, TntMinecartPvp::isArrow)) {
                    finish(mc);
                    return;
                }
                hold(mc, me, TntMinecartPvp::isBow);
                KitManager.stopMovement(mc);
                Vec3 tgt = target.position();
                float yaw = yawTo(me, tgt);
                solve(me.getEyePosition(), yaw, tgt, 3.0);
                KitManager.setRotation(me, yaw, bestPitch);
                mc.options.keyUse.setDown(true);
                if (me.isUsingItem() && me.getTicksUsingItem() >= 20) {
                    mc.options.keyUse.setDown(false);
                    to(MARK_WAIT);
                }
                if (stateTicks > 80) finish(mc);
            }

            case MARK_WAIT -> {
                mc.options.keyUse.setDown(false);
                if (stateTicks >= 30) {
                    refPos = findArrowSpot(mc, target);
                    to(RAIL);
                }
            }

            case RAIL -> {
                KitManager.stopMovement(mc);
                if (railPos == null) {
                    railPos = findRailSpot(mc, me, refPos);
                    if (railPos == null) { // door hai, thoda paas jao
                        if (stateTicks > 40) {
                            finish(mc);
                            return;
                        }
                        KitManager.setRotation(me, yawTo(me, target.position()), 0f);
                        move(mc, me, true, false, 0, true, false);
                        return;
                    }
                }
                if (mc.level.getBlockState(railPos).is(BlockTags.RAILS)) {
                    to(CART);
                    return;
                }
                if (!has(me, TntMinecartPvp::isRail)) {
                    finish(mc);
                    return;
                }
                hold(mc, me, TntMinecartPvp::isRail);
                if (stateTicks % 3 == 1) placeOn(mc, me, railPos.below());
                if (stateTicks > 40) finish(mc);
            }

            case CART -> {
                if (findCart(mc) != null) {
                    standDist = STAND_DIST;
                    to(POSITION);
                    return;
                }
                if (!has(me, TntMinecartPvp::isCart)) {
                    finish(mc);
                    return;
                }
                hold(mc, me, TntMinecartPvp::isCart);
                if (!cartPlaced || (stateTicks > 25 && stateTicks % 10 == 0)) {
                    mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(railPos), Direction.UP, railPos, false));
                    me.swing(InteractionHand.MAIN_HAND);
                    cartPlaced = true;
                }
                if (stateTicks > 60) finish(mc);
            }

            case POSITION -> { // cart se standDist door jao (rail ke pass rehkar)
                Entity cart = findCart(mc);
                if (cart == null) {
                    finish(mc);
                    return;
                }
                if (cross) chargeCrossbow(mc, me);
                Vec3 cp = cart.position();
                double dx = me.getX() - cp.x, dz = me.getZ() - cp.z;
                double len = Math.hypot(dx, dz);
                if (len < 1e-3) {
                    dx = 1;
                    dz = 0;
                    len = 1;
                }
                double sx = cp.x + dx / len * standDist, sz = cp.z + dz / len * standDist;
                double rem = Math.hypot(sx - me.getX(), sz - me.getZ());
                if (rem < 0.8 || stateTicks > 100) {
                    KitManager.stopMovement(mc);
                    to(FIRE);
                    return;
                }
                float yaw = (float) (Math.toDegrees(Math.atan2(sz - me.getZ(), sx - me.getX())) - 90.0);
                KitManager.setRotation(me, yaw, 0f);
                move(mc, me, true, false, 0, rem > 3.0, false);
            }

            case FIRE -> { // khud ke aage (arrow ke raste me) fire lagao
                Entity cart = findCart(mc);
                if (cart == null) {
                    finish(mc);
                    return;
                }
                KitManager.stopMovement(mc);
                if (cross && !crossCharged(me)) {
                    chargeCrossbow(mc, me);
                    if (stateTicks > 100) finish(mc);
                    return;
                }
                mc.options.keyUse.setDown(false);

                Vec3 tgt = cart.getBoundingBox().getCenter();
                double speed = cross ? 3.15 : 3.0;
                float yaw = yawTo(me, tgt);
                solve(me.getEyePosition(), yaw, tgt, speed);
                BlockPos cell = findFireCell(mc, me, yaw, bestPitch, speed);
                if (cell == null) { // fire ki jagah nahi mili -> thoda paas jao
                    standDist -= 1.0;
                    if (standDist < MIN_STAND) finish(mc);
                    else to(POSITION);
                    return;
                }
                firePos = cell;
                if (mc.level.getBlockState(cell).is(BlockTags.FIRE)) {
                    to(SHOOT);
                    return;
                }
                if (!has(me, TntMinecartPvp::isFlint) || ++fireTries > 8) {
                    finish(mc);
                    return;
                }
                hold(mc, me, TntMinecartPvp::isFlint);
                KitManager.aimAt(me, Vec3.atCenterOf(cell));
                placeOn(mc, me, cell.below());
            }

            case SHOOT -> {
                Entity cart = findCart(mc);
                if (cart == null) { // cart phat gaya ya gayab
                    finish(mc);
                    return;
                }
                if (firePos == null || !mc.level.getBlockState(firePos).is(BlockTags.FIRE)) {
                    to(FIRE); // fire bujh gayi, dobara
                    return;
                }
                KitManager.stopMovement(mc);
                Vec3 tgt = cart.getBoundingBox().getCenter();
                double speed = cross ? 3.15 : 3.0;
                float yaw = yawTo(me, tgt);
                solve(me.getEyePosition(), yaw, tgt, speed);

                if (cross) {
                    hold(mc, me, TntMinecartPvp::isCross);
                    if (!crossCharged(me)) {
                        to(FIRE);
                        return;
                    }
                    KitManager.setRotation(me, yaw, bestPitch);
                    mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
                    me.swing(InteractionHand.MAIN_HAND);
                    finish(mc);
                } else {
                    hold(mc, me, TntMinecartPvp::isBow);
                    KitManager.setRotation(me, yaw, bestPitch);
                    mc.options.keyUse.setDown(true);
                    if (me.isUsingItem() && me.getTicksUsingItem() >= 20) finish(mc); // finish keyUse band karta hai = arrow chhootega
                }
                if (stateTicks > 100) finish(mc);
            }

            default -> abort(mc);
        }
    }

    // ------------------------------------------------------------ CROSSBOW
    private boolean crossCharged(LocalPlayer me) {
        int i = idx(me, TntMinecartPvp::isCross);
        return i >= 0 && CrossbowItem.isCharged(me.getInventory().getItem(i));
    }

    private void chargeCrossbow(Minecraft mc, LocalPlayer me) {
        int ci = idx(me, TntMinecartPvp::isCross);
        if (ci < 0) return;
        if (CrossbowItem.isCharged(me.getInventory().getItem(ci))) {
            mc.options.keyUse.setDown(false);
            return;
        }
        if (!isCross(me.getMainHandItem())) KitManager.equip(mc, ci);
        mc.options.keyUse.setDown(true);
    }

    // ------------------------------------------------------------ ARROW PATH (gravity wala aim)
    /** from se to tak arrow pahunchane ka pitch nikalta hai (bestPitch, + = neeche). */
    private void solve(Vec3 from, float yawDeg, Vec3 to, double speed) {
        double horiz = Math.hypot(to.x - from.x, to.z - from.z);
        float bp = 0f;
        double bm = 1e9;
        for (float p = 80f; p >= -80f; p -= 0.5f) {
            double pr = Math.toRadians(p);
            double hv = Math.cos(pr) * speed, vy = -Math.sin(pr) * speed;
            double h = 0, y = from.y;
            double min = 1e9;
            for (int t = 0; t < 100; t++) {
                for (int s = 1; s <= 4; s++) {
                    double f = s / 4.0;
                    double d = Math.hypot(h + hv * f - horiz, y + vy * f - to.y);
                    if (d < min) min = d;
                }
                h += hv;
                y += vy;
                hv *= 0.99;
                vy = vy * 0.99 - 0.05;
                if (h > horiz + 3 || y < to.y - 25) break;
            }
            if (min < bm - 0.2) { // chhota (flat) arc ko preference
                bm = min;
                bp = p;
            }
        }
        bestPitch = bp;
    }

    /** Arrow ke raste me rail wali height pe aisi cell jahan fire laga sakte hain. */
    private BlockPos findFireCell(Minecraft mc, LocalPlayer me, float yaw, float pitch, double speed) {
        Vec3 from = me.getEyePosition();
        double yr = Math.toRadians(yaw);
        double hx = -Math.sin(yr), hz = Math.cos(yr);
        double pr = Math.toRadians(pitch);
        double hv = Math.cos(pr) * speed, vy = -Math.sin(pr) * speed;
        double h = 0, y = from.y;
        for (int t = 0; t < 60; t++) {
            for (int s = 1; s <= 8; s++) {
                double f = s / 8.0;
                double dd = h + hv * f;
                BlockPos c = BlockPos.containing(from.x + hx * dd, y + vy * f, from.z + hz * dd);
                if (c.equals(railPos)) return null;
                BlockState st = mc.level.getBlockState(c);
                if (c.getY() == railPos.getY()) {
                    if (st.is(BlockTags.FIRE)) return c;
                    BlockPos b = c.below();
                    if ((st.isAir() || st.canBeReplaced())
                            && mc.level.getBlockState(b).isFaceSturdy(mc.level, b, Direction.UP)
                            && from.distanceTo(Vec3.atCenterOf(c)) <= PLACE_RANGE) return c;
                }
                if (!st.getCollisionShape(mc.level, c).isEmpty()) return null; // raste me block
            }
            h += hv;
            y += vy;
            hv *= 0.99;
            vy = vy * 0.99 - 0.05;
        }
        return null;
  }
    // ------------------------------------------------------------ PLACE HELPERS
    private Entity findCart(Minecraft mc) {
        if (railPos == null) return null;
        List<MinecartTNT> l = mc.level.getEntitiesOfClass(MinecartTNT.class, new AABB(railPos).inflate(1.5));
        return l.isEmpty() ? null : l.get(0);
    }

    private BlockPos findArrowSpot(Minecraft mc, Player target) {
        List<AbstractArrow> l = mc.level.getEntitiesOfClass(AbstractArrow.class, target.getBoundingBox().inflate(8.0));
        AbstractArrow best = null;
        double bd = 1e9;
        for (AbstractArrow a : l) {
            if (a.getDeltaMovement().lengthSqr() > 1.0E-4) continue; // abhi ud raha hai
            double d = a.distanceToSqr(target);
            if (d < bd) {
                bd = d;
                best = a;
            }
        }
        if (best == null) return target.blockPosition();
        BlockPos p = best.blockPosition();
        BlockState st = mc.level.getBlockState(p);
        if (!st.isAir() && !st.canBeReplaced()) p = p.above();
        return p;
    }

    private BlockPos findRailSpot(Minecraft mc, LocalPlayer me, BlockPos ref) {
        BlockPos best = null;
        double bd = 1e9;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = ref.offset(dx, dy, dz);
                    BlockState st = mc.level.getBlockState(p);
                    if (!st.isAir() && !st.canBeReplaced()) continue;
                    BlockPos b = p.below();
                    if (!mc.level.getBlockState(b).isFaceSturdy(mc.level, b, Direction.UP)) continue;
                    Vec3 c = Vec3.atCenterOf(p);
                    if (me.getEyePosition().distanceTo(c) > PLACE_RANGE) continue;
                    double d = c.distanceToSqr(Vec3.atCenterOf(ref)) + Math.abs(dy) * 0.5;
                    if (d < bd) {
                        bd = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /** below block ke upar wali side pe item use karo (rail / fire). */
    private void placeOn(Minecraft mc, LocalPlayer me, BlockPos below) {
        Vec3 hit = Vec3.atCenterOf(below).add(0, 0.5, 0);
        mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, below, false));
        me.swing(InteractionHand.MAIN_HAND);
    }

    private static float yawTo(Player me, Vec3 t) {
        return (float) (Math.toDegrees(Math.atan2(t.z - me.getZ(), t.x - me.getX())) - 90.0);
    }

    // ------------------------------------------------------------ MELEE (sword + critical hit)
    private void melee(Minecraft mc, LocalPlayer me, Player target, double dist) {
        mc.options.keyUse.setDown(false);
        if (processWebs(mc, me, target)) return;

        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
        if (target.isBlocking() && axeIdx >= 0) {
            if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
        } else if (!KitManager.isSword(me.getMainHandItem())) {
            int swordIdx = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
            if (swordIdx >= 0) KitManager.equip(mc, swordIdx);
        }

        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        boolean inWeb = KitManager.inWeb(mc.level, me);

        if (dist > 3.5) { // door -> sprint karke pas
            move(mc, me, true, false, dist > 8.0 ? 0 : strafeDir, true, false);
            return;
        }

        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }
        if (backTimer > 0) backTimer--;
        else if (dist < 3.0 && rng.nextInt(30) == 0) backTimer = 4 + rng.nextInt(4);

        boolean back = backTimer > 0 || dist < 1.2;
        boolean fwd = dist > 2.0 && !back;
        boolean jump = me.onGround() && !inWeb && me.getAttackStrengthScale(8f) >= 0.95f;
        move(mc, me, fwd, back, strafeDir, false, jump); // sprint band (crit ke liye)

        boolean charged = me.getAttackStrengthScale(0.5f) >= 0.95f;
        if (dist <= REACH && charged) {
            boolean crit = me.fallDistance > 0 && !me.onGround() && !me.onClimbable()
                    && !me.isInWater() && !me.isSprinting();
            if (crit || inWeb || KitManager.isAxe(me.getMainHandItem())) {
                mc.gameMode.attack(me, target);
                me.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    // ------------------------------------------------------------ HEAL (pot / pearl / bhaagna / food)
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx, int potIdx, int pearlIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());

        // 1) healing pot apne pair pe
        if (potIdx >= 0 && potCd == 0 && !eating) {
            mc.options.keyUse.setDown(false);
            KitManager.equip(mc, potIdx);
            KitManager.setRotation(me, me.getYRot(), 90f);
            mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
            me.swing(InteractionHand.MAIN_HAND);
            potCd = 12;
            return;
        }
        // 2) enemy paas (ya khana nahi): pearl se bhaago, warna sprint
        if (dist < FLEE_SAFE_DIST || foodIdx < 0) {
            mc.options.keyUse.setDown(false);
            if (pearlIdx >= 0 && pearlCd == 0 && dist < 8.0) {
                throwPearlAway(mc, me, target, pearlIdx);
                return;
            }
            flee(mc, me, target);
            return;
        }
        // 3) safe doori: khana khao
        KitManager.stopMovement(mc);
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        if (!eating) KitManager.equip(mc, foodIdx);
        mc.options.keyUse.setDown(true);
    }

    private void throwPearlAway(Minecraft mc, LocalPlayer me, Player target, int pearlIdx) {
        double ax = me.getX() - target.getX();
        double az = me.getZ() - target.getZ();
        if (ax * ax + az * az < 1e-4) ax = 1;
        float yaw = (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
        KitManager.setRotation(me, yaw, -35f); // dushman se ulta, thoda upar
        KitManager.equip(mc, pearlIdx);
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        pearlCd = 60;
    }

    private void flee(Minecraft mc, LocalPlayer me, Player target) {
        double ax = me.getX() - target.getX();
        double az = me.getZ() - target.getZ();
        if (ax * ax + az * az < 1e-4) ax = 1;
        float yaw = (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
        KitManager.setRotation(me, yaw, 0f); // dushman se ulta dekho aur bhaago
        move(mc, me, true, false, 0, true, rng.nextInt(12) == 0);
    }

    // ------------------------------------------------------------ MOVE (cobweb se bachte huve)
    private void move(Minecraft mc, LocalPlayer me, boolean fwd, boolean back, int strafe,
                      boolean sprint, boolean jump) {
        double yawR = Math.toRadians(me.getYRot());
        double fx = -Math.sin(yawR), fz = Math.cos(yawR);   // aage
        double rx = -Math.cos(yawR), rz = -Math.sin(yawR);  // right (D)
        Level lvl = mc.level;

        if (fwd && KitManager.webAhead(lvl, me, fx, fz)) {
            fwd = false;
            if (strafe == 0) strafe = rng.nextBoolean() ? 1 : -1;
        }
        if (back && KitManager.webAhead(lvl, me, -fx, -fz)) back = false;
        if (strafe > 0 && KitManager.webAhead(lvl, me, rx, rz)) {
            strafe = KitManager.webAhead(lvl, me, -rx, -rz) ? 0 : -1;
            strafeDir = -1;
        } else if (strafe < 0 && KitManager.webAhead(lvl, me, -rx, -rz)) {
            strafe = KitManager.webAhead(lvl, me, rx, rz) ? 0 : 1;
            strafeDir = 1;
        }
        if (KitManager.inWeb(lvl, me)) { // fas gaye to bas fight karo
            back = false;
            strafe = 0;
            jump = false;
        }

        mc.options.keyUp.setDown(fwd);
        mc.options.keyDown.setDown(back);
        mc.options.keyLeft.setDown(strafe < 0);
        mc.options.keyRight.setDown(strafe > 0);
        mc.options.keySprint.setDown(sprint && fwd);
        mc.options.keyJump.setDown(jump && me.onGround());
        if (!(sprint && fwd)) me.setSprinting(false);
    }

    // ------------------------------------------------------------ COBWEB PLACE (har 10 sec)
    /** true = is tick me web laga raha hai. */
    private boolean processWebs(Minecraft mc, LocalPlayer me, Player target) {
        int webIdx = idx(me, KitManager::isWeb);

        if (!webQueue.isEmpty()) {
            if (webIdx < 0) {
                webQueue.clear();
                return false;
            }
            BlockPos pos = webQueue.poll();
            if (!KitManager.isWeb(me.getMainHandItem())) KitManager.equip(mc, webIdx);
            placeWeb(mc, me, pos);
            return true;
        }

        if (webTimer >= WEB_INTERVAL && webIdx >= 0 && me.distanceTo(target) <= PLACE_RANGE) {
            BlockPos feet = target.blockPosition();
            // enemy pehle se fasa hai to mat lagao
            if (KitManager.isWebBlock(mc.level, feet) || KitManager.isWebBlock(mc.level, feet.above()))
                return false;

            Direction away = Direction.getNearest(
                    target.getX() - me.getX(), 0, target.getZ() - me.getZ());
            webQueue.add(feet);                 // 1) pair ke paas
            webQueue.add(feet.above());         // 2) sir ke paas
            webQueue.add(feet.relative(away));  // 3) peeche ki taraf
            webTimer = 0;
        }
        return false;
    }

    private void placeWeb(Minecraft mc, LocalPlayer me, BlockPos pos) {
        BlockState st = mc.level.getBlockState(pos);
        if (!st.isAir() && !st.canBeReplaced()) return;
        if (me.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > PLACE_RANGE) return;

        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState ns = mc.level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced()) continue; // click karne ke liye solid block chahiye
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, n, false));
            me.swing(InteractionHand.MAIN_HAND);
            return;
        }
    }
          }
