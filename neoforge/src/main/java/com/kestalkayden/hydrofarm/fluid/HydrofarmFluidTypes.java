package com.kestalkayden.hydrofarm.fluid;

import com.kestalkayden.hydrofarm.HydrofarmNeoForge;

import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** NeoForge {@link FluidType}s for the mod's two custom fluids.
 *
 *  <p><b>Why this exists:</b> NeoForge patches vanilla {@code Fluid} with a {@code getFluidType()}
 *  whose default implementation delegates to {@code CommonHooks.getVanillaFluidType}, which handles
 *  only EMPTY/WATER/LAVA/MILK and otherwise throws
 *  {@code RuntimeException("Mod fluids must override getFluidType.")}. Our fluid classes live in
 *  {@code common/}, which compiles against <em>vanilla</em> — where that method does not exist — so
 *  they cannot override it there. Without this file, ANY third-party mod that calls
 *  {@code getFluidType()} on Liquid XP or Milk (rendering a tank level, reading a bucket volume,
 *  comparing fluids) hard-crashes. Hydrofarm's own code never calls it — it uses {@code FluidResource}
 *  and raw {@code Fluid} identity — which is why this went unnoticed until a backpack mod pumped
 *  Liquid XP into its own tank and crashed on every render, including on world join.
 *
 *  <p>Both fluids exist only inside tanks/pipes/pumps — never as a world block — so the physics
 *  properties are inert on purpose. {@code density} is the one that must NOT be left at a
 *  non-positive value: {@code FluidType.isLighterThanAir()} is exactly {@code density <= 0}, and
 *  third-party tank renderers use it to decide whether the fluid fills from the bottom or the top. */
public final class HydrofarmFluidTypes {

    public static final DeferredRegister<FluidType> FLUID_TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, HydrofarmNeoForge.MOD_ID);

    public static final DeferredHolder<FluidType, FluidType> LIQUID_XP_TYPE =
        FLUID_TYPES.register("liquid_xp", () -> new FluidType(inertProperties()
            .descriptionId("fluid.hydrofarm.liquid_xp")
            .lightLevel(10)));

    public static final DeferredHolder<FluidType, FluidType> FLUID_MILK_TYPE =
        FLUID_TYPES.register("fluid_milk", () -> new FluidType(inertProperties()
            .descriptionId("fluid.hydrofarm.fluid_milk")));

    /** Shared baseline: water-like density/viscosity so foreign tank renderers fill bottom-up, and
     *  every world-interaction behaviour disabled since neither fluid is ever placed in the world. */
    private static FluidType.Properties inertProperties() {
        return FluidType.Properties.create()
            .density(1000)
            .viscosity(1000)
            .temperature(300)
            .canConvertToSource(false)
            .canDrown(false)
            .canSwim(false)
            .canPushEntity(false)
            .canExtinguish(false)
            .canHydrate(false)
            .supportsBoating(false);
    }

    private HydrofarmFluidTypes() {}
}
