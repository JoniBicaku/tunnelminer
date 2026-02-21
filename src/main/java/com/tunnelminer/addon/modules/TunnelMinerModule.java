package com.tunnelminer.addon.modules;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

import java.util.*;
import java.util.function.Predicate;

import com.tunnelminer.addon.TunnelMinerAddon;

/**
 * TunnelMiner — digs a straight tunnel block by block from current position
 * to (targetX, currentY, targetZ). Travels X axis first, then Z axis.
 *
 * Loop per block step:
 *   1. MINE  — break every block in the tunnel cross-section one step ahead.
 *              Called every tick until all those blocks are air.
 *   2. WALK  — move the player exactly one block forward.
 *              Called every tick until player reaches the next block centre.
 *   3. FILL  — re-place blocks behind (optional).
 *   Then repeat from 1 until destX (then destZ) is reached.
 */
public class TunnelMinerModule extends Module {

    public static TunnelMinerModule INSTANCE;

    // ── Settings ──────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRestock = settings.createGroup("Restock");
    private final SettingGroup sgTiming  = settings.createGroup("Timing");

    private final Setting<Integer> targetX = sgGeneral.add(new IntSetting.Builder()
        .name("target-x").description("Target X coordinate.").defaultValue(0).build());

    private final Setting<Integer> targetZ = sgGeneral.add(new IntSetting.Builder()
        .name("target-z").description("Target Z coordinate.").defaultValue(0).build());

    private final Setting<Integer> tunnelHeight = sgGeneral.add(new IntSetting.Builder()
        .name("tunnel-height").description("Height of the tunnel in blocks (2 = player fits).")
        .defaultValue(2).min(2).max(4).sliderMax(4).build());

    private final Setting<Boolean> fillBehind = sgGeneral.add(new BoolSetting.Builder()
        .name("fill-behind")
        .description("Re-place the same block types behind you after each step.")
        .defaultValue(true).build());

    private final Setting<Boolean> lavaAvoidance = sgGeneral.add(new BoolSetting.Builder()
        .name("lava-avoidance")
        .description("Detour sideways when lava is detected up to 5 blocks ahead.")
        .defaultValue(true).build());

    private final Setting<Integer> lavaDetourDist = sgGeneral.add(new IntSetting.Builder()
        .name("lava-detour-blocks")
        .description("How many blocks sideways to go to avoid lava.")
        .defaultValue(3).min(1).max(6).sliderMax(6).build());

    private final Setting<Boolean> useShulkers = sgRestock.add(new BoolSetting.Builder()
        .name("use-shulkers")
        .description("Open shulker boxes to restock pickaxes when the last one is low.")
        .defaultValue(false).build());

    private final Setting<Boolean> useEnderChest = sgRestock.add(new BoolSetting.Builder()
        .name("use-ender-chest")
        .description("Open ender chests to restock pickaxes when the last one is low.")
        .defaultValue(false).build());

    private final Setting<Integer> lowDurability = sgRestock.add(new IntSetting.Builder()
        .name("low-durability")
        .description("Durability remaining on the last pickaxe that triggers a restock.")
        .defaultValue(100).min(1).max(1561).sliderMin(1).sliderMax(1561).build());

    private final Setting<Integer> minPickaxes = sgRestock.add(new IntSetting.Builder()
        .name("min-pickaxes")
        .description("How many pickaxes to grab from the container before closing it.")
        .defaultValue(3).min(1).max(10).sliderMax(10).build());

    private final Setting<Integer> breaksPerTick = sgTiming.add(new IntSetting.Builder()
        .name("breaks-per-tick").description("Block break attempts sent per tick.")
        .defaultValue(1).min(1).max(5).sliderMax(5).build());

    private final Setting<Integer> placesPerTick = sgTiming.add(new IntSetting.Builder()
        .name("places-per-tick").description("Block placements attempted per tick.")
        .defaultValue(1).min(1).max(5).sliderMax(5).build());

    private final Setting<Integer> placeDelay = sgTiming.add(new IntSetting.Builder()
        .name("place-delay").description("Ticks to wait between fill placements.")
        .defaultValue(1).min(0).sliderMax(10).build());

    private final Setting<Integer> invDelay = sgTiming.add(new IntSetting.Builder()
        .name("inventory-delay").description("Ticks to wait between inventory actions.")
        .defaultValue(3).min(0).sliderMax(10).build());

