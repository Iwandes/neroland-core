package za.co.neroland.nerolandcore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.NerolandCoreCommon;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.config.ConfigManager;
import za.co.neroland.nerolandcore.config.ConfigSchema;
import za.co.neroland.nerolandcore.config.ConfigValue;
import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.nerolandcore.meteor.MeteorMaterialEntry;
import za.co.neroland.nerolandcore.meteor.MeteorMaterials;
import za.co.neroland.nerolandcore.network.CoreNetwork;
import za.co.neroland.nerolandcore.progression.Gate;
import za.co.neroland.nerolandcore.progression.GateDefinitions;
import za.co.neroland.nerolandcore.progression.ProgressionGates;

/**
 * The shared {@code /neroland} command tree, built once with vanilla Brigadier so
 * it is identical on every loader. Each loader entry point registers it from its
 * own command-registration event ({@code RegisterCommandsEvent} on NeoForge/Forge,
 * {@code CommandRegistrationCallback} on Fabric).
 *
 * <ul>
 *   <li>{@code /neroland config reload} — re-read every registered schema from disk
 *       (op level 2);</li>
 *   <li>{@code /neroland config list} — print each schema's values (current +
 *       whether server-authoritative).</li>
 * </ul>
 */
public final class CoreCommands {

    private CoreCommands() {
    }

    /** Requirement for the operator-only subcommands (permission level 2, "gamemasters"). */
    private static Predicate<CommandSourceStack> gamemasters() {
        //? if >=1.21.6 {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
        //?} else {
        /*return source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS);
        *///?}
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("neroland")
                .then(Commands.literal("config")
                        .then(Commands.literal("reload")
                                .requires(gamemasters())
                                .executes(ctx -> {
                                    ConfigManager.reloadAll();
                                    if (ctx.getSource().getServer() != null) {
                                        CoreNetwork.syncAll(ctx.getSource().getServer());
                                    }
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable("commands.nerolandcore.config.reloaded"), true);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    listConfig(ctx.getSource());
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("gate")
                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    listGates(ctx.getSource());
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(Commands.literal("open")
                                .requires(gamemasters())
                                .then(Commands.argument("gate", StringArgumentType.string())
                                        .executes(ctx -> setGate(ctx, true))))
                        .then(Commands.literal("close")
                                .requires(gamemasters())
                                .then(Commands.argument("gate", StringArgumentType.string())
                                        .executes(ctx -> setGate(ctx, false)))))
                .then(Commands.literal("data")
                        .then(Commands.literal("eraseme")
                                .executes(ctx -> eraseSelf(ctx)))
                        .then(Commands.literal("erase")
                                .requires(gamemasters())
                                .then(Commands.argument("uuid", StringArgumentType.string())
                                        .executes(ctx -> eraseByUuid(ctx))))
                        .then(Commands.literal("purge-inactive")
                                .requires(gamemasters())
                                .executes(ctx -> purgeInactive(ctx))))
                .then(Commands.literal("meteor")
                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    listMeteor(ctx.getSource());
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(Commands.literal("reload")
                                .requires(gamemasters())
                                .executes(ctx -> reloadMeteor(ctx)))));
    }

    private static void listMeteor(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.translatable("commands.nerolandcore.no_server"));
            return;
        }
        for (MeteorMaterialEntry e : MeteorMaterials.all(server)) {
            String line = "  " + e.id() + " §7[" + e.tier().name().toLowerCase() + "]§r → " + e.item()
                    + (e.minGate() != null ? " §8gate=" + e.minGate() + "§r" : "")
                    + (e.planet() != null ? " §8planet=" + e.planet() + "§r" : "")
                    + (e.weightOverride() != null ? " §8w=" + e.weightOverride() + "§r" : "")
                    + (e.enabled() ? "" : " §c(disabled)§r");
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }

