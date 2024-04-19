package com.tac.guns.block;

import com.mojang.authlib.GameProfile;
import com.tac.guns.block.entity.TargetBlockEntity;
import com.tac.guns.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

public class TargetBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final BooleanProperty STAND = BooleanProperty.create("stand");
    public static final VoxelShape BOX_BOTTOM_STAND_X;
    public static final VoxelShape BOX_BOTTOM_STAND_Z;
    public static final VoxelShape BOX_BOTTOM_DOWN = Block.box(6, 0, 6, 10, 4, 10);
    public static final VoxelShape BOX_UPPER_X = Block.box(6, 0, 2, 10, 16, 14);
    public static final VoxelShape BOX_UPPER_Z = Block.box(2, 0, 6, 14, 16, 10);
    static {
        VoxelShape column = Block.box(6, 0, 6, 10, 16, 10);
        VoxelShape plate_x = Block.box(6, 13, 2, 10, 16, 14);
        VoxelShape plate_z = Block.box(2, 13, 6, 14, 16, 10);
        BOX_BOTTOM_STAND_X = Shapes.or(column,plate_x);
        BOX_BOTTOM_STAND_Z = Shapes.or(column,plate_z);
    }

    public TargetBlock() {
        super(Properties.of(Material.WOOD).sound(SoundType.WOOD).strength(2.0F, 3.0F).noOcclusion());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(STAND,true)
        );
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level pLevel, BlockState pState, BlockEntityType<T> pBlockEntityType) {
        return pState.getValue(HALF).equals(DoubleBlockHalf.LOWER) && pLevel.isClientSide() ?
                createTickerHelper(pBlockEntityType, ModBlocks.TARGET_BE.get(), TargetBlockEntity::clientTick) : null;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, STAND);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState blockState) {
        if(blockState.getValue(HALF).equals(DoubleBlockHalf.LOWER)) {
            return new TargetBlockEntity(pos, blockState);
        }else return null;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter worldIn, BlockPos pos, CollisionContext context) {
        boolean stand = state.getValue(STAND);
        boolean axis = state.getValue(FACING).getAxis().equals(Direction.Axis.X);

        if(state.getValue(HALF).equals(DoubleBlockHalf.UPPER)) {
            return stand ? (axis ? BOX_UPPER_X : BOX_UPPER_Z) : Shapes.empty();
        }
        return stand ? (axis ? BOX_BOTTOM_STAND_X : BOX_BOTTOM_STAND_Z) : BOX_BOTTOM_DOWN;
    }

    @Override
    public void tick(BlockState pState, ServerLevel pLevel, BlockPos pPos, Random pRandom) {
        // 计划刻的内容
        if(!pState.getValue(STAND)){
            pLevel.setBlock(pPos,pState.setValue(STAND,true),3);
        }
    }

    @Override
    public void onProjectileHit(Level world, BlockState state, BlockHitResult pHit, Projectile pProjectile) {
        if(pHit.getDirection().getOpposite().equals(state.getValue(FACING))){
            if(state.getValue(HALF).equals(DoubleBlockHalf.LOWER)) {
                world.getBlockEntity(pHit.getBlockPos(), TargetBlockEntity.TYPE)
                        .ifPresent(e-> e.hit(world, state, pHit.getBlockPos()));
            } else if (state.getValue(HALF).equals(DoubleBlockHalf.UPPER)) {
                world.getBlockEntity(pHit.getBlockPos().below(), TargetBlockEntity.TYPE)
                        .ifPresent(e-> e.hit(world, state, pHit.getBlockPos()));
            }
        }
    }

    public BlockState updateShape(BlockState pState, Direction pFacing, BlockState pFacingState, LevelAccessor pLevel, BlockPos pCurrentPos, BlockPos pFacingPos) {
        DoubleBlockHalf half = pState.getValue(HALF);
        boolean stand = pState.getValue(STAND);

        if(pFacing.getAxis() == Direction.Axis.Y){
            if(half.equals(DoubleBlockHalf.LOWER) && pFacing == Direction.UP || half.equals(DoubleBlockHalf.UPPER) && pFacing == Direction.DOWN){
                // 拆一半另外一半跟着没
                if(!pFacingState.is(this)){
                    return Blocks.AIR.defaultBlockState();
                }
                // 同步击倒状态
                if (pFacingState.getValue(STAND) != stand) {
                    return pState.setValue(STAND, pFacingState.getValue(STAND));
                }
            }
        }

        // 底下方块没了也拆掉
        if (half == DoubleBlockHalf.LOWER && pFacing == Direction.DOWN && !pState.canSurvive(pLevel, pCurrentPos)) {
            return Blocks.AIR.defaultBlockState();
        } else {
            return pState;
        }

    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction direction = context.getHorizontalDirection();
        BlockPos clickedPos = context.getClickedPos();
        BlockPos above = clickedPos.above();
        Level level = context.getLevel();
        if (level.getBlockState(above).canBeReplaced(context) && level.getWorldBorder().isWithinBounds(above)) {
            return this.defaultBlockState().setValue(FACING, direction);
        }
        return null;
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(world, pos, state, placer, stack);
        if (!world.isClientSide) {
            BlockPos above = pos.above();
            world.setBlock(above, state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
            world.blockUpdated(pos, Blocks.AIR);
            state.updateNeighbourShapes(world, pos, Block.UPDATE_ALL);
            if (stack.hasCustomHoverName()) {
                BlockEntity blockentity = world.getBlockEntity(pos);
                if (blockentity instanceof TargetBlockEntity e) {
                    GameProfile gameprofile = new GameProfile(null, stack.getHoverName().getString());
                    e.setOwner(gameprofile);
                }
            }
        }
    }

    public boolean canSurvive(BlockState pState, LevelReader pLevel, BlockPos pPos) {
        BlockPos blockpos = pPos.below();
        BlockState blockstate = pLevel.getBlockState(blockpos);
        return pState.getValue(HALF) == DoubleBlockHalf.LOWER ? blockstate.isFaceSturdy(pLevel, blockpos, Direction.UP) : blockstate.is(this);
    }

    @Override
    public RenderShape getRenderShape(BlockState pState) {
        return RenderShape.MODEL;
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.DESTROY;
    }

}
