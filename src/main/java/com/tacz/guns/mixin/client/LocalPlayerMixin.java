package com.tacz.guns.mixin.client;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.player.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.event.common.GunFireSelectEvent;
import com.tacz.guns.api.event.common.GunReloadEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.gun.FireMode;
import com.tacz.guns.api.gun.ReloadState;
import com.tacz.guns.api.gun.ShootResult;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.animation.internal.GunAnimationStateMachine;
import com.tacz.guns.client.input.ShootKey;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.duck.KeepingItemRenderer;
import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.network.message.*;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.sound.SoundManager;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.LogicalSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@SuppressWarnings("UnreachableCode")
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin implements IClientPlayerGunOperator {
    @Unique
    private static final ScheduledExecutorService tacz$ScheduledExecutorService = Executors.newScheduledThreadPool(2);
    @Unique
    private static final Predicate<IGunOperator> tacz$ShootLockedCondition = operator -> operator.getSynShootCoolDown() > 0;
    /**
     * 上一个 tick 的瞄准进度，用于插值，范围 0 ~ 1
     */
    @Unique
    private static float tacz$OldAimingProgress = 0;
    @Unique
    private volatile long tacz$ClientShootTimestamp = -1L;
    @Unique
    private volatile boolean tacz$IsShootRecorded = true;
    /**
     * 用于标记 bolt 是否已经执行完成，防止因为客户端、服务端异步产生的数据不同步而造成的重复 bolt
     */
    @Unique
    private boolean tacz$IsBolting = false;
    /**
     * 瞄准的进度，范围 0 ~ 1
     */
    @Unique
    private float tacz$ClientAimingProgress = 0;
    /**
     * 瞄准时间戳，单位 ms
     */
    @Unique
    private long tacz$ClientAimingTimestamp = -1L;
    @Unique
    private boolean tacz$ClientIsAiming = false;
    /**
     * 切枪时间戳，在切枪开始时更新，单位 ms。
     * 在客户端仅用于计算收枪动画的时长和过渡时长。
     */
    @Unique
    private long tacz$ClientDrawTimestamp = -1L;
    @Unique
    @Nullable
    private ScheduledFuture<?> tacz$DrawFuture = null;
    /**
     * 这个状态锁表示：任意时刻，正在进行的枪械操作只能为一个。
     * 主要用于防止客户端操作表现效果重复执行。
     */
    @Unique
    private volatile boolean tacz$ClientStateLock = false;
    /**
     * 用于等待上锁的服务端响应
     */
    @Unique
    @Nullable
    private Predicate<IGunOperator> tacz$LockedCondition = null;
    /**
     * 计算上锁响应时间，不允许超过最大响应时间，避免死锁
     */
    @Unique
    private long tacz$LockTimestamp = -1;

    @Unique
    @Override
    public ShootResult shoot() {
        // 如果上一次异步开火的效果还未执行，则直接返回，等待异步开火效果执行
        if (!tacz$IsShootRecorded) {
            return ShootResult.COOL_DOWN;
        }
        // 如果状态锁正在准备锁定，且不是开火的状态锁，则不允许开火(主要用于防止切枪后开火动作覆盖切枪动作)
        if (tacz$ClientStateLock && tacz$LockedCondition != tacz$ShootLockedCondition && tacz$LockedCondition != null) {
            tacz$IsShootRecorded = true;
            // 因为这块主要目的是防止切枪后开火动作覆盖切枪动作，返回 IS_DRAWING
            return ShootResult.IS_DRAWING;
        }
        LocalPlayer player = (LocalPlayer) (Object) this;
        // 暂定为只有主手能开枪
        ItemStack mainhandItem = player.getMainHandItem();
        if (!(mainhandItem.getItem() instanceof IGun iGun)) {
            return ShootResult.NOT_GUN;
        }
        ResourceLocation gunId = iGun.getGunId(mainhandItem);
        Optional<ClientGunIndex> gunIndexOptional = TimelessAPI.getClientGunIndex(gunId);
        if (gunIndexOptional.isEmpty()) {
            return ShootResult.ID_NOT_EXIST;
        }
        ClientGunIndex gunIndex = gunIndexOptional.get();
        GunData gunData = gunIndex.getGunData();
        FireMode fireMode = iGun.getFireMode(mainhandItem);
        long coolDown;
        if (fireMode == FireMode.BURST) {
            coolDown = gunData.getBurstShootInterval() - (System.currentTimeMillis() - tacz$ClientShootTimestamp);
        } else {
            coolDown = gunData.getShootInterval() - (System.currentTimeMillis() - tacz$ClientShootTimestamp);
        }
        // 如果射击冷却大于 1 tick (即 50 ms)，则不允许开火
        if (coolDown > 50) {
            return ShootResult.COOL_DOWN;
        }
        if (coolDown < 0) {
            coolDown = 0;
        }
        // 因为开火冷却检测用了特别定制的方法，所以不检查状态锁，而是手动检查是否换弹、切枪
        IGunOperator gunOperator = IGunOperator.fromLivingEntity(player);
        // 检查是否正在换弹
        if (gunOperator.getSynReloadState().getStateType().isReloading()) {
            return ShootResult.IS_RELOADING;
        }
        // 检查是否正在切枪
        if (gunOperator.getSynDrawCoolDown() != 0) {
            return ShootResult.IS_DRAWING;
        }
        // 判断子弹数
        Bolt boltType = gunIndex.getGunData().getBolt();
        boolean hasAmmoInBarrel = iGun.hasBulletInBarrel(mainhandItem) && boltType != Bolt.OPEN_BOLT;
        int ammoCount = iGun.getCurrentAmmoCount(mainhandItem) + (hasAmmoInBarrel ? 1 : 0);
        if (IGunOperator.fromLivingEntity(player).needCheckAmmo() && ammoCount < 1) {
            SoundPlayManager.playDryFireSound(player, gunIndex);
            return ShootResult.NO_AMMO;
        }
        // 判断膛内子弹
        if (boltType == Bolt.MANUAL_ACTION && !hasAmmoInBarrel) {
            bolt();
            return ShootResult.NEED_BOLT;
        }
        // 检查是否正在奔跑
        if (gunOperator.getSynSprintTime() > 0) {
            return ShootResult.IS_SPRINTING;
        }
        // 触发开火事件
        if (MinecraftForge.EVENT_BUS.post(new GunShootEvent(player, mainhandItem, LogicalSide.CLIENT))) {
            return ShootResult.FORGE_EVENT_CANCEL;
        }
        // 切换状态锁，不允许换弹、检视等行为进行。
        lockState(tacz$ShootLockedCondition);
        tacz$IsShootRecorded = false;
        // 开火效果需要延时执行，这样渲染效果更好。
        tacz$ScheduledExecutorService.schedule(() -> {
            // 转换 isRecord 状态，允许下一个tick的开火检测。
            tacz$IsShootRecorded = true;
            // 如果状态锁正在准备锁定，且不是开火的状态锁，则不允许开火(主要用于防止切枪后开火动作覆盖切枪动作)
            if (tacz$ClientStateLock && tacz$LockedCondition != tacz$ShootLockedCondition && tacz$LockedCondition != null) {
                tacz$IsShootRecorded = true;
                return;
            }
            // 记录新的开火时间戳
            tacz$ClientShootTimestamp = System.currentTimeMillis();
            // 发送开火的数据包，通知服务器。暂时只考虑主手能打枪。
            NetworkHandler.CHANNEL.sendToServer(new ClientMessagePlayerShoot());
            // 动画状态机转移状态
            GunAnimationStateMachine animationStateMachine = gunIndex.getAnimationStateMachine();
            if (animationStateMachine != null) {
                animationStateMachine.onGunShoot();
            }
            // 获取消音
            final boolean[] useSilenceSound = new boolean[]{false};
            AttachmentDataUtils.getAllAttachmentData(mainhandItem, gunData, attachmentData -> {
                if (attachmentData.getSilence() != null && attachmentData.getSilence().isUseSilenceSound()) {
                    useSilenceSound[0] = true;
                }
            });
            // 播放声音需要从异步线程上传到主线程执行。
            Minecraft.getInstance().submitAsync(() -> {
                // 开火需要打断检视
                SoundPlayManager.stopPlayGunSound(gunIndex, SoundManager.INSPECT_SOUND);
                if (useSilenceSound[0]) {
                    SoundPlayManager.playSilenceSound(player, gunIndex);
                } else {
                    SoundPlayManager.playShootSound(player, gunIndex);
                }
            });
        }, coolDown, TimeUnit.MILLISECONDS);
        return ShootResult.SUCCESS;
    }

    @Unique
    @Override
    public void draw(ItemStack lastItem) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        ItemStack currentItem = player.getMainHandItem();
        // 锁上状态锁
        lockState(operator -> operator.getSynDrawCoolDown() > 0);
        // 重置客户端的 shoot 时间戳
        tacz$IsShootRecorded = true;
        tacz$ClientShootTimestamp = -1;
        // 重置客户端瞄准状态
        tacz$ClientIsAiming = false;
        tacz$ClientAimingProgress = 0;
        tacz$OldAimingProgress = 0;
        // 重置拉栓状态
        tacz$IsBolting = false;
        // 更新切枪时间戳
        if (tacz$ClientDrawTimestamp == -1) {
            tacz$ClientDrawTimestamp = System.currentTimeMillis();
        }
        // 重置连发状态
        ShootKey.resetBurstState();
        long drawTime = System.currentTimeMillis() - tacz$ClientDrawTimestamp;
        IGun iGun = IGun.getIGunOrNull(currentItem);
        IGun iGun1 = IGun.getIGunOrNull(lastItem);
        if (drawTime >= 0) {
            // 如果不处于收枪状态，则需要加上收枪的时长
            if (iGun1 != null) {
                Optional<CommonGunIndex> gunIndex = TimelessAPI.getCommonGunIndex(iGun1.getGunId(lastItem));
                float putAwayTime = gunIndex.map(index -> index.getGunData().getPutAwayTime()).orElse(0F);
                if (drawTime > putAwayTime * 1000) {
                    drawTime = (long) (putAwayTime * 1000);
                }
                tacz$ClientDrawTimestamp = System.currentTimeMillis() + drawTime;
            } else {
                drawTime = 0;
                tacz$ClientDrawTimestamp = System.currentTimeMillis();
            }
        }
        long putAwayTime = Math.abs(drawTime);
        // 发包通知服务器
        if (Minecraft.getInstance().gameMode != null) {
            Minecraft.getInstance().gameMode.ensureHasSentCarriedItem();
        }
        NetworkHandler.CHANNEL.sendToServer(new ClientMessagePlayerDrawGun());
        // 不处于收枪状态时才能收枪
        if (drawTime >= 0) {
            if (iGun1 != null) {
                TimelessAPI.getClientGunIndex(iGun1.getGunId(lastItem)).ifPresent(gunIndex -> {
                    // 播放收枪音效
                    SoundPlayManager.stopPlayGunSound();
                    SoundPlayManager.playPutAwaySound(player, gunIndex);
                    // 播放收枪动画
                    GunAnimationStateMachine animationStateMachine = gunIndex.getAnimationStateMachine();
                    if (animationStateMachine != null) {
                        animationStateMachine.onGunPutAway(putAwayTime / 1000F);
                        // 保持枪械的渲染直到收枪动作完成
                        ((KeepingItemRenderer) Minecraft.getInstance().getItemInHandRenderer()).keep(lastItem, putAwayTime);
                    }
                });
            }
        }
        // 异步放映抬枪动画
        if (iGun != null) {
            TimelessAPI.getClientGunIndex(iGun.getGunId(currentItem)).ifPresent(gunIndex -> {
                GunAnimationStateMachine animationStateMachine = gunIndex.getAnimationStateMachine();
                if (animationStateMachine != null) {
                    if (tacz$DrawFuture != null) {
                        tacz$DrawFuture.cancel(false);
                    }
                    tacz$DrawFuture = tacz$ScheduledExecutorService.schedule(() -> {
                        Minecraft.getInstance().submitAsync(() -> {
                            animationStateMachine.onGunDraw();
                            SoundPlayManager.stopPlayGunSound();
                            SoundPlayManager.playDrawSound(player, gunIndex);
                        });
                    }, putAwayTime, TimeUnit.MILLISECONDS);
                }
            });
        }
    }

    @Unique
    @Override
    public void bolt() {
        // 检查状态锁
        if (tacz$ClientStateLock) {
            return;
        }
        if (tacz$IsBolting) {
            return;
        }
        LocalPlayer player = (LocalPlayer) (Object) this;
        ItemStack mainhandItem = player.getMainHandItem();
        if (!(mainhandItem.getItem() instanceof IGun iGun)) {
            return;
        }
        ResourceLocation gunId = iGun.getGunId(mainhandItem);
        TimelessAPI.getClientGunIndex(gunId).ifPresent(gunIndex -> {
            // 检查 bolt 类型是否是 manual action
            Bolt boltType = gunIndex.getGunData().getBolt();
            if (boltType != Bolt.MANUAL_ACTION) {
                return;
            }
            // 检查是否有弹药在枪膛内
            if (iGun.hasBulletInBarrel(mainhandItem)) {
                return;
            }
            // 检查弹匣内是否有子弹
            if (iGun.getCurrentAmmoCount(mainhandItem) == 0) {
                return;
            }
            // 锁上状态锁
            lockState(operator -> operator.getSynBoltCoolDown() >= 0);
            tacz$IsBolting = true;
            // 发包通知服务器
            NetworkHandler.CHANNEL.sendToServer(new ClientMessagePlayerBoltGun());
            // 播放动画和音效
            GunAnimationStateMachine animationStateMachine = gunIndex.getAnimationStateMachine();
            if (animationStateMachine != null) {
                SoundPlayManager.playBoltSound(player, gunIndex);
                animationStateMachine.onGunBolt();
            }
        });
    }

    @Unique
    @Override
    public void reload() {
        LocalPlayer player = (LocalPlayer) (Object) this;
        // 暂定只有主手可以装弹
        ItemStack mainhandItem = player.getMainHandItem();
        if (!(mainhandItem.getItem() instanceof IGun iGun)) {
            return;
        }
        ResourceLocation gunId = iGun.getGunId(mainhandItem);
        TimelessAPI.getClientGunIndex(gunId).ifPresent(gunIndex -> {
            // 检查状态锁
            if (tacz$ClientStateLock) {
                return;
            }
            // 弹药简单检查
            if (IGunOperator.fromLivingEntity(player).needCheckAmmo()) {
                // 满弹检查也放这，这样创造模式玩家随意随便换弹
                // 满弹不需要换
                int maxAmmoCount = AttachmentDataUtils.getAmmoCountWithAttachment(mainhandItem, gunIndex.getGunData());
                if (iGun.getCurrentAmmoCount(mainhandItem) >= maxAmmoCount) {
                    return;
                }
                // 背包弹药检查
                boolean hasAmmo = false;
                Inventory inventory = player.getInventory();
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    ItemStack checkAmmo = inventory.getItem(i);
                    if (checkAmmo.getItem() instanceof IAmmo iAmmo && iAmmo.isAmmoOfGun(mainhandItem, checkAmmo)) {
                        hasAmmo = true;
                        break;
                    }
                    if (checkAmmo.getItem() instanceof IAmmoBox iAmmoBox && iAmmoBox.isAmmoBoxOfGun(mainhandItem, checkAmmo)) {
                        hasAmmo = true;
                        break;
                    }
                }
                if (!hasAmmo) {
                    return;
                }
            }
            // 锁上状态锁
            lockState(operator -> operator.getSynReloadState().getStateType().isReloading());
            // 触发换弹事件
            if (MinecraftForge.EVENT_BUS.post(new GunReloadEvent(player, player.getMainHandItem(), LogicalSide.CLIENT))) {
                return;
            }
            // 发包通知服务器
            NetworkHandler.CHANNEL.sendToServer(new ClientMessagePlayerReloadGun());
            GunAnimationStateMachine animationStateMachine = gunIndex.getAnimationStateMachine();
            if (animationStateMachine != null) {
                Bolt boltType = gunIndex.getGunData().getBolt();
                boolean noAmmo;
                if (boltType == Bolt.OPEN_BOLT) {
                    noAmmo = iGun.getCurrentAmmoCount(mainhandItem) <= 0;
                } else {
                    noAmmo = !iGun.hasBulletInBarrel(mainhandItem);
                }
                //TODO 这块没完全弄好
                /*
                ItemStack extendedMagItem = iGun.getAttachment(mainhandItem, AttachmentType.EXTENDED_MAG);
                IAttachment iAttachment = IAttachment.getIAttachmentOrNull(extendedMagItem);
                if (iAttachment != null) {
                    TimelessAPI.getCommonAttachmentIndex(iAttachment.getAttachmentId(extendedMagItem)).ifPresent(index -> {
                        animationStateMachine.setMagExtended(index.getData().getExtendedMagLevel() > 0);
                    });
                }*/
                // 触发 reload，停止播放声音
                SoundPlayManager.stopPlayGunSound();
                SoundPlayManager.playReloadSound(player, gunIndex, noAmmo);
                animationStateMachine.setNoAmmo(noAmmo).onGunReload();
            }
        });
    }

    @Unique
    @Override
    public void inspect() {
        LocalPlayer player = (LocalPlayer) (Object) this;
        // 暂定只有主手可以检视
        ItemStack mainhandItem = player.getMainHandItem();
        if (!(mainhandItem.getItem() instanceof IGun iGun)) {
            return;
        }
        // 检查状态锁
        if (tacz$ClientStateLock) {
            return;
        }
        ResourceLocation gunId = iGun.getGunId(mainhandItem);
        TimelessAPI.getClientGunIndex(gunId).ifPresent(gunIndex -> {
            Bolt boltType = gunIndex.getGunData().getBolt();
            boolean noAmmo;
            if (boltType == Bolt.OPEN_BOLT) {
                noAmmo = iGun.getCurrentAmmoCount(mainhandItem) <= 0;
            } else {
                noAmmo = !iGun.hasBulletInBarrel(mainhandItem);
            }
            // 触发 inspect，停止播放声音
            SoundPlayManager.stopPlayGunSound();
            SoundPlayManager.playInspectSound(player, gunIndex, noAmmo);
            GunAnimationStateMachine animationStateMachine = gunIndex.getAnimationStateMachine();
            if (animationStateMachine != null) {
                animationStateMachine.setNoAmmo(noAmmo).onGunInspect();
            }
        });
    }

    @Override
    public void fireSelect() {
        // 检查状态锁
        if (tacz$ClientStateLock) {
            return;
        }
        LocalPlayer player = (LocalPlayer) (Object) this;
        // 暂定为主手
        ItemStack mainhandItem = player.getMainHandItem();
        if (!(mainhandItem.getItem() instanceof IGun iGun)) {
            return;
        }
        if (MinecraftForge.EVENT_BUS.post(new GunFireSelectEvent(player, player.getMainHandItem(), LogicalSide.CLIENT))) {
            return;
        }
        ResourceLocation gunId = iGun.getGunId(mainhandItem);
        TimelessAPI.getClientGunIndex(gunId).ifPresent(gunIndex -> {
            // 播放音效
            SoundPlayManager.playFireSelectSound(player, gunIndex);
            // 发送切换开火模式的数据包，通知服务器
            NetworkHandler.CHANNEL.sendToServer(new ClientMessagePlayerFireSelect());
            // 动画状态机转移状态
            GunAnimationStateMachine animationStateMachine = gunIndex.getAnimationStateMachine();
            if (animationStateMachine != null) {
                animationStateMachine.onGunFireSelect();
            }
        });
    }

    @Override
    public void aim(boolean isAim) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        // 暂定为主手
        ItemStack mainhandItem = player.getMainHandItem();
        if (!(mainhandItem.getItem() instanceof IGun iGun)) {
            return;
        }
        ResourceLocation gunId = iGun.getGunId(mainhandItem);
        TimelessAPI.getClientGunIndex(gunId).ifPresent(gunIndex -> {
            tacz$ClientIsAiming = isAim;
            // 发送切换开火模式的数据包，通知服务器
            NetworkHandler.CHANNEL.sendToServer(new ClientMessagePlayerAim(isAim));
        });
    }

    @Unique
    @Override
    public float getClientAimingProgress(float partialTicks) {
        return Mth.lerp(partialTicks, tacz$OldAimingProgress, tacz$ClientAimingProgress);
    }

    @Unique
    @Override
    public long getClientShootCoolDown() {
        LocalPlayer player = (LocalPlayer) (Object) this;
        ItemStack mainHandItem = player.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(mainHandItem);
        if (iGun == null) {
            return -1;
        }
        ResourceLocation gunId = iGun.getGunId(mainHandItem);
        Optional<CommonGunIndex> gunIndexOptional = TimelessAPI.getCommonGunIndex(gunId);
        return gunIndexOptional
                .map(gunIndex -> gunIndex.getGunData().getShootInterval() - (System.currentTimeMillis() - tacz$ClientShootTimestamp))
                .orElse(-1L);
    }

    @Unique
    private void lockState(@Nullable Predicate<IGunOperator> lockedCondition) {
        tacz$ClientStateLock = true;
        tacz$LockTimestamp = System.currentTimeMillis();
        tacz$LockedCondition = lockedCondition;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    public void onTickClientSide(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (player.getLevel().isClientSide()) {
            tickAimingProgress();
            tickStateLock();
            tickAutoBolt();
        }
    }

    @Redirect(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;setSprinting(Z)V"))
    public void cancelSprint(LocalPlayer player, boolean pSprinting) {
        IGunOperator gunOperator = IGunOperator.fromLivingEntity(player);
        boolean isAiming = gunOperator.getSynIsAiming();
        ReloadState.StateType reloadStateType = gunOperator.getSynReloadState().getStateType();
        if (isAiming || (reloadStateType.isReloading() && !reloadStateType.isReloadFinishing())) {
            player.setSprinting(false);
        } else {
            player.setSprinting(pSprinting);
        }
    }

    @Inject(method = "respawn", at = @At("RETURN"))
    public void onRespawn(CallbackInfo ci) {
        // 重置客户端的 shoot 时间戳
        tacz$IsShootRecorded = true;
        tacz$ClientShootTimestamp = -1;
        // 重置客户端瞄准状态
        tacz$ClientIsAiming = false;
        tacz$ClientAimingProgress = 0;
        tacz$OldAimingProgress = 0;
        // 重置拉栓状态
        tacz$IsBolting = false;
        // 打开状态锁
        tacz$ClientStateLock = false;
        draw(ItemStack.EMPTY);
    }

    @Unique
    private void tickAutoBolt() {
        LocalPlayer player = (LocalPlayer) (Object) this;
        ItemStack mainhandItem = player.getMainHandItem();
        if (!(mainhandItem.getItem() instanceof IGun iGun)) {
            tacz$IsBolting = false;
            return;
        }
        bolt();
        if (tacz$IsBolting) {
            // 对于客户端来说，膛内弹药被填入的状态同步到客户端的瞬间，bolt 过程才算完全结束
            if (iGun.hasBulletInBarrel(mainhandItem)) {
                tacz$IsBolting = false;
            }
        }
    }

    @Unique
    private void tickAimingProgress() {
        LocalPlayer player = (LocalPlayer) (Object) this;
        ItemStack mainhandItem = player.getMainHandItem();
        // 如果主手物品不是枪械，则取消瞄准状态并将 aimingProgress 归零，返回。
        if (!(mainhandItem.getItem() instanceof IGun iGun)) {
            tacz$ClientAimingProgress = 0;
            tacz$OldAimingProgress = 0;
            return;
        }
        // 如果正在收枪，则不能瞄准
        if (System.currentTimeMillis() - tacz$ClientDrawTimestamp < 0) {
            tacz$ClientIsAiming = false;
        }
        ResourceLocation gunId = iGun.getGunId(mainhandItem);
        Optional<CommonGunIndex> gunIndexOptional = TimelessAPI.getCommonGunIndex(gunId);
        if (gunIndexOptional.isEmpty()) {
            tacz$ClientAimingProgress = 0;
            tacz$OldAimingProgress = 0;
            return;
        }
        GunData gunData = gunIndexOptional.get().getGunData();
        final float[] aimTime = new float[]{gunData.getAimTime()};
        AttachmentDataUtils.getAllAttachmentData(mainhandItem, gunData, attachmentData -> aimTime[0] += attachmentData.getAdsAddendTime());
        aimTime[0] = Math.max(0, aimTime[0]);
        float alphaProgress = (System.currentTimeMillis() - tacz$ClientAimingTimestamp + 1) / (aimTime[0] * 1000);
        tacz$OldAimingProgress = tacz$ClientAimingProgress;
        if (tacz$ClientIsAiming) {
            // 处于执行瞄准状态，增加 aimingProgress
            tacz$ClientAimingProgress += alphaProgress;
            if (tacz$ClientAimingProgress > 1) {
                tacz$ClientAimingProgress = 1;
            }
        } else {
            // 处于取消瞄准状态，减小 aimingProgress
            tacz$ClientAimingProgress -= alphaProgress;
            if (tacz$ClientAimingProgress < 0) {
                tacz$ClientAimingProgress = 0;
            }
        }
        tacz$ClientAimingTimestamp = System.currentTimeMillis();
    }

    /**
     * 此方法每 tick 执行一次，判断是否应当释放状态锁。
     */
    @Unique
    private void tickStateLock() {
        LocalPlayer player = (LocalPlayer) (Object) this;
        IGunOperator gunOperator = IGunOperator.fromLivingEntity(player);
        ReloadState reloadState = gunOperator.getSynReloadState();
        // 如果还没完成上锁，则不能释放状态锁
        long maxLockTime = 250; // 上锁允许的最大响应时间，毫秒
        long lockTime = System.currentTimeMillis() - tacz$LockTimestamp;
        if (lockTime < maxLockTime && tacz$LockedCondition != null && !tacz$LockedCondition.test(gunOperator)) {
            return;
        }
        tacz$LockedCondition = null;
        if (reloadState.getStateType().isReloading()) {
            return;
        }
        long shootCoolDown = gunOperator.getSynShootCoolDown();
        if (shootCoolDown > 0) {
            return;
        }
        if (gunOperator.getSynDrawCoolDown() > 0) {
            return;
        }
        if (gunOperator.getSynBoltCoolDown() >= 0) {
            return;
        }
        // 释放状态锁
        tacz$ClientStateLock = false;
    }

    @Override
    public boolean isAim() {
        return this.tacz$ClientIsAiming;
    }
}