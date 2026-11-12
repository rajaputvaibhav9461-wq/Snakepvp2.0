package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Random;

public class PCrtingPvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final float LOW_HP = 3.0f;          // itne HP pe bhaag ke heal (3 HP = 1.5 heart)
    static final float HEAL_DONE_HP = 14.0f;   // itna HP hone pe wapas fight
    static final double REACH = 2.95;          // hit range
    static final double CHASE_DIST = 3.5;      // isse door ho to sprint karke pas jao
    static final double FLEE_SAFE_DIST = 8.0;  // itni door jaake khana khayega
    static final int POT_COOLDOWN = 12;        // 2 pot ke beech ke tick
    static final int KNOCK_WINDOW = 30;        // hit khane ke baad itne tick crit ka mauka

    private final Random rng = new Random();
    private int strafeDir = 1, strafeTimer = 0, backTimer = 0, potCd = 0, knockTicks = 0;
    private boolean healMode = false;

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        return KitManager.findBest(mc.player, KitManager::isSword, KitManager::swordScore) >= 0;
    }

    void onActivate(Minecraft mc) {
        healMode = false;
        backTimer = 0;
        strafeTimer = 0;
        potCd = 0;
        knockTicks = 0;
    }

    void onDeactivate(Minecraft mc) {
        healMode = false;
        KitManager.releaseAll(mc);
    }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (target == null) {
            KitManager.releaseAll(mc);
            return;
        }
        if (potCd > 0) potCd--;

        // enemy ne hit mara -> crit ka mauka shuru
        if (me.hurtTime > 0) knockTicks = KNOCK_WINDOW;
        else if (knockTicks > 0) knockTicks--;

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        int potIdx = KitManager.findBest(me, NethpotPvp::isHealPot, NethpotPvp::potScore);
        boolean canHeal = foodIdx >= 0 || potIdx >= 0;
        float hp = me.getHealth();

        if (!healMode && hp <= LOW_HP && canHeal) healMode = true;
        if (healMode && (hp >= HEAL_DONE_HP || !canHeal)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }

        if (healMode) heal(mc, me, target, foodIdx, potIdx);
        else fight(mc, me, target);
    }

    // ------------------------------------------------------------ FIGHT (jump nahi, sirf girte waqt crit)
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);

        // weapon: shield pe axe, warna sword (poori inventory se)
        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
        if (target.isBlocking() && axeIdx >= 0) {
            if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
        } else if (!KitManager.isSword(me.getMainHandItem())) {
            int swordIdx = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
            if (swordIdx >= 0) KitManager.equip(mc, swordIdx);
        }

        // aim: lock hone ke baad hamesha target pe
        KitManager.aimAt(me, target.getBoundingBox().getCenter());

        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());

        // range se bahar (ya enemy bhaag raha) -> pas jao. Hawa me sprint band (crit ke liye)
        if (dist > CHASE_DIST) {
            boolean far = dist > 8.0;
            move(mc, me, true, false, far ? 0 : strafeDir, me.onGround());
            attackIfCrit(mc, me, target, dist);
            return;
        }

        // movement: W + strafe A/D + kabhi kabhi S, taaki enemy ka aim na lage (jump nahi)
        if (--strafeTimer <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeTimer = 8 + rng.nextInt(15);
        }
        if (backTimer > 0) backTimer--;
        else if (dist < 3.0 && rng.nextInt(30) == 0) backTimer = 4 + rng.nextInt(4);

        boolean back = backTimer > 0 || dist < 1.2;
        boolean fwd = dist > 2.0 && !back;
        move(mc, me, fwd, back, strafeDir, false); // sprint band

        attackIfCrit(mc, me, target, dist);
    }

    /** Hit sirf tab: hit khane ke baad upar uchhal ke jab neeche aa rahe ho (crit). Shield pe axe = turant. */
    private void attackIfCrit(Minecraft mc, LocalPlayer me, Player target, double dist) {
        if (dist > REACH || me.getAttackStrengthScale(0.5f) < 0.9f) return;

        boolean axe = KitManager.isAxe(me.getMainHandItem());
        boolean crit = knockTicks > 0 && !me.onGround() && me.fallDistance > 0
                && me.getDeltaMovement().y < 0 && !me.onClimbable() && !me.isInWater()
                && !me.isSprinting();
        if (axe || crit) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            knockTicks = 0;
        }
    }

    // ------------------------------------------------------------ HEAL (pot + food)
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx, int potIdx) {
        double dist = Math.sqrt(me.distanceToSqr(target));
        boolean eating = me.isUsingItem() && KitManager.hasFoodComponent(me.getMainHandItem());

        // 1) healing pot hai to apne pair pe fenko
        if (potIdx >= 0 && potCd == 0 && !eating) {
            mc.options.keyUse.setDown(false);
            KitManager.equip(mc, potIdx);
            KitManager.setRotation(me, me.getYRot(), 90f); // neeche (apne pair pe)
            mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
            me.swing(InteractionHand.MAIN_HAND);
            potCd = POT_COOLDOWN;
            return;
        }
        // 2) enemy paas hai to bhaago
        if (dist < FLEE_SAFE_DIST) {
            mc.options.keyUse.setDown(false);
            flee(mc, me, target);
            return;
        }
        // 3) safe doori: khana khao
        KitManager.stopMovement(mc);
        KitManager.aimAt(me, target.getBoundingBox().getCenter());
        if (foodIdx >= 0) {
            if (!eating) KitManager.equip(mc, foodIdx);
            mc.options.keyUse.setDown(true);
        } else {
            mc.options.keyUse.setDown(false);
        }
    }

    private void flee(Minecraft mc, LocalPlayer me, Player target) {
        double ax = me.getX() - target.getX();
        double az = me.getZ() - target.getZ();
        if (ax * ax + az * az < 1e-4) ax = 1;
        float yaw = (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
        KitManager.setRotation(me, yaw, 0f); // dushman se ulta dekho aur bhaago
        move(mc, me, true, false, 0, true);
    }

    // ------------------------------------------------------------ MOVE (cobweb se bachte huve, jump nahi)
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
        mc.options.keyJump.setDown(false); // jump kabhi nahi
        if (!(sprint && fwd)) me.setSprinting(false);
    }
  }