    // ── State machine ─────────────────────────────────────────────────────────

    private enum Phase {
        INIT,
        PLACE_FLOOR,    // place netherrack below player when floor is missing
        MINE,           // break blocks one step ahead — loops every tick until clear
        WALK,           // move forward one block — loops every tick until arrived
        FILL,           // re-place mined blocks behind — then goes back to MINE
        DETOUR_MINE, DETOUR_WALK,
        DETOUR_RETURN_MINE, DETOUR_RETURN_WALK,
        RESTOCK_CLEAR, RESTOCK_PLACE, RESTOCK_WAIT,
        RESTOCK_OPEN, RESTOCK_LOOT, RESTOCK_CLOSE,
        RESTOCK_BREAK, RESTOCK_PICKUP,
        DONE
    }

    private Phase phase;

    // Where we are going
    private int destX, destZ, totalBlocks;

    // Which axis we are currently on (X first, then Z)
    private boolean onXAxis;

    // Walk target set each time we enter WALK
    private double walkTargetX, walkTargetZ;

    // Phase to return to after placing a floor block
    private Phase phaseAfterFloor;

    // Detour state
    private Direction detourDir;   // the sideways direction we stepped
    private int       detourSteps; // how many steps we've taken sideways

    // Fill log: original block states of blocks we mined, keyed by position
    private final LinkedHashMap<BlockPos, BlockState> fillLog = new LinkedHashMap<>();

    // Timers
    private int placeTimer, invTimer, waitTicks;
    private static final int MAX_WAIT = 100;

    // Inventory
    private int       pickSlot    = -1;
    private BlockPos  containerPos;
    private boolean   restockEC;

    // Stats for HUD
    private long startMs;
    private int  blocksMined;

    // ── Constructor ───────────────────────────────────────────────────────────

    public TunnelMinerModule() {
        super(TunnelMinerAddon.CATEGORY, "tunnel-miner",
              "Mines a tunnel block-by-block to target XZ coordinates at the same Y.");
        INSTANCE = this;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        destX       = targetX.get();
        destZ       = targetZ.get();
        onXAxis     = true;
        detourDir   = null;
        detourSteps = 0;
        fillLog.clear();
        placeTimer = invTimer = waitTicks = 0;
        pickSlot   = -1;
        containerPos = null;
        startMs    = System.currentTimeMillis();
        blocksMined = 0;

        int px = MathHelper.floor(mc.player.getX());
        int pz = MathHelper.floor(mc.player.getZ());
        totalBlocks = Math.abs(destX - px) + Math.abs(destZ - pz);
        phase = Phase.INIT;
        info("Tunnel to X=" + destX + " Z=" + destZ + " (" + totalBlocks + " blocks)");
    }

    @Override public void onDeactivate() { info("Stopped."); }

    @EventHandler
    private void onGameLeft(GameLeftEvent e) { if (isActive()) toggle(); }

    // ── Main tick ─────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) { if (isActive()) toggle(); return; }
        if (placeTimer > 0) { placeTimer--; return; }
        if (invTimer   > 0) { invTimer--;   return; }

