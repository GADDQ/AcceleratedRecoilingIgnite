package top.earthstudio.acceleratedrecoiling.mixin.core;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.MoonriseChunkSlices;
import com.wiyuka.acceleratedrecoiling.natives.realtime.index.RealtimeSection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkEntitySlices.class)
public abstract class ChunkEntitySlicesMixin implements MoonriseChunkSlices {
    @Shadow @Final public int minSection;
    @Shadow @Final public int maxSection;

    @Unique
    private RealtimeSection[] ar$realtimeSections;

    @Override
    public RealtimeSection ar$getRealtimeSection(int sectionY) {
        if (this.ar$realtimeSections == null) {
            this.ar$realtimeSections = new RealtimeSection[this.maxSection - this.minSection + 1];
        }
        int idx = sectionY - this.minSection;
        if (idx < 0 || idx >= this.ar$realtimeSections.length) {
            return null;
        }
        if (this.ar$realtimeSections[idx] == null) {
            this.ar$realtimeSections[idx] = new RealtimeSection();
        }
        return this.ar$realtimeSections[idx];
    }

    @Inject(method = "addEntity", at = @At("RETURN"))
    private void ar$onAddEntity(Entity entity, int chunkSection, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && this.ar$realtimeSections != null) {
            int idx = chunkSection - this.minSection;
            if (idx >= 0 && idx < this.ar$realtimeSections.length && this.ar$realtimeSections[idx] != null) {
                this.ar$realtimeSections[idx].added(entity);
            }
        }
    }

    @Inject(method = "removeEntity", at = @At("RETURN"))
    private void ar$onRemoveEntity(Entity entity, int chunkSection, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && this.ar$realtimeSections != null) {
            int idx = chunkSection - this.minSection;
            if (idx >= 0 && idx < this.ar$realtimeSections.length && this.ar$realtimeSections[idx] != null) {
                this.ar$realtimeSections[idx].removed(entity);
            }
        }
    }
}
