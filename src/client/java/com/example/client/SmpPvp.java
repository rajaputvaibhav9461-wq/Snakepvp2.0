package com.snakepvp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Random;

public class SmpPvp {
    // ---- settings (yahan se badal sakte ho) ----
    static final float LOW_HP = 3.0f;          // itne HP pe bhaag ke heal (3 HP = 1.5 heart)
    static final float HEAL_DONE_HP = 14.0f;   // itna HP hone pe wapas fight
    static final double REACH = 2.95;          // hit range
    static final double CHASE_DIST = 3.5;      // isse door ho to sprint karke pas jao
    static final double FLEE_SAFE_DIST = 8.0;  // itni door jaake khana khayega
    static final int POT_COOLDOWN = 12;        // 2 pot ke beech ke tick
    static final int WEB_INTERVAL = 100;       // 100 tick = 5 second
    static final double WEB_PLACE_RANGE = 4.5;

    // buff pots: {potion naam me ye hona chahiye, effect ka naam}
    static final String[][] BUFFS = {
            { "strength", "strength" },
            { "swiftness", "speed" },
            { "fire_resistance", "fire_resistance" }
    };

    private final Random rng = new Random();
    private final ArrayDeque<BlockPos> webQueue = new ArrayDeque<>();
    private int strafeDir = 1, strafeTimer = 0, backTimer = 0, webTimer = 0;
    private int potCd = 0, buffCd = 0, pearlCd = 0, offCd = 0;
    private boolean healMode = false;

    // ------------------------------------------------------------ LIFECYCLE
    boolean matches(Minecraft mc) {
        return KitManager.findBest(mc.player, KitManager::isSword, KitManager::swordScore) >= 0;
    }

    void onActivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        backTimer = 0;
        strafeTimer = 0;
        potCd = 0;
        buffCd = 0;
        pearlCd = 0;
        offCd = 0;
        webTimer = WEB_INTERVAL; // pehla web turant
    }

    void onDeactivate(Minecraft mc) {
        webQueue.clear();
        healMode = false;
        KitManager.releaseAll(mc);
    }

    void tick(Minecraft mc, Player target) {
        LocalPlayer me = mc.player;
        if (potCd > 0) potCd--;
        if (buffCd > 0) buffCd--;
        if (pearlCd > 0) pearlCd--;
        if (offCd > 0) offCd--;

        ensureOffhand(mc, me); // totem hamesha offhand me

        if (target == null) {
            KitManager.releaseAll(mc);
            webQueue.clear();
            return;
        }
        webTimer++;

        int foodIdx = KitManager.findBest(me, s -> KitManager.isFood(me, s), KitManager::foodScore);
        int potIdx = KitManager.findBest(me, NethpotPvp::isHealPot, NethpotPvp::potScore);
        int pearlIdx = KitManager.findBest(me, s -> s.is(Items.ENDER_PEARL), KitManager::zeroScore);
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

    // ------------------------------------------------------------ OFFHAND (totem / shield)
    private void ensureOffhand(Minecraft mc, LocalPlayer me) {
        if (offCd > 0) return;
        if (me.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;

        int i = KitManager.findBest(me, s -> s.is(Items.TOTEM_OF_UNDYING), KitManager::zeroScore);
        if (i < 0) { // totem nahi to shield
            if (me.getOffhandItem().is(Items.SHIELD)) return;
            i = KitManager.findBest(me, s -> s.is(Items.SHIELD), KitManager::zeroScore);
        }
        if (i < 0) return;

        int slot = i < 9 ? 36 + i : i;
        mc.gameMode.handleInventoryMouseClick(
                me.inventoryMenu.containerId, slot, 40, ClickType.SWAP, me); // 40 = offhand
        offCd = 10;
    }

    // ------------------------------------------------------------ BUFF POTS (strength / speed / fire resistance)
    private boolean hasEffect(LocalPlayer me, String key) {
        for (MobEffectInstance e : me.getActiveEffects()) {
            String p = e.getEffect().unwrapKey().map(k -> k.location().getPath()).orElse("");
            if (p.equals(key)) return true;
        }
        return false;
    }

    /** Jo buff nahi chal raha uska splash pot apne pair pe. true = is tick pot fenka. */
    private boolean tryBuff(Minecraft mc, LocalPlayer me) {
        if (potCd > 0 || buffCd > 0) return false;
        for (String[] b : BUFFS) {
            if (hasEffect(me, b[1])) continue;
            int i = KitManager.findBest(me, s -> NethpotPvp.potPath(s).contains(b[0]), KitManager::zeroScore);
            if (i < 0) continue;
            mc.options.keyUse.setDown(false);
            KitManager.equip(mc, i);
            KitManager.setRotation(me, me.getYRot(), 90f); // neeche (apne pair pe)
            mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
            me.swing(InteractionHand.MAIN_HAND);
            buffCd = 14;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ FIGHT (sirf critical hit)
    private void fight(Minecraft mc, LocalPlayer me, Player target) {
        mc.options.keyUse.setDown(false);

        double dist = KitManager.boxDist(me.getEyePosition(), target.getBoundingBox());

        // enemy paas nahi hai to buff pots lagao
        if (dist > CHASE_DIST && tryBuff(mc, me)) return;

        if (processWebs(mc, me, target)) return;

        // weapon: shield up to axe se todo, warna sword (poori inventory se)
        int axeIdx = KitManager.findBest(me, KitManager::isAxe, KitManager::zeroScore);
        if (target.isBlocking() && axeIdx >= 0) {
            if (!KitManager.isAxe(me.getMainHandItem())) KitManager.equip(mc, axeIdx);
        } else if (!KitManager.isSword(me.getMainHandItem())) {
            int swordIdx = KitManager.findBest(me, KitManager::isSword, KitManager::swordScore);
            if (swordIdx >= 0) KitManager.equip(mc, swordIdx);
        }

        // aim: lock hone ke baad hamesha target pe
        KitManager.aimAt(me, target.getBoundingBox().getCenter());

        boolean inWeb = KitManager.inWeb(mc.level, me);

        // range se bahar (ya enemy bhaag raha) -> sprint karke pas jao
        if (dist > CHASE_DIST) {
            boolean far = dist > 8.0;
            move(mc, me, true, false, far ? 0 : strafeDir, true, false);
            return;
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
        // crit ke liye: attack charge hone se thoda pehle jump (girte waqt hit lagega)
        boolean jump = me.onGround() && !inWeb && me.getAttackStrengthScale(8f) >= 0.95f;
        move(mc, me, fwd, back, strafeDir, false, jump); // sprint band (sprint me crit nahi lagta)

        // attack: sirf critical hit (axe se shield todna ya cobweb me fase ho to turant)
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

    // ------------------------------------------------------------ COBWEB PLACE
    /** true = is tick me web laga raha hai (fight skip). */
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
