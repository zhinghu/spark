/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.forge.plugin;

import com.google.common.collect.ImmutableMap;
import me.lucko.spark.common.platform.PlatformInfo;
import me.lucko.spark.common.platform.world.WorldInfoProvider;
import me.lucko.spark.common.sampler.source.ClassSourceLookup;
import me.lucko.spark.common.sampler.source.SourceMetadata;
import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.common.tick.TickReporter;
import me.lucko.spark.forge.ForgeClassSourceLookup;
import me.lucko.spark.forge.ForgePlatformInfo;
import me.lucko.spark.forge.ForgeServerCommandSender;
import me.lucko.spark.forge.ForgeSparkMod;
import me.lucko.spark.forge.ForgeTickHook;
import me.lucko.spark.forge.ForgeTickReporter;
import me.lucko.spark.forge.ForgeWorldInfoProvider;
import me.lucko.spark.minecraft.plugin.MinecraftServerSparkPlugin;
import me.lucko.spark.minecraft.sender.MinecraftServerCommandSender;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.listener.EventListener;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionNode.PermissionResolver;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ForgeServerSparkPlugin extends MinecraftServerSparkPlugin<ForgeSparkMod> {

    public static void init(ForgeSparkMod mod, ServerAboutToStartEvent event) {
        ForgeServerSparkPlugin plugin = new ForgeServerSparkPlugin(mod, event.getServer());
        plugin.enable();
    }

    private static final PermissionResolver<Boolean> DEFAULT_PERMISSION_VALUE = (player, playerUUID, context) -> {
        if (player == null) {
            return false;
        }

        MinecraftServer server = player.level().getServer();
        if (server != null && server.isSingleplayerOwner(player.nameAndId())) {
            return true;
        }

        return player.hasPermissions(4);
    };

    private Map<String, PermissionNode<Boolean>> registeredPermissions = Collections.emptyMap();
    private Collection<EventListener> listeners = Collections.emptyList();

    public ForgeServerSparkPlugin(ForgeSparkMod mod, MinecraftServer server) {
        super(mod, server);
    }

    @Override
    public void enable() {
        super.enable();

        // register listeners
        this.listeners = BusGroup.DEFAULT.register(MethodHandles.lookup(), this);
    }

    @Override
    public void disable() {
        super.disable();

        // unregister listeners
        if (!this.listeners.isEmpty()) {
            BusGroup.DEFAULT.unregister(this.listeners);
        }
        this.listeners = Collections.emptyList();
    }

    @SubscribeEvent
    public void onDisable(ServerStoppingEvent event) {
        if (event.getServer() == this.server) {
            disable();
        }
    }

    @SubscribeEvent
    public void onPermissionGather(PermissionGatherEvent.Nodes e) {
        // collect all possible permissions
        List<String> permissions = this.platform.getCommands().stream()
                .map(me.lucko.spark.common.command.Command::primaryAlias)
                .collect(Collectors.toList());

        // special case for the "spark" permission: map it to "spark.all"
        permissions.add("all");

        // register permissions with forge & keep a copy for lookup
        ImmutableMap.Builder<String, PermissionNode<Boolean>> builder = ImmutableMap.builder();

        Map<String, PermissionNode<?>> alreadyRegistered = e.getNodes().stream().collect(Collectors.toMap(PermissionNode::getNodeName, Function.identity()));

        for (String permission : permissions) {
            String permissionString = "spark." + permission;

            // there's a weird bug where it seems that this listener can be called twice, causing an
            // IllegalArgumentException to be thrown the second time e.addNodes is called.
            PermissionNode<?> existing = alreadyRegistered.get(permissionString);
            if (existing != null) {
                //noinspection unchecked
                builder.put(permissionString, (PermissionNode<Boolean>) existing);
                continue;
            }

            PermissionNode<Boolean> node = new PermissionNode<>("spark", permission, PermissionTypes.BOOLEAN, DEFAULT_PERMISSION_VALUE);
            e.addNodes(node);
            builder.put(permissionString, node);
        }
        this.registeredPermissions = builder.build();
    }

    @SubscribeEvent
    public void onCommandRegister(RegisterCommandsEvent e) {
        registerCommands(e.getDispatcher());
    }

    public PermissionNode<Boolean> getPermissionNode(String permission) {
        if (permission.equals("spark")) {
            permission = "spark.all";
        }

        PermissionNode<Boolean> permissionNode = this.registeredPermissions.get(permission);
        if (permissionNode == null) {
            throw new IllegalStateException("spark permission not registered: " + permission);
        }
        return permissionNode;
    }

    @Override
    protected MinecraftServerCommandSender createCommandSender(CommandSourceStack source) {
        return new ForgeServerCommandSender(source, this);
    }

    @Override
    public TickHook createTickHook() {
        return new ForgeTickHook.Server();
    }

    @Override
    public TickReporter createTickReporter() {
        return new ForgeTickReporter.Server();
    }

    @Override
    public ClassSourceLookup createClassSourceLookup() {
        return new ForgeClassSourceLookup();
    }

    @Override
    public Collection<SourceMetadata> getKnownSources() {
        return SourceMetadata.gather(
                ModList.get().getMods(),
                IModInfo::getModId,
                mod -> mod.getVersion().toString(),
                mod -> null, // ?
                IModInfo::getDescription
        );
    }

    @Override
    public WorldInfoProvider createWorldInfoProvider() {
        return new ForgeWorldInfoProvider.Server(this.server);
    }

    @Override
    public PlatformInfo getPlatformInfo() {
        return new ForgePlatformInfo(PlatformInfo.Type.SERVER);
    }
}
