package com.kestalkayden.hydrofarm.fluid;

import com.kestalkayden.hydrofarm.HydrofarmNeoForge;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class HydrofarmFluids {
    public static final DeferredRegister<Fluid> FLUIDS =
        DeferredRegister.create(Registries.FLUID, HydrofarmNeoForge.MOD_ID);

    /** Set inside the DeferredRegister callback so cross-loader code can reference
     *  HydrofarmFluids.LIQUID_XP directly without the .get() ceremony of DeferredHolder. */
    public static Fluid LIQUID_XP;
    public static Fluid FLUID_MILK;

    /** NeoForge-only subclasses that supply the mandatory {@link FluidType}. The common classes
     *  compile against vanilla and so cannot override {@code getFluidType()} themselves — see
     *  {@link HydrofarmFluidTypes} for why omitting it crashes third-party mods. Registering the
     *  subclass does not change the registry ID, so existing saves are unaffected. */
    private static final class NeoForgeLiquidXpFluid extends LiquidXpFluid {
        @Override
        public FluidType getFluidType() { return HydrofarmFluidTypes.LIQUID_XP_TYPE.value(); }
    }

    private static final class NeoForgeFluidMilkFluid extends FluidMilkFluid {
        @Override
        public FluidType getFluidType() { return HydrofarmFluidTypes.FLUID_MILK_TYPE.value(); }
    }

    public static final DeferredHolder<Fluid, LiquidXpFluid> LIQUID_XP_HOLDER =
        FLUIDS.register("liquid_xp", () -> {
            LiquidXpFluid fluid = new NeoForgeLiquidXpFluid();
            LIQUID_XP = fluid;
            return fluid;
        });

    public static final DeferredHolder<Fluid, FluidMilkFluid> FLUID_MILK_HOLDER =
        FLUIDS.register("fluid_milk", () -> {
            FluidMilkFluid fluid = new NeoForgeFluidMilkFluid();
            FLUID_MILK = fluid;
            return fluid;
        });

    private HydrofarmFluids() {}
}
