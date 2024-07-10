package com.tacz.guns.network.message.event;

import com.tacz.guns.api.event.common.GunFireSelectEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ServerMessageGunFireSelect {
    private final int shooterId;
    private final ItemStack gunItemStack;

    public ServerMessageGunFireSelect(int shooterId, ItemStack gunItemStack) {
        this.shooterId = shooterId;
        this.gunItemStack = gunItemStack;
    }

    public static void encode(ServerMessageGunFireSelect message, FriendlyByteBuf buf) {
        buf.writeVarInt(message.shooterId);
        buf.writeItem(message.gunItemStack);
    }

    public static ServerMessageGunFireSelect decode(FriendlyByteBuf buf) {
        int shooterId = buf.readVarInt();
        ItemStack gunItemStack = buf.readItem();
        return new ServerMessageGunFireSelect(shooterId, gunItemStack);
    }

    public static void handle(ServerMessageGunFireSelect message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> doClientEvent(message));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void doClientEvent(ServerMessageGunFireSelect message) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (level.getEntity(message.shooterId) instanceof LivingEntity shooter) {
            GunFireSelectEvent gunFireSelectEvent = new GunFireSelectEvent(shooter, message.gunItemStack, LogicalSide.CLIENT);
            MinecraftForge.EVENT_BUS.post(gunFireSelectEvent);
        }
    }
}