package top.earthstudio.acceleratedrecoiling.mixin.core;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(Holder.Reference.class)
public abstract class ClimbableTagMixin<T> {
    @Inject(method = "bindTags", at = @At("RETURN"))
    private void ar$tagsChanged(CallbackInfo ci) {
        BatchedRules.invalidatePolicy();
    }

    @Shadow
    private Set<TagKey<T>> tags;

    @Unique
    private Set<TagKey<T>> ar$cachedTags;

    @Unique
    private boolean ar$isClimbable;

    @WrapMethod(method = "is(Lnet/minecraft/tags/TagKey;)Z")
    private boolean ar$climbableTag(TagKey<T> tag, Operation<Boolean> original) {
        if (tag != BlockTags.CLIMBABLE || !RealtimeNative.isEnabled()) {
            return original.call(tag);
        }

        Set<TagKey<T>> current = this.tags;

        if (current != null && current == this.ar$cachedTags) {
            return this.ar$isClimbable;
        }

        boolean result = original.call(tag);
        if (current == this.tags) {
            this.ar$cachedTags = current;
            this.ar$isClimbable = result;
        }
        return result;
    }
}