    private static int reloadMeteor(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        if (server == null) {
            ctx.getSource().sendFailure(Component.translatable("commands.nerolandcore.no_server"));
            return 0;
        }
        MeteorMaterials.reload(server);
        int count = MeteorMaterials.all(server).size();
        ctx.getSource().sendSuccess(
                () -> Component.translatable("commands.nerolandcore.meteor.reloaded", count), true);
        return Command.SINGLE_SUCCESS;
    }

    private static void listConfig(CommandSourceStack source) {
        for (ConfigSchema schema : ConfigManager.schemas()) {
            source.sendSuccess(() -> Component.literal("§e" + schema.modId() + "§r"), false);
            for (ConfigValue<?> value : schema.values()) {
                String line = "  " + value.key() + " = " + value.asString()
                        + (value.serverAuthoritative() ? " §8(server)§r" : "");
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
    }

    private static void listGates(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        for (Gate gate : GateDefinitions.current().values()) {
            Component line = Component.literal("  " + gate.id() + " §7(" + gate.scope().name().toLowerCase() + ")§r");
            if (player != null) {
                line = line.copy().append(" ").append(Component.translatable(ProgressionGates.isOpen(player, gate.id())
                        ? "commands.nerolandcore.gate.state.open" : "commands.nerolandcore.gate.state.closed"));
            }
            Component finalLine = line;
            source.sendSuccess(() -> finalLine, false);
        }
    }

    private static int setGate(CommandContext<CommandSourceStack> ctx, boolean open) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("commands.nerolandcore.player_only"));
            return 0;
        }
        Identifier gate = parseGateId(StringArgumentType.getString(ctx, "gate"));
        boolean changed = open ? ProgressionGates.open(player, gate) : ProgressionGates.close(player, gate);
        String gateKey = "commands.nerolandcore.gate." + (open ? "opened" : "closed") + (changed ? "" : ".unchanged");
        ctx.getSource().sendSuccess(() -> Component.translatable(gateKey, gate.toString()), true);
        return Command.SINGLE_SUCCESS;
    }

    /** Accept a bare path ("reached_orbit") as nerolandcore-namespaced, or a full "ns:path". */
    private static Identifier parseGateId(String raw) {
        if (raw.indexOf(':') < 0) {
            return Identifier.fromNamespaceAndPath(NerolandCoreCommon.MOD_ID, raw);
        }
        return Identifier.parse(raw);
    }

    private static int eraseSelf(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("commands.nerolandcore.player_only"));
            return 0;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null || !PlayerDataErasure.eraseAndReport(server, player.getUUID())) {
            ctx.getSource().sendFailure(Component.translatable("commands.nerolandcore.data.erase_incomplete"));
            return 0;
        }
        ctx.getSource().sendSuccess(
                () -> Component.translatable("commands.nerolandcore.data.erased_self"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int eraseByUuid(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        UUID player;
        try {
            player = UUID.fromString(StringArgumentType.getString(ctx, "uuid"));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.translatable("commands.nerolandcore.data.invalid_uuid"));
            return 0;
        }
        if (!PlayerDataErasure.eraseAndReport(server, player)) {
            ctx.getSource().sendFailure(Component.translatable("commands.nerolandcore.data.erase_incomplete"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.nerolandcore.data.erased_player"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int purgeInactive(CommandContext<CommandSourceStack> ctx) {
        PlayerDataErasure.PurgeResult result;
        try {
            result = PlayerDataErasure.purgeInactiveReport(ctx.getSource().getServer());
        } catch (RuntimeException failure) {
            NerolandCoreCommon.LOGGER.warn("[Neroland Core] Retention sweep could not refresh its retry state ({})",
                    failure.getClass().getName());
            ctx.getSource().sendFailure(Component.translatable("commands.nerolandcore.data.erase_incomplete"));
            return 0;
        }
        if (result.failed() > 0) {
            ctx.getSource().sendFailure(Component.translatable("commands.nerolandcore.data.purge_incomplete",
                    result.purged(), result.failed()));
            return 0;
        }
        ctx.getSource().sendSuccess(
                () -> Component.translatable("commands.nerolandcore.data.purged", result.purged()), true);
        return Command.SINGLE_SUCCESS;
    }
}
