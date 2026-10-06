package com.wiyuka.acceleratedrecoiling.natives.realtime.compat;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import net.minecraft.world.entity.Entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.Arrays;

public final class MoonriseSectionHelper {
    private static final MethodHandle GET_ALL_ENTITIES;
    private static final MethodHandle GET_ENTITIES_BY_SECTION;
    private static final MethodHandle GET_STORAGE;
    private static final MethodHandle GET_SIZE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ChunkEntitySlices.class, MethodHandles.lookup());

            Field allEntitiesField = ChunkEntitySlices.class.getDeclaredField("allEntities");
            allEntitiesField.setAccessible(true);
            GET_ALL_ENTITIES = lookup.unreflectGetter(allEntitiesField);

            Class<?> collectionClass = allEntitiesField.getType();
            Field entitiesBySectionField = collectionClass.getDeclaredField("entitiesBySection");
            entitiesBySectionField.setAccessible(true);
            MethodHandles.Lookup collectionLookup = MethodHandles.privateLookupIn(collectionClass, lookup);
            GET_ENTITIES_BY_SECTION = collectionLookup.unreflectGetter(entitiesBySectionField);

            Class<?> listClass = entitiesBySectionField.getType().getComponentType();
            Field storageField = listClass.getDeclaredField("storage");
            storageField.setAccessible(true);
            Field sizeField = listClass.getDeclaredField("size");
            sizeField.setAccessible(true);
            MethodHandles.Lookup listLookup = MethodHandles.privateLookupIn(listClass, lookup);
            GET_STORAGE = listLookup.unreflectGetter(storageField);
            GET_SIZE = listLookup.unreflectGetter(sizeField);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    public static Entity[] getSectionEntities(ChunkEntitySlices slices, int sectionY) {
        try {
            int sectionIndex = sectionY - slices.minSection;
            Object allEntities = GET_ALL_ENTITIES.invoke(slices);
            Object[] bySection = (Object[]) GET_ENTITIES_BY_SECTION.invoke(allEntities);
            if (bySection == null || sectionIndex < 0 || sectionIndex >= bySection.length) {
                return new Entity[0];
            }
            Object list = bySection[sectionIndex];
            if (list == null) {
                return new Entity[0];
            }
            int size = (int) GET_SIZE.invoke(list);
            if (size <= 0) {
                return new Entity[0];
            }
            Entity[] storage = (Entity[]) GET_STORAGE.invoke(list);
            return Arrays.copyOf(storage, size);
        } catch (Throwable t) {
            throw new RuntimeException("Get Moonrise slice entities failed!", t);
        }
    }
}
