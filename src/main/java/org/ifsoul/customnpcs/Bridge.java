package org.ifsoul.customnpcs;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import noppes.npcs.entity.EntityCustomNpc;
import noppes.npcs.api.NpcAPI;
import noppes.npcs.api.entity.IMob;

@Mod("ifsoul_customnpcs")
public final class Bridge {
    private final boolean actionsEnabled;
    private final UUID playerId;
    private final String npcId;
    private final DialogueService service;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private UUID selected;
    private long until;
    private long revision;
    private boolean inFlight;
    private ActiveMovement movement;
    private boolean checkingAction;
    private long nextCheck;

    private record ActiveMovement(DialogueService.Decision decision, Entity npc, PathNavigation navigation,
                                  net.minecraft.world.level.pathfinder.Path path, Vec3 destination, long deadline) {}

    public Bridge() {
        Properties config = new Properties();
        Path path = FMLPaths.CONFIGDIR.get().resolve("ifsoul-customnpcs.properties");
        try (var stream = Files.newInputStream(path)) {
            config.load(stream);
            playerId = UUID.fromString(config.getProperty("player-uuid"));
            actionsEnabled = "true".equals(config.getProperty("game-actions"));
            npcId = config.getProperty("npc-id", "");
            if (npcId.isBlank()) throw new IllegalArgumentException("An exact NPC is required.");
            service = new DialogueService(config.getProperty("service-origin", ""),
                config.getProperty("pairing-code", ""), npcId, actionsEnabled ? Set.of("movement") : Set.of());
        } catch (IOException | IllegalArgumentException | NullPointerException error) {
            throw new IllegalStateException("Configure ifsoul-customnpcs.properties before enabling the bridge.");
        }
        NeoForge.EVENT_BUS.addListener(this::onInteract);
        NeoForge.EVENT_BUS.addListener(this::onCommands);
        NeoForge.EVENT_BUS.addListener(this::onQuit);
        NeoForge.EVENT_BUS.addListener(this::onTravel);
        NeoForge.EVENT_BUS.addListener(this::onStop);
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !playerId.equals(player.getUUID()) || event.getHand() != InteractionHand.MAIN_HAND
            || !validNpc(event.getTarget(), player)) return;
        selected = event.getTarget().getUUID();
        until = System.nanoTime() + 30_000_000_000L;
        player.sendSystemMessage(Component.literal("Use /ifsoul-customnpcs <text> to speak privately."));
    }

    private void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ifsoul-customnpcs")
            .requires(source -> source.getEntity() instanceof ServerPlayer player
                && playerId.equals(player.getUUID()))
            .then(Commands.argument("text", StringArgumentType.greedyString())
                .executes(context -> converse(context.getSource().getPlayerOrException(),
                    StringArgumentType.getString(context, "text")))));
    }

    static boolean isCancellation(String text) {
        String command = text.strip();
        return "stop".equalsIgnoreCase(command) || "wait".equalsIgnoreCase(command);
    }

    private int converse(ServerPlayer player, String text) {
        if (isCancellation(text)) {
            invalidate(player);
            player.sendSystemMessage(Component.literal("Pending IfSoul movement canceled."));
            return 1;
        }
        UUID target = selected;
        if (inFlight || movement != null || target == null || until - System.nanoTime() <= 0
            || !validNpc(player.serverLevel().getEntity(target), player)
            || !DialogueService.safeText(text, 500)) return 0;
        Entity npc = player.serverLevel().getEntity(target);
        Map<String, String> options = choices(npc, player);
        inFlight = true;
        var server = player.serverLevel().getServer();
        String playerName = player.getGameProfile().getName();
        long startedRevision = revision;
        worker.execute(() -> {
            DialogueService.Decision decision;
            try {
                decision = service.decide(playerId, playerName, text, options);
                if (!"none".equals(decision.choice()) && !service.checkActions()) {
                    service.outcome(decision.id(), decision.choice(), "interrupted");
                    decision = null;
                }
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                decision = null;
            }
            var reply = decision;
            server.execute(() -> {
                inFlight = false;
                ServerPlayer recipient = server.getPlayerList().getPlayer(playerId);
                Entity currentNpc = recipient == null ? null : recipient.serverLevel().getEntity(target);
                if (startedRevision != revision || recipient == null || currentNpc != npc
                    || !validNpc(currentNpc, recipient)) {
                    if (reply != null && !"none".equals(reply.choice())) recordOutcome(reply, "interrupted");
                    return;
                }
                if (reply == null) recipient.sendSystemMessage(Component.literal("IfSoul dialogue is unavailable."));
                else {
                    if (!"none".equals(reply.choice())) {
                        if (until - System.nanoTime() <= 0) recordOutcome(reply, "expired");
                        else try {
                            executeAction(reply, currentNpc, recipient);
                        } catch (RuntimeException error) {
                            System.getLogger(Bridge.class.getName()).log(System.Logger.Level.WARNING,
                                "Native Minecraft action failed.");
                            recordOutcome(reply, "failed");
                        }
                    }
                    if ("none".equals(reply.choice()) && !reply.speech().isEmpty()) recipient.sendSystemMessage(Component.literal(reply.speech()));
                }
            });
        });
        return 1;
    }

    private PathNavigation actionNavigation(Entity npc, ServerPlayer player) {
        if (!actionsEnabled || !validNpc(npc, player)) return null;
        return npc instanceof EntityCustomNpc custom ? custom.getNavigation() : null;
    }

    private Map<String, String> choices(Entity npc, ServerPlayer player) {
        var navigation = actionNavigation(npc, player);
        return navigation != null && navigation.isDone()
            ? Map.of("come", "Walk near the selected player") : Map.of();
    }

    private void executeAction(DialogueService.Decision decision, Entity npc, ServerPlayer player) {
        var navigation = actionNavigation(npc, player);
        if (!"come".equals(decision.choice()) || navigation == null || !navigation.isDone()) {
            recordOutcome(decision, "interrupted");
            return;
        }
        var destination = player.position();
        if (npc.distanceToSqr(destination) <= 4) {
            recordOutcome(decision, "arrived");
            return;
        }
        if (!(NpcAPI.Instance().getIEntity(npc) instanceof IMob<?> custom)) {
            recordOutcome(decision, "interrupted");
            return;
        }
        custom.navigateTo(destination.x, destination.y, destination.z, 1.0);
        var path = navigation.getPath();
        if (path == null || navigation.isDone()) {
            recordOutcome(decision, "unreachable");
            return;
        }
        movement = new ActiveMovement(decision, npc, navigation, path, destination,
            System.nanoTime() + 10_000_000_000L);
        nextCheck = System.nanoTime() + 1_000_000_000L;
    }

    private void onTick(ServerTickEvent.Post event) {
        var active = movement;
        if (active == null) return;
        var player = event.getServer().getPlayerList().getPlayer(playerId);
        if (player == null || actionNavigation(active.npc(), player) != active.navigation()
            || active.navigation().getPath() != active.path()) {
            finishMovement("interrupted");
        } else if (active.npc().distanceToSqr(active.destination()) <= 4) {
            finishMovement("arrived");
        } else if (active.navigation().isDone()) {
            finishMovement("unreachable");
        } else if (System.nanoTime() - active.deadline() >= 0) {
            finishMovement("expired");
        } else if (!checkingAction && System.nanoTime() - nextCheck >= 0) {
            checkingAction = true;
            var server = event.getServer();
            worker.execute(() -> {
                boolean authorized;
                try {
                    authorized = service.checkActions();
                } catch (Exception error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    authorized = false;
                }
                boolean permitted = authorized;
                server.execute(() -> {
                    checkingAction = false;
                    nextCheck = System.nanoTime() + 1_000_000_000L;
                    if (!permitted && movement == active) finishMovement("interrupted");
                });
            });
        }
    }

    private void finishMovement(String result) {
        var active = movement;
        if (active == null) return;
        movement = null;
        if (active.navigation().getPath() == active.path()) active.navigation().stop();
        recordOutcome(active.decision(), result);
        var player = ((net.minecraft.server.level.ServerLevel) active.npc().level()).getServer().getPlayerList().getPlayer(playerId);
        if (player != null) player.sendSystemMessage(Component.literal("IfSoul action: " + result + "."));
    }

    private void recordOutcome(DialogueService.Decision decision, String result) {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            try {
                service.outcome(decision.id(), decision.choice(), result);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException error) {
                System.getLogger(Bridge.class.getName()).log(System.Logger.Level.WARNING,
                    "IfSoul action outcome could not be delivered.");
            }
        });
    }

    private boolean validNpc(Entity entity, ServerPlayer player) {
        if (entity == null || !entity.isAlive() || entity.level() != player.level()
            || player.distanceToSqr(entity) > 64) return false;
        return entity instanceof EntityCustomNpc && npcId.equals(entity.getStringUUID());
    }

    private void onQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        invalidate(event.getEntity());
    }

    private void onTravel(PlayerEvent.PlayerChangedDimensionEvent event) {
        invalidate(event.getEntity());
    }

    private void invalidate(Entity entity) {
        if (!playerId.equals(entity.getUUID())) return;
        revision++;
        selected = null;
        finishMovement("stopped");
    }

    private void onStop(ServerStoppingEvent event) {
        revision++;
        finishMovement("interrupted");
        worker.shutdownNow();
    }
}
