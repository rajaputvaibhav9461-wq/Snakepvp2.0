package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

public class CrystalPvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final float LOW_HP = 6.0f;            // 6 HP = 3 heart pe bhaag ke heal
    static final float HEAL_DONE_HP = 14.0f;     // itna HP hone pe wapas fight
    static final float CRYSTAL_MIN_HP = 8.0f;    // isse kam HP pe crystal band, sirf sword
    static final double REACH = 2.95;            // sword hit range
    static final double CHASE_DIST = 3.5;        // isse door ho to sprint karke pas jao
    static final double FLEE_SAFE_DIST = 8.0;    // itni door jaake khana khayega
    static final int POT_COOLDOWN = 12;          // 2 pot ke beech ke tick
    static final int PLACE_DELAY = 2;            // hit ke baad crystal rakhne se pehle tick
    static final int BREAK_DELAY = 2;            // crystal rakhne ke baad blast se pehle tick
    static final int OBS_COOLDOWN = 4;           // 2 obsidian ke beech ke tick
    static final double PLACE_RANGE = 4.5;       // block rakhne ki range
    static final double SELF_MIN = 2.5;          // crystal tumse itna door (khud ko damage kam)
    static final double BREAK_MAX = 3.8;         // crystal tumse itne ke andar (hit pahunche)
    static final double ENEMY_MAX = 2.5;         // crystal enemy ke itne paas

    // states
    static final int MELEE = 0, PLACE = 1, BREAK = 2;

    private final Random rng = new Random();
    private int state = MELEE, stateTicks = 0;
    private int strafeDir = 1, strafeTimer = 0, backTimer = 0;
    private int potCd = 0, pearlCd = 0, obsCd = 0, offCd = 0;
    private boolean healMode = false;
    private BlockPos base = null;

    // ------------------------------------------------------------ ITEM CHECKS
    static boolean isObsidian(ItemStack s) { return s.is(Items.OBSIDIAN); }
    static boolean isCrystal(ItemStack s) { return s.is(Items.END_CRYSTAL); }

    private int idx(LocalPlayer me, java.util.function.Predicate<ItemStack> f) {
        return KitManager.findBest(me, f, KitManager::zeroScore);
    }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        LocalPlayer me = mc.player;
        return KitManager.findBest(me, KitManager::isSword, KitManager::swordScore) >= 0
                && idx(me, CrystalPvp::isObsidian) >= 0
                && idx(me, CrystalPvp::isCrystal) >= 0;
    }

    void onActivate(Minecraft mc) {
        healMode = false;
        state = MELEE;
        stateTicks = 0;
        base = null;
        backTimer = 0;
        strafeTimer = 0;
        potCd = 0;
        pearlCd = 0;
        obsCd = 0;
        offCd = 0;
    }

    void onDeactivate(Minecraft mc) {
        healMode = false;
        state = MELEE;
        base = null;
        KitManager.releaseAll(mc);
    }

    private void to(int s) {
        state = s;
        stateTicks = 0;
    }

    // ------------------------------------------------------------ MAIN TICK
    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (potCd > 0) potCd--;
        if (pearlCd > 0) pearlCd--;
        if (obsCd > 0) obsCd--;
        if (offCd > 0) offCd--;

        ensureOffhand(mc, me); // totem hamesha offhand me

        if (target == null) {
            KitManager.releaseAll(mc);
            to(MELEE);
            return;
        }

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        int potIdx = KitManager.findBest(me, NethpotPvp::isHealPot, NethpotPvp::potScore);
        int pearlIdx = idx(me, s -> s.is(Items.ENDER_PEARL));
        boolean canHeal = foodIdx >= 0 || potIdx >= 0 || pearlIdx >= 0;
        float hp = me.getHealth();

        if (!healMode && hp <= LOW_HP && canHeal) {
            healMode = true;
            to(MELEE);
        }
        if (healMode && (hp >= HEAL_DONE_HP || !canHeal)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }

        if (healMode) heal(mc, me, target, foodIdx, potIdx, pearlIdx);
        else fight(mc, me, target);
    }

    // ------------------------------------------------------------ OFFHAND (totem / shield)
    private void ensureOffhand(Minecraft mc, LocalPlayer me) {
        if (offCd > 0) return;
        if (me.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;

        int i = idx(me, s -> s.is(Items.TOTEM_OF_UNDYING));
        if (i < 0) { // totem nahi to shield
            if (me.getOffhandItem().is(Items.SHIELD)) return;
            i = idx(me, s -> s.is(Items.SHIELD));
        }
        if (i < 0) return;

        int slot = i < 9 ? 36 + i : i;
        mc.gameMode.handleInventoryMouseClick(
                me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me); // 40 = offhand
        offCd = 10;
    }

    // ------------------------------------------------------------ FIGHT
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);

        if (state == PLACE) {
            placeCrystal(mc, me, target);
            return;
        }
        if (state == BREAK) {
            breakCrystal(mc, me, target);
            return;
        }

        // ---- MELEE: pehle obsidian, phir hit
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());
        BlockPos nb = findBase(mc, me, target);
        boolean hasObs = nb != null && isBaseBlock(mc.level, nb);

        if (nb != null && !hasObs && obsCd == 0) {
            int oi = idx(me, CrystalPvp::isObsidian);
            if (oi >= 0) {
                KitManager.stopMovement(mc);
                KitManager.equip(mc, oi);
                KitManager.aimAt(me, Vec3.atCenterOf(nb));
                placeBlock(mc, me, nb);
                obsCd = OBS_COOLDOWN;
                return;
            }
        }

        boolean hit = melee(mc, me, target, dist);

        // hit lagte hi turant crystal
        if (hit && hasObs && me.getHealth() >= CRYSTAL_MIN_HP && idx(me, CrystalPvp::isCrystal) >= 0) {
            base = nb;
            to(PLACE);
        }
    }

    /** Sword/axe se hit. true = is tick hit mara. */
    private boolean melee(Minecraft mc, LocalPlayer me, Player target, double dist) {
        // weapon: shield pe axe, warna sword (poori inventory se)
        int axeIdx = idx(me, KitManager::isAxe);
        if (target.isBlocking() && axeIdx >= 0) {
            if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
        } else if (!KitManager.isSword(me.getMainHandItem())) {
            int swordIdx = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
            if (swordIdx >= 0) KitManager.equip(mc, swordIdx);
        }

        // aim: lock hone ke baad hamesha target pe
        KitManager.aimAt(me, target.getBoundingBox().getCenter());

        // range se bahar (ya enemy bhaag raha) -> sprint karke pas jao
        if (dist > CHASE_DIST) {
            move(mc, me, true, false, dist > 8.0 ? 0 : strafeDir, true);
            return false;
        }

        // movement: W + strafe A/D + kabhi kabhi S, taaki enemy ka aim na lage
        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }
        if (backTimer > 0) backTimer--;
        else if (dist < 3.0 && rng.nextInt(30) == 0) backTimer = 4 + rng.nextInt(4);

        boolean back = backTimer > 0 || dist < 1.2;
        boolean fwd = dist > 2.0 && !back;
        move(mc, me, fwd, back, strafeDir, fwd); // sprint hit = zyada knockback (enemy upar uchhalta hai)

        if (dist <= REACH && me.getAttackStrengthScale(0.5f) >= 0.9f) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ PLACE crystal (hit ke turant baad)
    private void placeCrystal(Minecraft mc, LocalPlayer me, Player target) {
        stateTicks++;
        KitManager.stopMovement(mc);
        if (base == null || !isBaseBlock(mc.level, base)) {
            to(MELEE);
            return;
        }
        Vec3 c = Vec3.atCenterOf(base);
        KitManager.aimAt(me, c.add(0, 0.5, 0));
        if (stateTicks < PLACE_DELAY) return;

        double h = Math.hypot(c.x - me.getX(), c.z - me.getZ());
        if (h < SELF_MIN - 0.4 || me.getEyePosition().distanceTo(c) > PLACE_RANGE) { // khud ko lagega / door
            to(MELEE);
            return;
        }
        if (findCrystal(mc) != null) { // pehle se crystal hai, seedha blast
            to(BREAK);
            return;
        }
        int ci = idx(me, CrystalPvp::isCrystal);
        if (ci < 0) {
            to(MELEE);
            return;
        }
        KitManager.equip(mc, ci);
        Vec3 hit = c.add(0, 0.5, 0);
        mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, base, false));
        me.swing(InteractionHand.MAIN_HAND);
        to(BREAK);
    }

    // ------------------------------------------------------------ BREAK crystal (blast)
    private void breakCrystal(Minecraft mc, LocalPlayer me, Player target) {
        stateTicks++;
        EndCrystal cr = findCrystal(mc);
        if (cr == null) { // crystal abhi aaya nahi / nahi laga
            KitManager.stopMovement(mc);
            if (stateTicks > 10) to(MELEE);
            return;
        }
        KitManager.aimAt(me, cr.getBoundingBox().getCenter());
        double d = KitManager.boxDist(me.getEyePosition(), cr.getBoundingBox());
        if (d > 2.9) { // thoda paas jao
            KitManager.stopMovement(mc);
            mc.options.keyUp.setDown(true);
            if (stateTicks > 30) to(MELEE);
            return;
        }
        KitManager.stopMovement(mc);
        if (stateTicks < BREAK_DELAY) return;

        mc.gameMode.attack(me, cr); // blast
        me.swing(InteractionHand.MAIN_HAND);
        to(MELEE);
    }

    private EndCrystal findCrystal(Minecraft mc) {
        if (base == null) return null;
        List<EndCrystal> l = mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(base.above()).inflate(0.6));
        return l.isEmpty() ? null : l.get(0);
    }

    // ------------------------------------------------------------ BASE (obsidian) dhundhna / rakhna
    private static boolean isBaseType(BlockState st) {
        return st.is(Blocks.OBSIDIAN) || st.is(Blocks.BEDROCK);
    }

    private static boolean isBaseBlock(Level lv, BlockPos p) {
        return isBaseType(lv.getBlockState(p)) && lv.getBlockState(p.above()).isAir();
    }

    private static boolean hasSupport(Level lv, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockState ns = lv.getBlockState(p.relative(d));
            if (!ns.isAir() && !ns.canBeReplaced()) return true;
        }
        return false;
    }

    /** Enemy ke paas obsidian ki jagah: tumse SELF_MIN..BREAK_MAX door, enemy ke ENEMY_MAX andar. */
    private BlockPos findBase(Minecraft mc, LocalPlayer me, Player target) {
        Level lv = mc.level;
        BlockPos feet = target.blockPosition();
        Vec3 eye = me.getEyePosition();
        BlockPos best = null;
        double bs = 1e9;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos o = feet.offset(dx, dy, dz);
                    BlockState st = lv.getBlockState(o);
                    boolean obs = isBaseType(st);
                    if (!obs) {
                        if (!st.isAir() && !st.canBeReplaced()) continue;
                        if (!hasSupport(lv, o)) continue;
                    }
                    if (!lv.getBlockState(o.above()).isAir()) continue;

                    Vec3 c = Vec3.atCenterOf(o);
                    double hMe = Math.hypot(c.x - me.getX(), c.z - me.getZ());
                    if (hMe < SELF_MIN || hMe > BREAK_MAX) continue;
                    if (eye.distanceTo(c) > PLACE_RANGE) continue;
                    double hEn = Math.hypot(c.x - target.getX(), c.z - target.getZ());
                    if (hEn > ENEMY_MAX) continue;

                    AABB cb = new AABB(o.getX(), o.getY() + 1, o.getZ(), o.getX() + 1, o.getY() + 3, o.getZ() + 1);
                    if (cb.intersects(target.getBoundingBox()) || cb.intersects(me.getBoundingBox())) continue;
                    if (!obs) {
                        AABB ob = new AABB(o);
                        if (ob.intersects(target.getBoundingBox()) || ob.intersects(me.getBoundingBox())) continue;
                    }

                    double score = hEn + Math.abs(dy) * 0.5 - (obs ? 1.0 : 0.0);
                    if (score < bs) {
                        bs = score;
                        best = o;
                    }
                }
            }
        }
        return best;
    }

    /** pos pe block rakhna (kisi solid padosi block pe click karke). */
    private void placeBlock(Minecraft mc, LocalPlayer me, BlockPos pos) {
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

    // ------------------------------------------------------------ HEAL (pot / pearl / bhaagna / food)
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx, int potIdx, int pearlIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());

        // 1) healing pot hai to apne pair pe fenko
        if (potIdx >= 0 && potCd == 0 && !eating) {
            mc.options.keyUse.setDown(false);
            KitManager.equip(mc, potIdx);
            KitManager.setRotation(me, me.getYRot(), 90f);
            mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
            me.swing(InteractionHand.MAIN_HAND);
            potCd = POT_COOLDOWN;
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
        move(mc, me, true, false, 0, true);
        if (me.onGround() && rng.nextInt(12) == 0) mc.options.keyJump.setDown(true);
    }

    // ------------------------------------------------------------ MOVE (cobweb se bachte huve)
    private void move(Minecraft mc, LocalPlayer me, boolean fwd, boolean back, int strafe, boolean sprint) {
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
        }

        mc.options.keyUp.setDown(fwd);
        mc.options.keyDown.setDown(back);
        mc.options.keyLeft.setDown(strafe < 0);
        mc.options.keyRight.setDown(strafe > 0);
        mc.options.keySprint.setDown(sprint && fwd);
        mc.options.keyJump.setDown(false);
        if (!(sprint && fwd)) me.setSprinting(false);
    }
  }