        switch (phase) {
            case INIT               -> initPhase();
            case PLACE_FLOOR        -> placeFloorPhase();
            case MINE               -> minePhase();
            case WALK               -> walkPhase();
            case FILL               -> fillPhase();
            case DETOUR_MINE        -> detourMinePhase(false);
            case DETOUR_WALK        -> detourWalkPhase(false);
            case DETOUR_RETURN_MINE -> detourMinePhase(true);
            case DETOUR_RETURN_WALK -> detourWalkPhase(true);
            case RESTOCK_CLEAR      -> restockClear();
            case RESTOCK_PLACE      -> restockPlace();
            case RESTOCK_WAIT       -> restockWait();
            case RESTOCK_OPEN       -> restockOpen();
            case RESTOCK_LOOT       -> restockLoot();
            case RESTOCK_CLOSE      -> restockClose();
            case RESTOCK_BREAK      -> restockBreak();
            case RESTOCK_PICKUP     -> restockPickup();
            case DONE               -> { info("Destination reached!"); toggle(); }
        }
    }

    // ── INIT ──────────────────────────────────────────────────────────────────

    private void initPhase() {
        if (countPickaxes() == 0) { warning("No pickaxes — stopping."); toggle(); return; }
        pickSlot = equipBestPickaxe();
        phase = Phase.MINE;
    }

    // ── MINE ──────────────────────────────────────────────────────────────────
    // This runs EVERY tick. It:
    //   • Checks if we've reached the target on the current axis → switch axis or finish
    //   • Breaks any solid blocks in the tunnel profile one step ahead
    //   • When the profile is clear, transitions to WALK

    private void minePhase() {
        // Restock check
        if (needsRestock()) { phase = Phase.RESTOCK_CLEAR; return; }

        // Floor check — place netherrack if nothing below the player
        if (needsFloor()) { phaseAfterFloor = Phase.MINE; phase = Phase.PLACE_FLOOR; return; }

        int px = MathHelper.floor(mc.player.getX());
        int py = MathHelper.floor(mc.player.getY());
        int pz = MathHelper.floor(mc.player.getZ());

        // ── Axis arrival checks ──
        if (onXAxis) {
            if (px == destX) {
                // Finished X leg — switch to Z
                if (pz == destZ) { phase = Phase.DONE; return; }
                onXAxis = false;
                info("X axis done — now heading Z...");
                // Don't return: fall through so we immediately mine on Z in this tick
            }
        } else {
            if (pz == destZ) { phase = Phase.DONE; return; }
        }

        Direction fwd = fwd();

        // ── Lava avoidance ──
        if (lavaAvoidance.get() && lavaAhead(px, py, pz, fwd)) {
            detourDir   = fwd.rotateYClockwise();
            detourSteps = 0;
            info("Lava ahead — detouring " + lavaDetourDist.get() + " blocks sideways.");
            phase = Phase.DETOUR_MINE;
            return;
        }

        // ── Collect blocks to break one step ahead ──
        List<BlockPos> toBreak = new ArrayList<>();
        for (int h = 0; h < tunnelHeight.get(); h++) {
            BlockPos bp = new BlockPos(px + fwd.getOffsetX(), py + h, pz + fwd.getOffsetZ());
            BlockState bs = mc.world.getBlockState(bp);
            if (!bs.isAir() && bs.getBlock() != Blocks.BEDROCK) {
                toBreak.add(bp);
            }
        }

        if (!toBreak.isEmpty()) {
            // Still blocks in the way — break them and come back next tick
            ensurePickaxe();
            int n = Math.min(breaksPerTick.get(), toBreak.size());
            for (int i = 0; i < n; i++) {
                BlockPos bp = toBreak.get(i);
                // Log original state BEFORE breaking so fill works correctly
                if (fillBehind.get()) fillLog.putIfAbsent(bp, mc.world.getBlockState(bp));
                final BlockPos fBp = bp;
                Rotations.rotate(Rotations.getYaw(bp), Rotations.getPitch(bp),
                    () -> BlockUtils.breakBlock(fBp, true));
            }
            return; // ← come back next tick, keep breaking until toBreak is empty
        }

        // ── Profile is clear — walk one step forward ──
        blocksMined++;
        startWalk(px, pz, fwd);
    }

    // ── WALK ──────────────────────────────────────────────────────────────────
    // Runs every tick. Directly sets player velocity toward walkTarget.
    // When close enough, snaps to centre and returns to MINE.

    private void startWalk(int fromX, int fromZ, Direction fwd) {
        walkTargetX = (fromX + fwd.getOffsetX()) + 0.5;
        walkTargetZ = (fromZ + fwd.getOffsetZ()) + 0.5;
        phase = Phase.WALK;
    }

    private void walkPhase() {
        // Floor check — place netherrack if nothing below the player
        if (needsFloor()) { phaseAfterFloor = Phase.WALK; phase = Phase.PLACE_FLOOR; return; }

        moveToward(walkTargetX, walkTargetZ, () -> {
            if (fillBehind.get() && !fillLog.isEmpty()) phase = Phase.FILL;
            else phase = Phase.MINE;
        });
    }

    /**
     * Moves the player toward (targetX, targetZ) by directly setting position each tick.
     * This works regardless of Baritone or other mods because we override position
     * after their tick via setPosition + packet. onArrival runs when dist < 0.1.
     */
    private void moveToward(double targetX, double targetZ, Runnable onArrival) {
        double px   = mc.player.getX();
        double py   = mc.player.getY();
        double pz   = mc.player.getZ();
        double dx   = targetX - px;
        double dz   = targetZ - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist < 0.1) {
            // Snap exactly to target, zero out horizontal velocity
            mc.player.setPosition(targetX, py, targetZ);
            mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                targetX, py, targetZ, mc.player.isOnGround(), mc.player.horizontalCollision));
            onArrival.run();
            return;
        }

        // Step size: 0.25 blocks/tick — fast enough, small enough to not overshoot
        double step = Math.min(0.25, dist);
        double nx = px + (dx / dist) * step;
        double nz = pz + (dz / dist) * step;

        mc.player.setPosition(nx, py, nz);
        mc.player.setVelocity(0, mc.player.getVelocity().y, 0); // kill other mods' velocity

        float yaw = (float) Math.toDegrees(Math.atan2(-(nx - px), nz - pz));
        mc.player.setYaw(yaw);
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(
            nx, py, nz, yaw, mc.player.getPitch(),
            mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    // ── PLACE_FLOOR ───────────────────────────────────────────────────────────
    // If the block directly below the player is air, place netherrack there
    // before doing anything else.

    private boolean needsFloor() {
        if (mc.player == null || mc.world == null) return false;
        BlockPos below = new BlockPos(
            MathHelper.floor(mc.player.getX()),
            MathHelper.floor(mc.player.getY()) - 1,
            MathHelper.floor(mc.player.getZ()));
        return mc.world.getBlockState(below).isAir();
    }

    private void placeFloorPhase() {
        BlockPos below = new BlockPos(
            MathHelper.floor(mc.player.getX()),
            MathHelper.floor(mc.player.getY()) - 1,
            MathHelper.floor(mc.player.getZ()));

        // Already filled (another mod / gravity block / etc.)
        if (!mc.world.getBlockState(below).isAir()) {
            phase = phaseAfterFloor;
            return;
        }

        // Find netherrack in inventory
        int slot = findInInv(s -> !s.isEmpty() && s.isOf(Items.NETHERRACK));
        if (slot == -1) {
            warning("No netherrack to place floor — stopping.");
            toggle();
            return;
        }

        int hb = toHotbar(slot);
        if (hb == -1) { warning("Hotbar full — can't place floor."); toggle(); return; }
        InvUtils.swap(hb, false);

        // Place by clicking on the side of the block one below (use DOWN face of current pos)
        final int fHb = hb;
        final BlockPos fBelow = below;
        // We place against the bottom face of the player's current block
        BlockPos standingOn = new BlockPos(
            MathHelper.floor(mc.player.getX()),
            MathHelper.floor(mc.player.getY()),
            MathHelper.floor(mc.player.getZ()));
        Rotations.rotate(Rotations.getYaw(fBelow), Rotations.getPitch(fBelow),
            () -> BlockUtils.place(fBelow, Hand.MAIN_HAND, fHb, true, 0, true, true, false));

        placeTimer = placeDelay.get() > 0 ? placeDelay.get() : 1;
        ensurePickaxe();
        phase = phaseAfterFloor;
    }

    // ── FILL ──────────────────────────────────────────────────────────────────

    private void fillPhase() {
        if (!fillBehind.get() || fillLog.isEmpty()) { phase = Phase.MINE; return; }

        int px = MathHelper.floor(mc.player.getX());
        int pz = MathHelper.floor(mc.player.getZ());
        int placed = 0;

        Iterator<Map.Entry<BlockPos, BlockState>> it = fillLog.entrySet().iterator();
        while (it.hasNext() && placed < placesPerTick.get()) {
            Map.Entry<BlockPos, BlockState> e = it.next();
            BlockPos pos = e.getKey();

            // Skip positions player is still standing on
            if (Math.abs(pos.getX() - px) <= 1 && Math.abs(pos.getZ() - pz) <= 1) continue;
            // Already filled
            if (!mc.world.getBlockState(pos).isAir()) { it.remove(); continue; }
            // Needs a surface to place against
            if (mc.world.getBlockState(pos.down()).isAir()) { it.remove(); continue; }

            Block want = e.getValue().getBlock();
            int slot = findInInv(s -> !s.isEmpty() && Block.getBlockFromItem(s.getItem()) == want);
            if (slot == -1) { it.remove(); continue; } // don't have this block type

            int hb = toHotbar(slot);
            if (hb == -1) { continue; }
            InvUtils.swap(hb, false);

            final BlockPos fPos = pos;
            final int fHb = hb;
            Rotations.rotate(Rotations.getYaw(pos.down()), Rotations.getPitch(pos.down()),
                () -> BlockUtils.place(fPos, Hand.MAIN_HAND, fHb, true, 0, true, true, false));

            it.remove();
            placed++;
        }

        if (placed > 0) {
            placeTimer = placeDelay.get();
            ensurePickaxe();
        }

        if (fillLog.isEmpty()) phase = Phase.MINE;
    }

    // ── DETOUR (lava avoidance) ────────────────────────────────────────────────

    private void detourMinePhase(boolean returning) {
        int px = MathHelper.floor(mc.player.getX());
        int py = MathHelper.floor(mc.player.getY());
        int pz = MathHelper.floor(mc.player.getZ());
        Direction fwd  = fwd();
        Direction side = returning ? detourDir.getOpposite() : detourDir;

        if (!returning) {
            if (!lavaAhead(px, py, pz, fwd)) { phase = Phase.DETOUR_RETURN_MINE; return; }
            if (detourSteps >= lavaDetourDist.get()) { phase = Phase.MINE; return; }
        } else {
            if (detourSteps <= 0) { phase = Phase.MINE; return; }
        }

        List<BlockPos> toBreak = new ArrayList<>();
        for (int h = 0; h < tunnelHeight.get(); h++) {
            BlockPos bp = new BlockPos(px + side.getOffsetX(), py + h, pz + side.getOffsetZ());
            BlockState bs = mc.world.getBlockState(bp);
            if (!bs.isAir() && bs.getBlock() != Blocks.BEDROCK) toBreak.add(bp);
        }

        if (!toBreak.isEmpty()) {
            ensurePickaxe();
            for (int i = 0; i < Math.min(breaksPerTick.get(), toBreak.size()); i++) {
                BlockPos bp = toBreak.get(i);
                if (fillBehind.get()) fillLog.putIfAbsent(bp, mc.world.getBlockState(bp));
                final BlockPos fBp = bp;
                Rotations.rotate(Rotations.getYaw(bp), Rotations.getPitch(bp),
                    () -> BlockUtils.breakBlock(fBp, true));
            }
            return;
        }

        // Clear — walk sideways one block
        walkTargetX = (px + side.getOffsetX()) + 0.5;
        walkTargetZ = (pz + side.getOffsetZ()) + 0.5;
        phase = returning ? Phase.DETOUR_RETURN_WALK : Phase.DETOUR_WALK;
    }

    private void detourWalkPhase(boolean returning) {
        moveToward(walkTargetX, walkTargetZ, () -> {
            if (!returning) detourSteps++; else detourSteps--;
            phase = returning ? Phase.DETOUR_RETURN_MINE : Phase.DETOUR_MINE;
        });
    }

    // ── RESTOCK ───────────────────────────────────────────────────────────────

    private void restockClear() {
        int px = MathHelper.floor(mc.player.getX());
        int py = MathHelper.floor(mc.player.getY());
        int pz = MathHelper.floor(mc.player.getZ());
        Direction fwd = fwd();

        boolean clear = true;
        for (int h = 0; h < 2; h++) {
            BlockPos bp = new BlockPos(px + fwd.getOffsetX(), py + h, pz + fwd.getOffsetZ());
            if (!mc.world.getBlockState(bp).isAir()) {
                ensurePickaxe();
                final BlockPos fBp = bp;
                Rotations.rotate(Rotations.getYaw(bp), Rotations.getPitch(bp),
                    () -> BlockUtils.breakBlock(fBp, true));
                clear = false; waitTicks++;
                if (waitTicks > MAX_WAIT) { warning("Can't clear space."); toggle(); }
                return;
            }
        }
        if (clear) {
            waitTicks = 0;
            int sk = useShulkers.get()   ? findInInv(TunnelMinerModule::isShulkerBox) : -1;
            int ec = useEnderChest.get() ? findInInv(TunnelMinerModule::isEnderChest) : -1;
            if      (sk != -1) { restockEC = false; phase = Phase.RESTOCK_PLACE; }
            else if (ec != -1) { restockEC = true;  phase = Phase.RESTOCK_PLACE; }
            else               { warning("No restock container — stopping."); toggle(); }
        }
    }

    private void restockPlace() {
        Direction fwd = fwd();
        int px = MathHelper.floor(mc.player.getX());
        int py = MathHelper.floor(mc.player.getY());
        int pz = MathHelper.floor(mc.player.getZ());
        containerPos = new BlockPos(px + fwd.getOffsetX(), py, pz + fwd.getOffsetZ());
        BlockPos floor = containerPos.down();
        if (mc.world.getBlockState(floor).isAir()) { warning("No floor for container."); toggle(); return; }

        int slot = restockEC ? findInInv(TunnelMinerModule::isEnderChest)
                             : findInInv(TunnelMinerModule::isShulkerBox);
        if (slot == -1) { warning("Lost container!"); toggle(); return; }
        int hb = toHotbar(slot);
        if (hb == -1) { warning("Hotbar full!"); toggle(); return; }
        InvUtils.swap(hb, false);

        final int fHb = hb;
        Rotations.rotate(Rotations.getYaw(floor), Rotations.getPitch(floor),
            () -> BlockUtils.place(containerPos, Hand.MAIN_HAND, fHb, true, 0, true, true, false));

        ensurePickaxe();
        invTimer = invDelay.get();
        waitTicks = 0;
        phase = Phase.RESTOCK_WAIT;
    }

    private void restockWait() {
        waitTicks++;
        boolean here = restockEC
            ? mc.world.getBlockState(containerPos).getBlock() == Blocks.ENDER_CHEST
            : mc.world.getBlockState(containerPos).getBlock() instanceof ShulkerBoxBlock;
        if (here)              { waitTicks = 0; phase = Phase.RESTOCK_OPEN; return; }
        if (waitTicks > MAX_WAIT) { warning("Container didn't appear."); toggle(); }
    }

    private void restockOpen() {
        waitTicks++;
        if (waitTicks == 1) {
            BlockPos bp = containerPos;
            Rotations.rotate(Rotations.getYaw(bp), Rotations.getPitch(bp),
                () -> mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(bp), Direction.UP, bp, false)));
            return;
        }
        if (mc.currentScreen != null) { waitTicks = 0; invTimer = invDelay.get(); phase = Phase.RESTOCK_LOOT; return; }
        if (waitTicks > MAX_WAIT)     { warning("Container didn't open."); toggle(); }
    }

    private void restockLoot() {
        if (mc.currentScreen == null) { phase = Phase.RESTOCK_CLOSE; return; }

        // Count free slots — must keep AT LEAST 1 free for the shulker/EC item drop
        int free = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++)
            if (mc.player.getInventory().getStack(i).isEmpty()) free++;
        if (free <= 1) { phase = Phase.RESTOCK_CLOSE; return; }

        // Only grab pickaxes — stop as soon as we have enough (defined by minPickaxes setting)
        int currentPickaxes = countPickaxes();
        if (currentPickaxes >= minPickaxes.get()) { phase = Phase.RESTOCK_CLOSE; return; }

        var handler = mc.player.currentScreenHandler;
        for (int i = 0; i < Math.min(27, handler.slots.size()); i++) {
            if (isPickaxe(handler.slots.get(i).getStack())) {
                InvUtils.shiftClick().slotId(i);
                invTimer = invDelay.get();
                return;
            }
        }
        // No more pickaxes in container
        phase = Phase.RESTOCK_CLOSE;
    }

    private void restockClose() {
        if (mc.currentScreen != null) { mc.currentScreen.close(); invTimer = invDelay.get(); return; }
        waitTicks = 0;
        phase = Phase.RESTOCK_BREAK;
    }

    private void restockBreak() {
        if (containerPos == null) { phase = Phase.RESTOCK_PICKUP; return; }
        boolean here = mc.world.getBlockState(containerPos).getBlock() instanceof ShulkerBoxBlock
                    || mc.world.getBlockState(containerPos).getBlock() == Blocks.ENDER_CHEST;
        if (here) {
            ensurePickaxe();
            final BlockPos bp = containerPos;
            Rotations.rotate(Rotations.getYaw(bp), Rotations.getPitch(bp),
                () -> BlockUtils.breakBlock(bp, true));
            return;
        }
        waitTicks = 0;
        phase = Phase.RESTOCK_PICKUP;
    }

    private void restockPickup() {
        if (++waitTicks < 20) return;
        containerPos = null; waitTicks = 0;
        pickSlot = equipBestPickaxe();
        info("Restock done — resuming.");
        phase = Phase.MINE;
    }

    // ── HUD getters ───────────────────────────────────────────────────────────

    public int getBlocksLeft() {
        if (mc.player == null || !isActive()) return 0;
        return Math.abs(destX - MathHelper.floor(mc.player.getX()))
             + Math.abs(destZ - MathHelper.floor(mc.player.getZ()));
    }

    public double getEtaSeconds() {
        if (!isActive() || blocksMined < 5) return -1;
        long ms = System.currentTimeMillis() - startMs;
        if (ms <= 0) return -1;
        double rate = (double) blocksMined / ms;
        return rate <= 0 ? -1 : getBlocksLeft() / (rate * 1000.0);
    }

    public int getDestX()       { return destX; }
    public int getDestZ()       { return destZ; }
    public int getTotalBlocks() { return totalBlocks; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Current forward direction based on which axis we're on and which way the target is. */
    private Direction fwd() {
        if (onXAxis)
            return destX > MathHelper.floor(mc.player.getX()) ? Direction.EAST : Direction.WEST;
        else
            return destZ > MathHelper.floor(mc.player.getZ()) ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean lavaAhead(int px, int py, int pz, Direction dir) {
        for (int d = 1; d <= 5; d++)
            for (int h = 0; h < tunnelHeight.get(); h++)
                if (mc.world.getBlockState(
                    new BlockPos(px + dir.getOffsetX()*d, py+h, pz + dir.getOffsetZ()*d))
                    .getBlock() == Blocks.LAVA) return true;
        return false;
    }

    // ── Inventory helpers ─────────────────────────────────────────────────────

    private static boolean isPickaxe(ItemStack s) {
        if (s.isEmpty()) return false;
        Item it = s.getItem();
        return it == Items.WOODEN_PICKAXE  || it == Items.STONE_PICKAXE
            || it == Items.IRON_PICKAXE    || it == Items.GOLDEN_PICKAXE
            || it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE;
    }

    private static boolean isShulkerBox(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof BlockItem bi
            && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    private static boolean isEnderChest(ItemStack s) {
        return !s.isEmpty() && s.isOf(Items.ENDER_CHEST);
    }

    private static int durabilityLeft(ItemStack s) {
        if (!s.isDamageable()) return Integer.MAX_VALUE;
        Integer d = s.get(DataComponentTypes.DAMAGE);
        return s.getMaxDamage() - (d != null ? d : 0);
    }

    private int countPickaxes() {
        int n = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++)
            if (isPickaxe(mc.player.getInventory().getStack(i))) n++;
        return n;
    }

    private boolean needsRestock() {
        if (!useShulkers.get() && !useEnderChest.get()) return false;
        if (countPickaxes() != 1) return false;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (isPickaxe(s) && durabilityLeft(s) <= lowDurability.get()) return true;
        }
        return false;
    }

    private int findInInv(Predicate<ItemStack> p) {
        for (int i = 0; i < mc.player.getInventory().size(); i++)
            if (p.test(mc.player.getInventory().getStack(i))) return i;
        return -1;
    }

    private int equipBestPickaxe() {
        int best = -1, score = -1;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            int sc = pickScore(s);
            if (sc > score) { score = sc; best = i; }
        }
        if (best == -1) return -1;
        int hb = toHotbar(best);
        if (hb == -1) hb = 0;
        InvUtils.swap(hb, false);
        return hb;
    }

    private void ensurePickaxe() {
        if (pickSlot >= 0 && isPickaxe(mc.player.getInventory().getStack(pickSlot)))
            InvUtils.swap(pickSlot, false);
        else
            pickSlot = equipBestPickaxe();
    }

    /** Returns hotbar slot for the given inventory slot, moving to hotbar if needed. */
    private int toHotbar(int slot) {
        if (slot >= 0 && slot < 9) return slot;
        for (int i = 0; i < 9; i++) {
            if (i == pickSlot) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                InvUtils.move().from(slot).toHotbar(i);
                return i;
            }
        }
        int t = (pickSlot == 1) ? 0 : 1;
        InvUtils.move().from(slot).toHotbar(t);
        return t;
    }

    private int pickScore(ItemStack s) {
        if (s.isOf(Items.NETHERITE_PICKAXE)) return 5;
        if (s.isOf(Items.DIAMOND_PICKAXE))   return 4;
        if (s.isOf(Items.IRON_PICKAXE))      return 3;
        if (s.isOf(Items.STONE_PICKAXE))     return 2;
        if (s.isOf(Items.GOLDEN_PICKAXE))    return 1;
        return 0;
    }

    private void info(String m)    { ChatUtils.info("TunnelMiner", m); }
    private void warning(String m) { ChatUtils.warning("TunnelMiner", m); }
}