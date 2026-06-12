package com.kestalkayden.hydrofarm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import com.kestalkayden.hydrofarm.HydrofarmConfig;
import com.kestalkayden.hydrofarm.HydrofarmRefs;

/** Tree farm bed — reuses all the cluster/water/XP/quadrant logic from the hydroponics bed
 *  via inheritance, just registers its own {@link net.minecraft.world.level.block.entity.BlockEntityType}
 *  so cluster discovery scopes per bed-type (a hydroponics bed adjacent to a tree bed is
 *  two separate clusters, not one). The accepted-seed category is enforced at the Block
 *  level via {@link TreeFarmBedBlock#getAcceptedCategory()}. */
public class TreeFarmBedBlockEntity extends HydroponicsBedBlockEntity {
    public TreeFarmBedBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.TREE_FARM_BED_BE.get(), pos, state);
    }

    /** Tree farm reads its own XP toggle, separate from the hydroponics bed it inherits from. */
    @Override
    public boolean outputFluidEnabled() {
        return HydrofarmConfig.treeFarmBedXp();
    }
}
