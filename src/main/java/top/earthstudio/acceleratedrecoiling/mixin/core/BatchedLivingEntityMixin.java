package top.earthstudio.acceleratedrecoiling.mixin.core;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wiyuka.acceleratedrecoiling.natives.realtime.BatchedCollisions;
import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LivingEntity.class)
public class BatchedLivingEntityMixin {
    @WrapMethod(method = "pushEntities")
    private void ar$batchPlainCollisions(Operation<Void> original) {
        if (!RealtimeNative.isEnabled() || !BatchedCollisions.tryPush((LivingEntity) (Object) this)) {
            original.call();
        }
    }
}
