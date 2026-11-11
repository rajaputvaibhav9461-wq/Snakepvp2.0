package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Random;
import java.util.function.Predicate;

public class ElytraMacePvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final float LOW_HP = 6.0f;          // 6 HP = 3 heart pe bhaag ke heal
    static final float HEAL_DONE_HP = 14.0f;   // itna HP hone pe wapas fight
    static final double REACH = 3.0;           // mace hit range
    static final double FLEE_SAFE_DIST = 10.0; // itni door jaake khana khayega
    static final double ALT_GAIN = 20.0;       // rocket se itna upar jao
    static final int ROCKET_GAP = 10;          // 2 rocket ke beech tick

    // states
    static final int PREP = 0, LAUNCH = 1, CLIMB = 2, TOP = 3, APPROACH = 4, HIT = 5,
            PC_LAND = 6, PC_SEQ = 7, PC_TP = 8, FALLBACK = 9;

    private final Random rng = new Random();
    private int state = PREP, stateTicks = 0, seqStep = 0, topStep = 0, jumpReq = 0;
    private int rocketCd = 0, swapCd = 0, atkCd = 0, throwCd = 0, pearlCd = 0, potCd = 0;
    private boolean healMode = false, pulse = false;
    private double climbY = 0;
    private Vec3 lastPos = null;

    // ------------------------------------------------------------ ITEM CHECKS
    static boolean isElytra(ItemStack s) { return s.is(Items.ELYTRA); }
    static boolean isMace(ItemStack s) { return s.is(Items.MACE); }
    static boolean isWind(ItemStack s) { return s.is(Items.WIND_CHARGE); }
    static boolean isPearl(ItemStack s) { return s.is(Items.ENDER_PEARL); }
    static boolean isRocket(ItemStack s) { return s.is(Items.FIREWORK_ROCKET); }

    static boolean isChest(ItemStack s) {
        return s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == EquipmentSlot.CHEST;
    }

    static int chestScore(ItemStack s) { return s.getMaxDamage(); }

    private int idx(LocalPlayer me, Predicate<ItemStack> f) {
        return KitManager.findBest(me, f, KitManager::zeroScore);
    }

    private boolean has(LocalPlayer me, Predicate<ItemStack> f) { return idx(me, f) >= 0; }

    private void hold(Minecraft mc, LocalPlayer me, Predicate<ItemStack> f) {
        if (f.test(me.getMainHandItem())) return;
        int i = idx(me, f);
        if (i >= 0) KitManager.equip(mc, i);
    }

    private boolean wearingElytra(LocalPlayer me) {
        return me.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private boolean wearingChest(LocalPlayer me) {
        return isChest(me.getItemBySlot(EquipmentSlot.CHEST));
    }

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        LocalPlayer me = mc.player;
        boolean base = has(me, ElytraMacePvp::isElytra) && has(me, ElytraMacePvp::isMace)
                && KitManager.findBest(me, ElytraMacePvp::isChest, ElytraMacePvp::chestScore) >= 0;
        boolean rocket = has(me, ElytraMacePvp::isRocket);
        boolean pearlCatch = has(me, ElytraMacePvp::isPearl) && has(me, ElytraMacePvp::isWind);
        return base && (rocket || pearlCatch);
    }

    void onActivate(Minecraft mc) {
        healMode = false;
        pulse = false;
        rocketCd = 0;
        swapCd = 0;
        atkCd = 0;
        throwCd = 0;
        pearlCd = 0;
        potCd = 0;
        to(PREP);
    }

    void onDeactivate(Minecraft mc) {
        healMode = false;
        to(PREP);
        KitManager.releaseAll(mc);
    }

    private void to(int s) {
        state = s;
        stateTicks = 0;
    }

    // ------------------------------------------------------------ MAIN TICK
    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (target == null) {
            KitManager.releaseAll(mc);
            to(PREP);
            return;
        }
        if (rocketCd > 0) rocketCd--;
        if (swapCd > 0) swapCd--;
        if (atkCd > 0) atkCd--;
        if (throwCd > 0) throwCd--;
        if (pearlCd > 0) pearlCd--;
        if (potCd > 0) potCd--;

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        int potIdx = KitManager.findBest(me, NethpotPvp::isHealPot, NethpotPvp::potScore);
        int pearlIdx = idx(me, ElytraMacePvp::isPearl);
        boolean canHeal = foodIdx >= 0 || potIdx >= 0 || pearlIdx >= 0;
        float hp = me.getHealth();

        if (!healMode && hp <= LOW_HP && canHeal) {
            healMode = true;
            to(PREP);
        }
        if (healMode && (hp >= HEAL_DONE_HP || !canHeal)) {
            healMode = false;
            mc.options.keyUse.setDown(false);
        }

        KitManager.stopMovement(mc);
        jumpReq = 0;
        if (healMode) heal(mc, me, target, foodIdx, potIdx, pearlIdx);
        else fight(mc, me, target);
        applyJump(mc);
    }

    /** 0 = jump band, 1 = jump dabaye rakho, 2 = baar baar dabao (hawa me elytra kholne ke liye). */
    private void applyJump(Minecraft mc) {
        if (jumpReq == 2) {
            pulse = !pulse;
            mc.options.keyJump.setDown(pulse);
        } else {
            pulse = false;
            mc.options.keyJump.setDown(jumpReq == 1);
        }
    }

    // ------------------------------------------------------------ FIGHT (state machine)
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        stateTicks++;
        mc.options.keyUse.setDown(false);
        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());

        switch (state) {
            case PREP -> { // rocket ho to rocket wala, warna pearl catching
                boolean rocket = has(me, ElytraMacePvp::isRocket);
                boolean pc = has(me, ElytraMacePvp::isPearl) && has(me, ElytraMacePvp::isWind);
                if (rocket) to(LAUNCH);
                else if (pc) to(PC_LAND);
                else to(FALLBACK);
            }

            case LAUNCH -> { // elytra pehno, upar dekho, jump + double jump
                if (!wear(mc, me, true)) return;
                KitManager.setRotation(me, yawTo(me, target), -85f);
                if (me.isFallFlying()) {
                    climbY = Math.max(me.getY(), target.getY()) + ALT_GAIN;
                    to(CLIMB);
                } else if (me.onGround()) {
                    jumpReq = 1;
                } else {
                    jumpReq = 2;
                }
                if (stateTicks > 100) to(PREP);
            }

            case CLIMB -> { // rocket se upar
                if (!me.isFallFlying()) {
                    if (stateTicks > 5) to(LAUNCH);
                    else jumpReq = 2;
                    return;
                }
                KitManager.setRotation(me, yawTo(me, target), -85f);
                boost(mc, me);
                if (me.getY() >= climbY || stateTicks > 160) {
                    topStep = 0;
                    to(TOP);
                }
            }

            case TOP -> { // chestplate pehno, phir elytra pehno, enemy pe aim
                KitManager.aimAt(me, target.getBoundingBox().getCenter());
                if (me.onGround() && stateTicks > 3) {
                    to(PREP);
                    return;
                }
                if (topStep == 0) {
                    if (wear(mc, me, false)) topStep = 1;
                } else if (topStep == 1) {
                    if (wear(mc, me, true)) topStep = 2;
                } else {
                    if (me.isFallFlying()) to(APPROACH);
                    else jumpReq = 2;
                }
                if (stateTicks > 80) to(PREP);
            }

            case APPROACH -> { // enemy pe aim, rocket se paas
                if (!wear(mc, me, true)) return;
                KitManager.aimAt(me, target.getBoundingBox().getCenter());
                if (me.onGround()) {
                    if (stateTicks > 3) to(PREP);
                    return;
                }
                if (!me.isFallFlying()) {
                    jumpReq = 2;
                    return;
                }
                double speed = me.getDeltaMovement().length();
                if (dist <= 4.0 + speed * 3.0) { // hit range me aane se pehle
                    to(HIT);
                    return;
                }
                boost(mc, me);
                if (stateTicks > 240) to(PREP);
            }

            case HIT -> { // chestplate pehno aur mace se maro
                KitManager.aimAt(me, target.getBoundingBox().getCenter());
                if (!wear(mc, me, false)) return;
                if (me.onGround()) walk(mc, true);
                if (smash(mc, me, target, dist)) {
                    to(PREP); // hit ke baad phir upar
                    return;
                }
                if (stateTicks > 40) to(PREP);
            }

            case PC_LAND -> { // ek jagah khade ho jao (chestplate pehne)
                if (wearingElytra(me) && (me.isFallFlying() || me.onGround())) {
                    wear(mc, me, false);
                    return;
                }
                if (!me.onGround()) return; // girne do
                KitManager.aimAt(me, target.getBoundingBox().getCenter());
                if (pearlCd == 0 && throwCd == 0) {
                    seqStep = 0;
                    to(PC_SEQ);
                }
                if (stateTicks > 200) to(PREP);
            }

            case PC_SEQ -> { // neeche wind charge, upar pearl, turant wind charge
                if (throwCd > 0) return;
                float yaw = me.getYRot();
                if (seqStep == 0) {
                    if (throwItem(mc, me, ElytraMacePvp::isWind, yaw, 90f)) {
                        seqStep = 1;
                        throwCd = 2;
                    } else to(PREP);
                } else if (seqStep == 1) {
                    if (throwItem(mc, me, ElytraMacePvp::isPearl, yaw, -90f)) {
                        seqStep = 2;
                        throwCd = 1;
                        pearlCd = 40;
                    } else to(PREP);
                } else {
                    throwItem(mc, me, ElytraMacePvp::isWind, yaw, 90f);
                    lastPos = me.position();
                    to(PC_TP);
                }
            }

            case PC_TP -> { // TP hone ka wait, hote hi elytra pehen ke enemy ki taraf
                Vec3 p = me.position();
                if (lastPos != null && p.distanceTo(lastPos) > 6.0) {
                    to(APPROACH);
                    return;
                }
                lastPos = p;
                if (stateTicks > 220) to(PREP);
            }

            case FALLBACK -> { // kuch nahi bacha: zameen pe mace fight
                KitManager.aimAt(me, target.getBoundingBox().getCenter());
                if (!wear(mc, me, false)) return;
                walk(mc, dist > 3.0);
                smash(mc, me, target, dist);
                if (stateTicks > 20) to(PREP);
            }

            default -> to(PREP);
        }
  }
    // ------------------------------------------------------------ HELPERS
    /** Chestplate / elytra pehenna (hath me pakad ke right-click = swap). true = pehna hua hai. */
    private boolean wear(Minecraft mc, LocalPlayer me, boolean elytra) {
        if (elytra ? wearingElytra(me) : wearingChest(me)) return true;
        if (swapCd > 0) return false;
        int i = elytra
                ? idx(me, ElytraMacePvp::isElytra)
                : KitManager.findBest(me, ElytraMacePvp::isChest, ElytraMacePvp::chestScore);
        if (i < 0) return false;
        KitManager.equip(mc, i);
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        swapCd = 2;
        return false; // agle tick me check hoga
    }

    /** Fire rocket se boost (elytra me). */
    private void boost(Minecraft mc, LocalPlayer me) {
        if (rocketCd > 0) return;
        int i = idx(me, ElytraMacePvp::isRocket);
        if (i < 0) return;
        KitManager.equip(mc, i);
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        rocketCd = ROCKET_GAP;
    }

    private boolean throwItem(Minecraft mc, LocalPlayer me, Predicate<ItemStack> f, float yaw, float pitch) {
        int i = idx(me, f);
        if (i < 0) return false;
        KitManager.equip(mc, i);
        KitManager.setRotation(me, yaw, pitch);
        mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** Shield pe axe, warna mace. true = mace se hit lag gaya. */
    private boolean smash(Minecraft mc, LocalPlayer me, Player target, double dist) {
        int axeIdx = idx(me, KitManager::isAxe);
        boolean useAxe = target.isBlocking() && axeIdx >= 0;
        if (useAxe) {
            if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
        } else {
            hold(mc, me, ElytraMacePvp::isMace);
        }
        if (dist <= REACH && atkCd == 0) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
            atkCd = 3;
            return !useAxe;
        }
        return false;
    }

    private void walk(Minecraft mc, boolean sprint) {
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(sprint);
    }

    private static float yawTo(Player me, Player t) {
        return (float) (Math.toDegrees(Math.atan2(t.getZ() - me.getZ(), t.getX() - me.getX())) - 90.0);
    }

    // ------------------------------------------------------------ HEAL (pot / pearl / bhaagna / food)
    private void heal(Minecraft mc, LocalPlayer me, Player target, int foodIdx, int potIdx, int pearlIdx) {
        // elytra utaaro (hawa me ho ya zameen pe)
        if (wearingElytra(me) && (me.isFallFlying() || me.onGround())) {
            wear(mc, me, false);
            return;
        }
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
        walk(mc, true);
        if (me.onGround() && rng.nextInt(12) == 0) jumpReq = 1;
    }
        }
