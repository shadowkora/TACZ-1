package com.tacz.guns.entity.gun;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.attachment.AttachmentType;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.gun.ReloadState;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public class GunAim {
    private final LivingEntity shooter;
    private final ModDataHolder data;

    public GunAim(LivingEntity shooter, ModDataHolder data) {
        this.shooter = shooter;
        this.data = data;
    }

    public void aim(boolean isAim) {
        data.isAiming = isAim;
    }

    public void zoom() {
        if (data.currentGunItem == null) {
            return;
        }
        ItemStack currentGunItem = data.currentGunItem.get();
        if (!(currentGunItem.getItem() instanceof IGun iGun)) {
            return;
        }
        ItemStack scopeItem = iGun.getAttachment(currentGunItem, AttachmentType.SCOPE);
        IAttachment iAttachment = IAttachment.getIAttachmentOrNull(scopeItem);
        if (iAttachment != null) {
            TimelessAPI.getCommonAttachmentIndex(iAttachment.getAttachmentId(scopeItem)).ifPresent(index -> {
                int zoomNumber = iAttachment.getZoomNumber(scopeItem);
                ++zoomNumber;
                // 避免上溢变成负的
                zoomNumber = zoomNumber % (Integer.MAX_VALUE - 1);
                iAttachment.setZoomNumber(scopeItem, zoomNumber);
                iGun.installAttachment(currentGunItem, scopeItem);
            });
        }
    }

    public void tickAimingProgress() {
        // currentGunItem 如果为 null，则取消瞄准状态并将 aimingProgress 归零。
        if (data.currentGunItem == null || !(data.currentGunItem.get().getItem() instanceof IGun iGun)) {
            data.aimingProgress = 0;
            data.aimingTimestamp = System.currentTimeMillis();
            return;
        }
        ItemStack currentGunItem = data.currentGunItem.get();
        // 如果获取不到 gunIndex，则取消瞄准状态并将 aimingProgress 归零，返回。
        ResourceLocation gunId = iGun.getGunId(currentGunItem);
        Optional<CommonGunIndex> gunIndexOptional = TimelessAPI.getCommonGunIndex(gunId);
        if (gunIndexOptional.isEmpty()) {
            data.aimingProgress = 0;
            return;
        }
        GunData gunData = gunIndexOptional.get().getGunData();
        final float[] aimTime = new float[]{gunData.getAimTime()};
        AttachmentDataUtils.getAllAttachmentData(currentGunItem, gunData, attachmentData -> aimTime[0] += attachmentData.getAdsAddendTime());
        aimTime[0] = Math.max(0, aimTime[0]);
        float alphaProgress = (System.currentTimeMillis() - data.aimingTimestamp + 1) / (aimTime[0] * 1000);
        if (data.isAiming) {
            // 处于执行瞄准状态，增加 aimingProgress
            data.aimingProgress += alphaProgress;
            if (data.aimingProgress > 1) {
                data.aimingProgress = 1;
            }
        } else {
            // 处于取消瞄准状态，减小 aimingProgress
            data.aimingProgress -= alphaProgress;
            if (data.aimingProgress < 0) {
                data.aimingProgress = 0;
            }
        }
        data.aimingTimestamp = System.currentTimeMillis();
    }

    public void tickSprint() {
        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        ReloadState reloadState = operator.getSynReloadState();
        if (data.isAiming || (reloadState.getStateType().isReloading() && !reloadState.getStateType().isReloadFinishing())) {
            shooter.setSprinting(false);
        }
        if (data.sprintTimestamp == -1) {
            data.sprintTimestamp = System.currentTimeMillis();
        }
        if (data.currentGunItem == null) {
            return;
        }
        ItemStack gunItem = data.currentGunItem.get();
        IGun iGun = IGun.getIGunOrNull(gunItem);
        if (iGun == null) {
            return;
        }
        TimelessAPI.getCommonGunIndex(iGun.getGunId(gunItem)).ifPresentOrElse(gunIndex -> {
            float gunSprintTime = gunIndex.getGunData().getSprintTime();
            if (shooter.isSprinting()) {
                data.sprintTimeS += System.currentTimeMillis() - data.sprintTimestamp;
                if (data.sprintTimeS > gunSprintTime) {
                    data.sprintTimeS = gunSprintTime;
                }
            } else {
                data.sprintTimeS -= System.currentTimeMillis() - data.sprintTimestamp;
                if (data.sprintTimeS < 0) {
                    data.sprintTimeS = 0;
                }
            }
        }, () -> data.sprintTimeS = 0);
        data.sprintTimestamp = System.currentTimeMillis();
    }
}