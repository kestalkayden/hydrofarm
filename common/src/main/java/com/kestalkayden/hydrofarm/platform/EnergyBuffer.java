package com.kestalkayden.hydrofarm.platform;

/** A block's insert-side energy store — what a cable network pushes into and a powered consumer
 *  draws down. Implemented by powered machines (autocrafter, repulser) so the insert-only capability
 *  wrappers and the {@link com.kestalkayden.hydrofarm.block.EnergyNetwork} pull helper can be shared
 *  instead of re-written per block. */
public interface EnergyBuffer {

    /** Accept up to {@code amount} energy; return the amount accepted. */
    int insertEnergy(int amount);

    int getEnergyStored();

    int getEnergyCapacity();

    /** Direct setter used by the capability wrappers' transaction snapshot restore. */
    void setEnergyStored(int v);
}
