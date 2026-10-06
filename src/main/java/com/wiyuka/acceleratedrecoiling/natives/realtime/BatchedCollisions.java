package com.wiyuka.acceleratedrecoiling.natives.realtime;

import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import com.wiyuka.acceleratedrecoiling.natives.realtime.index.IndexedEntity;
import com.wiyuka.acceleratedrecoiling.natives.realtime.index.IndexedSection;
import com.wiyuka.acceleratedrecoiling.natives.realtime.index.RealtimeSection;

import net.minecraft.server.level.ServerLevel;

import net.minecraft.util.AbortableIterationConsumer.Continuation;
import net.minecraft.util.profiling.Profiler;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.Reference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.util.ArrayDeque;
import java.util.ArrayList;

import top.earthstudio.acceleratedrecoiling.mixin.core.BatchedLevelAccess;
import top.earthstudio.acceleratedrecoiling.mixin.core.LivingEntityAccess;
import top.earthstudio.acceleratedrecoiling.mixin.core.RealtimeGetterAccess;

public final class BatchedCollisions {
    private static final int SECTION_ADDRESS_OFFSET = 0;
    private static final int SECTION_COUNT_OFFSET = SECTION_ADDRESS_OFFSET + Long.BYTES;
    private static final int SECTION_STRIDE_OFFSET = SECTION_COUNT_OFFSET + Integer.BYTES;
    private static final int SECTION_SPATIAL_OFFSET = SECTION_STRIDE_OFFSET + Integer.BYTES;
    private static final int SECTION_COINCIDENT_OFFSET = SECTION_SPATIAL_OFFSET + Long.BYTES;
    private static final int SECTION_DESCRIPTOR_BYTES = SECTION_COINCIDENT_OFFSET + Long.BYTES;

    private static final boolean NATIVE_PUSH = Boolean.parseBoolean(System.getProperty("ar.nativePush", "true"));
    private static final int OUTPUT_FIELD_COUNT = NATIVE_PUSH
            ? OutputField.values().length : OutputField.PUSH_TARGETS.ordinal() + 1;
    private static final int OUTPUT_BYTES_PER_ENTRY = OUTPUT_FIELD_COUNT * Long.BYTES;

    private enum OutputField {
        COLLISIONS,
        PUSH_TARGETS,
        IMPULSE_X,
        IMPULSE_Z;

        int byteOffset(int entries) {
            return ordinal() * entries * Long.BYTES;
        }
    }

    private BatchedCollisions() {
    }

