package com.kestalkayden.hydrofarm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Tree farm bed block — thin subclass of {@link HydroponicsBedBlock}. Differs only in the
 *  accepted-seed category (TREE instead of CROP) and the BlockEntity instantiated. All
 *  cluster/water/XP/quadrant logic is inherited unchanged. */
public class TreeFarmBedBlock extends HydroponicsBedBlock {

    public TreeFarmBedBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TreeFarmBedBlockEntity(pos, state);
    }

    @Override
    protected CropAdapter.Category getAcceptedCategory() {
        return CropAdapter.Category.TREE;
    }
}
