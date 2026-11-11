package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Random;

public class MacePvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final float LOW_HP = 6.0f;          // 6 HP = 3 heart pe bhaag ke heal
    static final float HEAL_DONE_HP = 14.0f;   // itna HP hone pe wapas fight
    static final double REACH = 3.0;           // hit range
    static final double FLEE_SAFE_DIST = 10.0; // itni door jaake khana khayega
    static final double CYCLE_RANGE = 24.0;    // itni doori ke andar wind-charge jump
    static final int ATTACK_GAP = 3;           // 2 hit ke beech tick (spam, par bahut fast nahi)
    static final int WIND_GAP = 14;            // 2 wind charge ke beech tick
    static final int WEB_INTERVAL = 100;       // 100 tick = 5 second
    static final double WEB_PLACE_RANGE = 4.5;

    private final Random rng = new Random();
    private final ArrayDeque<BlockPos> webQueue = new ArrayDeque<>();
    private int strafeDir = 1, strafeTimer = 0, webTimer = 0;
    private int windCd = 0, atkCd = 0, pearlCd = 0, potCd = 0, jumpTicks = 0;
    // phase: 0 normal, 1 jump chal raha (wind charge fenkna baaki), 2 upar ja rahe
    private int phase = 0;
    private boolean healMode = false;

    static boolean isMace(ItemStack s) { return s.is(Items.MACE); }
    static boolean isWind(ItemStack s) { return s.is(Items.WIND_CHARGE); }
    static boolean isShield(ItemStack s) { return s.is(Items.SHIELD); }
    static boolean isPearl(ItemStack s) { return s.is(Items.ENDER_PEARL); }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        return KitManager.findBest(mc.player, MacePvp::isMace, KitManager::zeroScore) >= 0
                && KitManager.findBest(mc.player, MacePvp::isWind, KitManager::zeroScore) >= 0;
    }

    void onActivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        phase = 0;
        windCd = 0;
        atkCd = 0;
        pearlCd = 0;
        potCd = 0;
        strafeTimer = 0;
        webTimer = WEB_INTERVAL; // pehla web turant
    }

    void onDeactivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        phase = 0;
        KitManager.releaseAll(mc);
    }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (target == null) {
            KitManager.releaseAll(mc);
            webQueue.clear();
            phase = 0;
            return;
        }
        webTimer++;
        if (windCd > 0) windCd--;
        if (atkCd > 0) atkCd--;
        if (pearlCd > 0) pearlCd--;
        if (potCd > 0) potCd--;

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        int potIdx = KitManager.findBest(me, NethpotPvp::isHealPot, NethpotPvp::potScore);
        int pearlIdx = KitManager.findBest(me, MacePvp::isPearl, KitManager::zeroScore);
        boolean canHeal = foodIdx >= 0 || potIdx >= 0 || pearlIdx >= 0;
        float hp = me.getHealth();

        if (!healMode && hp <= LOW_HP && canHeal) healMode = true;
        if (healMode && (hp >= HEAL_DONE_HP || !canHeal)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }

        if (healMode) heal(mc, me, target, foodIdx, potIdx, pearlIdx);
        else fight(mc, me, target);
    }

    // ------------------------------------------------------------ FIGHT
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());
        double hdist = Math.hypot(target.getX() - me.getX(), target.getZ() - me.getZ());

        // 1) enemy uper se mace leke aa raha hai -> hit range me aane se pehle shield up
        if (phase != 1 && threatFromAbove(me, target) && ensureOffhandShield(mc, me)) {
            holdMace(mc, me);
            KitManager.aimAt(me, target.getBoundingBox().getCenter());
            KitManager.stopMovement(mc);
            mc.options.keyUse.setDown(true);
            return;
        }

        if (me.onGround()) ground(mc, me, target, dist, hdist);
        else air(mc, me, target, dist, hdist);
    }

    // ------------------------------------------------------------ GROUND: wind charge + jump
    private void ground(Minecraft mc, LocalPlayer me, Player target, double dist, double hdist) {
        float yaw = yawTo(me, target);

        if (phase == 1) { // jump chal raha hai, neeche dekho
            KitManager.setRotation(me, yaw, 90f);
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
            mc.options.keyJump.setDown(true);
            if (++jumpTicks > 5) phase = 0;
            return;
        }
        phase = 0;
        holdMace(mc, me);
        if (processWebs(mc, me, target)) return;

        // enemy bahut door: sprint karke pas jao
        if (hdist > CYCLE_RANGE) {
            KitManager.setRotation(me, yaw, 0f);
            move(mc, me, true, false, 0, true, false);
            return;
        }

        int windIdx = KitManager.findBest(me, MacePvp::isWind, KitManager::zeroScore);
        if (windIdx >= 0 && windCd == 0) { // zameen pe ho -> hamesha wind charge se upar
            phase = 1;
            jumpTicks = 0;
            KitManager.setRotation(me, yaw, 90f);
            mc.options.keyJump.setDown(true);
            return;
        }

        // wind charge cooldown pe (ya khatam): enemy ke pas jao
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }
        boolean fwd = dist > 2.0;
        move(mc, me, fwd, dist < 1.2, strafeDir, fwd && dist > 4.0, false);

        if (windIdx < 0 && dist <= REACH && me.getAttackStrengthScale(0.5f) >= 0.95f) {
            hit(mc, me, target); // wind charge nahi to normal mace hit
        }
    }

    // ------------------------------------------------------------ AIR: upar / niche
    private void air(Minecraft mc, LocalPlayer me, Player target, double dist, double hdist) {
        // jump ke baad wind charge neeche fenko
        if (phase == 1) {
            phase = throwWind(mc, me) ? 2 : 0;
            return;
        }

        Vec3 mv = me.getDeltaMovement();
        boolean falling = mv.y < -0.02;

        // upar ja rahe: aim enemy pe nahi, bas uski direction (yaw)
        if (!falling) {
            holdMace(mc, me);
            KitManager.setRotation(me, yawTo(me, target), 0f);
            move(mc, me, hdist > 1.5, false, 0, true, false);
            return;
        }

        // niche aa rahe: aim turant enemy pe
        KitManager.aimAt(me, target.getBoundingBox().getCenter());

        // enemy door hai aur fall damage padne wala hai -> wind charge se bachao
        boolean canHit = dist <= REACH + 1.5;
        if (!canHit && me.fallDistance > 3.5f) {
            double ground = groundDist(mc, me);
            int windIdx = KitManager.findBest(me, MacePvp::isWind, KitManager::zeroScore);
            if (windIdx >= 0 && ground <= 6.0) {
                KitManager.setRotation(me, me.getYRot(), 90f);
                if (ground <= 3.0 && windCd == 0) throwWind(mc, me);
                return;
            }
        }

        // enemy ki taraf aao aur mace smash (spam)
        move(mc, me, hdist > 1.0, false, 0, true, false);
        if (dist <= REACH && atkCd == 0) {
            int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
            if (target.isBlocking() && axeIdx >= 0) {
                if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
                hit(mc, me, target); // pehle axe se shield todo
            } else if (me.fallDistance > 1.5f) {
                holdMace(mc, me);
                hit(mc, me, target); // phir mace se smash
            }
        }
    }

    private void hit(Minecraft mc, LocalPlayer me, Player target) {
        mc.gameMode.attack(me, target);
        me.swing(InteractionHand.MAIN_HAND);
        atkCd = ATTACK_GAP;
    }

    private boolean throwWind(Minecraft mc, LocalPlayer me) {
        int idx = KitManager.findBest(me, MacePvp::isWind, KitManager::zeroScore);
        if (idx < 0) return false;
        mc.options.keyJump.setDown(false);
        KitManager.equip(mc, idx);
        KitManager.setRotation(me, me.getYRot(), 90f); // neeche (apne pair pe)
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        windCd = WIND_GAP;
        return true;
    }

    private void holdMace(Minecraft mc, LocalPlayer me) {
        if (isMace(me.getMainHandItem())) return;
        int idx = KitManager.findBest(me, MacePvp::isMace, KitManager::zeroScore);
        if (idx >= 0) KitManager.equip(mc, idx);
    }

    /** Zameen kitni neeche hai (blocks). */
    private double groundDist(Minecraft mc, LocalPlayer me) {
        BlockPos p = me.blockPosition();
        for (int k = 0; k <= 12; k++) {
            BlockPos q = p.below(k);
            if (!mc.level.getBlockState(q).getCollisionShape(mc.level, q).isEmpty())
                return me.getY() - (q.getY() + 1);
        }
        return 99;
    }

    private static float yawTo(Player me, Player t) {
        return (float) (Math.toDegrees(Math.atan2(t.getZ() - me.getZ(), t.getX() - me.getX())) - 90.0);
    }

    // ------------------------------------------------------------ SHIELD (uper se mace ke khilaf)
    private boolean threatFromAbove(LocalPlayer me, Player t) {
        if (!t.getMainHandItem().is(Items.MACE)) return false;
        double vy = t.getY() - t.yo;
        if (t.getY() < me.getY() + 1.5 || vy > -0.15) return false; // uper nahi ya gir nahi raha
        Vec3 d = new Vec3(t.getX() - t.xo, vy, t.getZ() - t.zo).scale(3.0); // 3 tick aage
        AABB next = t.getBoundingBox().move(d);
        return KitManager.boxDist(me.getEyePosition(), next) <= 4.5;
    }

    private boolean ensureOffhandShield(Minecraft mc, LocalPlayer me) {
        if (me.getOffhandItem().is(Items.SHIELD)) return true;
        int idx = KitManager.findBest(me, MacePvp::isShield, KitManager::zeroScore);
        if (idx < 0) return false;
        int slot = idx < 9 ? 36 + idx : idx;
        mc.gameMode.handleInventoryMouseClick(
                me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me); // 40 = offhand
        return true;
    }

    // ------------------------------------------------------------ HEAL (pot / pearl / bhaagna / food)
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx, int potIdx, int pearlIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());
        phase = 0;

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
    }

    // ------------------------------------------------------------ COBWEB PLACE (zameen pe)
    /** true = is tick me web laga raha hai. */
    private boolean processWebs(Minecraft mc, LocalPlayer me, Player target) {
        int webIdx = KitManager.findBest(me, KitManager::isWeb, KitManager::zeroScore);

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

        if (webTimer >= WEB_INTERVAL && webIdx >= 0 && me.distanceTo(target) <= WEB_PLACE_RANGE) {
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
        if (me.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > WEB_PLACE_RANGE) return;

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