    private static final ThreadLocal<ArrayDeque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private static final class Frame {
        final ArrayList<RealtimeSection.View> sections = new ArrayList<>();

        ByteBuffer sectionDescriptors = buffer(256);
        ByteBuffer output = buffer(4096);

        int entryCount;
        int sourceSection;

        void ensureCapacity() {
            if (sectionDescriptors.capacity() < (long) sections.size() * SECTION_DESCRIPTOR_BYTES) {
                sectionDescriptors = buffer(Math.multiplyExact(sections.size(), SECTION_DESCRIPTOR_BYTES * 2));
            }

            if (output.capacity() < (long) entryCount * OUTPUT_BYTES_PER_ENTRY) {
                output = buffer(Math.multiplyExact(entryCount, OUTPUT_BYTES_PER_ENTRY * 2));
            }
        }
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    public static void clear() {
        FRAMES.remove();
    }

    @SuppressWarnings("unchecked")
    public static boolean noEntityObstacles(Entity source, AABB area) {
        if (source.level().getClass() != ServerLevel.class || !BatchedRules.cleanWorld()
            || !BatchedRules.plain(source.getClass())) {
            return false;
        }
        ServerLevel level = (ServerLevel) source.level();
        if (!level.dragonParts().isEmpty()) return false;

        var getter = ((BatchedLevelAccess) level).ar$entities();

        if (!(getter instanceof ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup lookup)) {
            return false;
        }

        AABB expanded = area.inflate(1.0E-7);
        int minChunkX = net.minecraft.util.Mth.floor(expanded.minX) >> 4;
        int minChunkZ = net.minecraft.util.Mth.floor(expanded.minZ) >> 4;
        int maxChunkX = net.minecraft.util.Mth.floor(expanded.maxX) >> 4;
        int maxChunkZ = net.minecraft.util.Mth.floor(expanded.maxZ) >> 4;
        int minSectionY = net.minecraft.util.Mth.floor(expanded.minY) >> 4;
        int maxSectionY = net.minecraft.util.Mth.floor(expanded.maxY) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                var slices = lookup.getChunk(cx, cz);
                if (slices == null || !slices.status.isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL)) {
                    continue;
                }
                int startY = Math.max(minSectionY, slices.minSection);
                int endY = Math.min(maxSectionY, slices.maxSection);

                for (int sy = startY; sy <= endY; ++sy) {
                    var index = ((com.wiyuka.acceleratedrecoiling.natives.realtime.compat.MoonriseChunkSlices) (Object) slices).ar$getRealtimeSection(sy);
                    if (index != null && !index.softOnly(slices, sy)) {
                        return false;
                    }
                }
            }
        }

        if (!(area.getSize() < 1.0E-7)) {
            Profiler.get().incrementCounter("getEntities");
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static boolean tryPush(LivingEntity source) {
        if (!canPush(source)) {
            return false;
        }

        ServerLevel level = (ServerLevel) source.level();
        if (!level.dragonParts().isEmpty()) {
            return false;
        }

        var getter = ((BatchedLevelAccess) level).ar$entities();

        if (!(getter instanceof ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup lookup)) {
            return false;
        }

        var frames = FRAMES.get();
        Frame frame = frames.pollFirst();
        if (frame == null) {
            frame = new Frame();
        }

        try {
            long epoch = BatchedRules.epoch();
            var bounds = source.getBoundingBox();

            if (!collectSections(frame, lookup, bounds, epoch)) {
                return false;
            }

            prepareSectionDescriptors(frame, source);
            return queryAndPush(source, level, bounds, frame);
        } finally {
            Reference.reachabilityFence(frame.sections);
            for (var section : frame.sections) section.release();
            frame.sections.clear();
            frames.addFirst(frame);
        }
    }

    private static boolean canPush(LivingEntity source) {
        return source.level().getClass() == ServerLevel.class
                && BatchedRules.cleanWorld()
                && BatchedRules.classify(source, true) == BatchedRules.PUSHABLE;
    }

    private static boolean collectSections(Frame frame,
                                           ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup lookup,
                                           AABB bounds,
                                           long epoch) {
        boolean[] supported = { true };
        frame.entryCount = 0;

        int minChunkX = net.minecraft.util.Mth.floor(bounds.minX) >> 4;
        int minChunkZ = net.minecraft.util.Mth.floor(bounds.minZ) >> 4;
        int maxChunkX = net.minecraft.util.Mth.floor(bounds.maxX) >> 4;
        int maxChunkZ = net.minecraft.util.Mth.floor(bounds.maxZ) >> 4;
        int minSectionY = net.minecraft.util.Mth.floor(bounds.minY) >> 4;
        int maxSectionY = net.minecraft.util.Mth.floor(bounds.maxY) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                var slices = lookup.getChunk(cx, cz);
                if (slices == null || !slices.status.isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL)) {
                    continue;
                }

                int startY = Math.max(minSectionY, slices.minSection);
                int endY = Math.min(maxSectionY, slices.maxSection);

                for (int sy = startY; sy <= endY; ++sy) {
                    var index = ((com.wiyuka.acceleratedrecoiling.natives.realtime.compat.MoonriseChunkSlices) (Object) slices).ar$getRealtimeSection(sy);
                    if (index == null) continue;

                    var view = index.view(slices, sy);
                    if (view == null) {
                        supported[0] = false;
                        return false;
                    }

                    index.prepareBatch(epoch);
                    frame.sections.add(view.retain());
                    frame.entryCount = Math.addExact(frame.entryCount, view.count());
                }
            }
        }

        return supported[0];
    }

    private static void prepareSectionDescriptors(Frame frame, LivingEntity source) {
        frame.ensureCapacity();

        var owner = ((IndexedEntity) source).ar$section();
        var sourceView = owner == null ? null : owner.currentView();
        frame.sourceSection = -1;

        for (int sectionIndex = 0; sectionIndex < frame.sections.size(); sectionIndex++) {
            var section = frame.sections.get(sectionIndex);
            if (section == sourceView) {
                frame.sourceSection = sectionIndex;
            }

            int offset = sectionIndex * SECTION_DESCRIPTOR_BYTES;
            frame.sectionDescriptors.putLong(offset + SECTION_ADDRESS_OFFSET, section.address);
            frame.sectionDescriptors.putInt(offset + SECTION_COUNT_OFFSET, section.count());
            frame.sectionDescriptors.putInt(offset + SECTION_STRIDE_OFFSET, section.stride);
            frame.sectionDescriptors.putLong(offset + SECTION_SPATIAL_OFFSET, section.spatialAddress());
            frame.sectionDescriptors.putLong(offset + SECTION_COINCIDENT_OFFSET,
                    section.coincidentAddress(source.getX(), source.getZ()));
        }
    }

    private static boolean queryAndPush(LivingEntity source,
                                        ServerLevel level,
                                        AABB bounds,
                                        Frame frame) {
        IndexedEntity indexedSource = (IndexedEntity) source;
        int crammingLimit = level.getWorld().getGameRuleValue(org.bukkit.GameRule.MAX_ENTITY_CRAMMING);
        long counts = RealtimeNative.queryBatch(frame.sectionDescriptors, frame.sections.size(), frame.output, frame.sourceSection,
                indexedSource.ar$sectionSlot(), source.getX(), source.getZ(),
                bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ,
                crammingLimit > 0, NATIVE_PUSH);
        if (counts == -1) {
            return false;
        }

        if (counts < 0) {
            throw new IllegalStateException("Invalid native batch result: " + counts);
        }

        int collisionCount = (int) (counts >>> Integer.SIZE);
        int pushCount = (int) counts;
        if (pushCount < 0 || pushCount > collisionCount || collisionCount > frame.entryCount) {
            throw new IllegalStateException("Invalid native batch counts");
        }

        Profiler.get().incrementCounter("getEntities");
        dispatchPushes(source, level, frame, collisionCount, pushCount, crammingLimit);

        return true;
    }

    private static void dispatchPushes(LivingEntity source,
                                      ServerLevel level,
                                      Frame frame,
                                      int collisionCount,
                                      int pushCount,
                                      int crammingLimit) {
        boolean crammingAttempted = false;
        if (collisionCount > 0 && crammingLimit > 0 && collisionCount > crammingLimit - 1
                && source.getRandom().nextInt(4) == 0) {
            source.hurtServer(level, source.damageSources().cramming(), 6.0F);
            crammingAttempted = true;
        }

        int count = crammingAttempted ? collisionCount : pushCount;
        OutputField hits = crammingAttempted ? OutputField.COLLISIONS : OutputField.PUSH_TARGETS;
        int offset = hits.byteOffset(frame.entryCount);
        if (NATIVE_PUSH && !crammingAttempted) {
            dispatchImpulses(source, frame, count, offset);
        } else {
            for (int index = 0; index < count; index++) {
                Entity other = collisionTarget(frame, offset, index);
                ((LivingEntityAccess) source).ar$doPush(other);
            }
        }
    }

    private static Entity collisionTarget(Frame frame, int offset, int index) {
        long hit = frame.output.getLong(offset + index * Long.BYTES);
        int sectionIndex = (int) (hit >>> Integer.SIZE);
        int entitySlot = (int) hit;
        return frame.sections.get(sectionIndex).entities[entitySlot];
    }

    private static void dispatchImpulses(LivingEntity source, Frame frame, int count, int offset) {
        if (count == 0) {
            return;
        }

        Vec3 velocity = source.getDeltaMovement();
        double velocityX = velocity.x;
        double velocityY = velocity.y;
        double velocityZ = velocity.z;
        boolean sourcePushabilityChecked = false;
        boolean sourcePushable = false;
        boolean sourceNeedsSync = false;

        int impulseXOffset = OutputField.IMPULSE_X.byteOffset(frame.entryCount);
        int impulseZOffset = OutputField.IMPULSE_Z.byteOffset(frame.entryCount);
        for (int index = 0; index < count; index++) {
            var other = (LivingEntity) collisionTarget(frame, offset, index);
            int impulseOffset = index * Double.BYTES;
            double impulseX = frame.output.getDouble(impulseXOffset + impulseOffset);
            double impulseZ = frame.output.getDouble(impulseZOffset + impulseOffset);
            if (!canPushPair(source, other)) {
                continue;
            }

            if (!other.isVehicle() && isPushableInBatch(other, true)) {
                other.push(-impulseX, 0.0, -impulseZ);
            }

            if (!sourcePushabilityChecked) {
                sourcePushable = !source.isVehicle() && isPushableInBatch(source, frame.sourceSection >= 0);
                sourcePushabilityChecked = true;
            }
            if (!sourcePushable) {
                continue;
            }

            velocityX += impulseX;
            velocityY += 0.0;
            velocityZ += impulseZ;
            sourceNeedsSync = true;
        }

        if (sourceNeedsSync) {
            source.setDeltaMovement(new Vec3(velocityX, velocityY, velocityZ));
        }
    }

    private static boolean canPushPair(LivingEntity source, LivingEntity other) {
        return !other.isSleeping()
                && !other.isPassengerOfSameVehicle(source)
                && !source.noPhysics
                && !other.noPhysics;
    }

    private static boolean isPushableInBatch(LivingEntity entity, boolean sectionTicking) {
        if (sectionTicking) {
            return ((PushableMemoryEntity) entity).ar$isPushableInTickingSection();
        }

        return entity.isPushable();
    }
}
