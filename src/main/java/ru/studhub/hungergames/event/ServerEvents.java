package ru.studhub.hungergames.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ru.studhub.hungergames.HungerGames;
import ru.studhub.hungergames.arena.ArenaService;
import ru.studhub.hungergames.command.HgCommands;

public final class ServerEvents {
    @SubscribeEvent public void started(ServerStartedEvent event) { HungerGames.start(event.getServer()); }
    @SubscribeEvent public void stopping(ServerStoppingEvent event) { HungerGames.stop(); }
    @SubscribeEvent public void commands(RegisterCommandsEvent event) { HgCommands.register(event); }
    @SubscribeEvent public void tick(ServerTickEvent.Post event) {
        EventController c = HungerGames.controller();
        if (c != null) c.tick();
    }
    @SubscribeEvent public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && HungerGames.controller() != null) HungerGames.controller().restore(player);
    }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && HungerGames.controller() != null) HungerGames.controller().logout(player);
    }
    @SubscribeEvent public void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && HungerGames.controller() != null) HungerGames.controller().respawn(player);
    }
    @SubscribeEvent public void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && HungerGames.controller() != null) HungerGames.controller().syncBorder(player);
    }
    @SubscribeEvent public void clonePlayer(PlayerEvent.Clone event) {
        String token = event.getOriginal().getPersistentData().getString("hungergames_return_receipt");
        if (!token.isEmpty()) event.getEntity().getPersistentData().putString("hungergames_return_receipt", token);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && HungerGames.controller() != null) HungerGames.controller().eliminate(player, false);
    }
    @SubscribeEvent public void damage(LivingIncomingDamageEvent event) {
        EventController c = HungerGames.controller();
        if (c == null || !c.protectedLevel(event.getEntity().level())) return;
        if (!(event.getEntity() instanceof ServerPlayer player) || !c.live(player)
                || event.getSource().getEntity() instanceof ServerPlayer attacker && !c.live(attacker)) event.setCanceled(true);
    }
    @SubscribeEvent public void attack(AttackEntityEvent event) {
        EventController c = HungerGames.controller();
        if (c == null || !c.protectedLevel(event.getEntity().level())) return;
        if (!(event.getEntity() instanceof ServerPlayer player) || !c.live(player)
                || !(event.getTarget() instanceof ServerPlayer target) || !c.live(target)) event.setCanceled(true);
    }
    @SubscribeEvent public void pickup(ItemEntityPickupEvent.Pre event) {
        EventController c = HungerGames.controller();
        if (c != null && c.protectedLevel(event.getPlayer().level())
                && (!(event.getPlayer() instanceof ServerPlayer player) || !c.live(player))) event.setCanPickup(TriState.FALSE);
    }
    @SubscribeEvent public void breaking(BlockEvent.BreakEvent event) {
        EventController c = HungerGames.controller();
        if (c != null && c.protectedLevel(event.getPlayer().level())) event.setCanceled(true);
    }
    @SubscribeEvent public void placing(BlockEvent.EntityPlaceEvent event) {
        EventController c = HungerGames.controller();
        if (c != null && event.getLevel() instanceof net.minecraft.world.level.Level level && c.protectedLevel(level)) event.setCanceled(true);
    }
    @SubscribeEvent public void explosion(ExplosionEvent.Detonate event) {
        EventController c = HungerGames.controller();
        if (c != null && c.protectedLevel(event.getLevel())) {
            event.getAffectedBlocks().clear();
            event.getAffectedEntities().removeIf(entity -> !(entity instanceof ServerPlayer player) || !c.live(player));
        }
    }
    @SubscribeEvent public void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        EventController c = HungerGames.controller();
        if (c == null || !c.protectedLevel(player.level())) return;
        if (!c.live(player)) { event.setCanceled(true); return; }
        var block = player.level().getBlockState(event.getPos()).getBlock();
        if (!ArenaService.allowed(player.serverLevel(), event.getPos(), c.settings())
                && !(block instanceof DoorBlock) && !(block instanceof TrapDoorBlock)
                && !(block instanceof ButtonBlock) && !(block instanceof LeverBlock)) event.setUseBlock(TriState.FALSE);
    }
    @SubscribeEvent public void rightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        EventController c = HungerGames.controller();
        if (c != null && c.protectedLevel(player.level()) && !c.live(player)) event.setCanceled(true);
    }
    @SubscribeEvent public void interactEntity(PlayerInteractEvent.EntityInteract event) {
        if (HungerGames.controller() != null && HungerGames.controller().protectedLevel(event.getLevel())) event.setCanceled(true);
    }
    @SubscribeEvent public void interactSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (HungerGames.controller() != null && HungerGames.controller().protectedLevel(event.getLevel())) event.setCanceled(true);
    }
    @SubscribeEvent public void travel(EntityTravelToDimensionEvent event) {
        EventController c = HungerGames.controller();
        if (c == null || c.transferring()) return;
        Entity entity = event.getEntity();
        var target = entity.getServer() == null ? null : entity.getServer().getLevel(event.getDimension());
        if (c.protectedLevel(entity.level()) || target != null && c.protectedLevel(target)) event.setCanceled(true);
    }
    @SubscribeEvent public void command(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) return;
        EventController c = HungerGames.controller();
        if (c == null || !c.engaged(player)) return;
        String input = event.getParseResults().getReader().getString().strip();
        if (input.startsWith("/")) input = input.substring(1);
        if (event.getParseResults().getContext().getSource().hasPermission(2) && input.matches("(?:hungergames:)?hg(?:\\s.*)?")) return;
        if (!input.matches("(?:hungergames:)?hg(?:\\s+(?:leave|status|spectator))?\\s*")) {
            event.setCanceled(true);
            player.sendSystemMessage(EventController.message("На событии доступны /hg leave и /hg status."));
        }
    }
}
