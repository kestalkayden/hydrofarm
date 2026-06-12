package com.kestalkayden.hydrofarm.fluid;

import com.kestalkayden.hydrofarm.HydrofarmNeoForge;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class HydrofarmFluids {
    public static final DeferredRegister<Fluid> FLUIDS =
        DeferredRegister.create(Registries.FLUID, HydrofarmNeoForge.MOD_ID);

    /** Set inside the DeferredRegister callback so cross-loader code can reference
     *  HydrofarmFluids.LIQUID_XP directly without the .get() ceremony of DeferredHolder. */
    public static Fluid LIQUID_XP;
    public static Fluid FLUID_MILK;

    public static final DeferredHolder<Fluid, LiquidXpFluid> LIQUID_XP_HOLDER =
        FLUIDS.register("liquid_xp", () -> {
            LiquidXpFluid fluid = new LiquidXpFluid();
            LIQUID_XP = fluid;
            return fluid;
        });

    public static final DeferredHolder<Fluid, FluidMilkFluid> FLUID_MILK_HOLDER =
        FLUIDS.register("fluid_milk", () -> {
            FluidMilkFluid fluid = new FluidMilkFluid();
            FLUID_MILK = fluid;
            return fluid;
        });

    private HydrofarmFluids() {}
}
