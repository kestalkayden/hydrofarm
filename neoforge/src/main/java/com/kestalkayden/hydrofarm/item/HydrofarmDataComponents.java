package com.kestalkayden.hydrofarm.item;

import com.kestalkayden.hydrofarm.HydrofarmNeoForge;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class HydrofarmDataComponents {

    public static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, HydrofarmNeoForge.MOD_ID);

    public static DataComponentType<CapturedEntity> CAPTURED_ENTITY;

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CapturedEntity>> CAPTURED_ENTITY_HOLDER =
        COMPONENTS.register("captured_entity", () -> {
            CAPTURED_ENTITY = DataComponentType.<CapturedEntity>builder()
                .persistent(CapturedEntity.CODEC)
                .networkSynchronized(CapturedEntity.STREAM_CODEC)
                .build();
            return CAPTURED_ENTITY;
        });

    private HydrofarmDataComponents() {}
}
