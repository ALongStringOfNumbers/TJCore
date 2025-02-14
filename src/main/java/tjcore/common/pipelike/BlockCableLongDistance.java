package tjcore.common.pipelike;

import com.google.common.base.Preconditions;
import gregtech.api.GregTechAPI;
import gregtech.api.capability.GregtechCapabilities;
import gregtech.api.cover.ICoverable;
import gregtech.api.damagesources.DamageSources;
import gregtech.api.items.toolitem.ToolClasses;
import gregtech.api.items.toolitem.ToolHelper;
import gregtech.api.pipenet.block.material.BlockMaterialPipe;
import gregtech.api.pipenet.tile.IPipeTile;
import gregtech.api.pipenet.tile.TileEntityPipeBase;
import gregtech.api.unification.material.Material;
import gregtech.api.unification.material.properties.WireProperties;
import gregtech.api.util.GTUtility;
import gregtech.client.renderer.pipe.CableRenderer;
import gregtech.common.pipelike.cable.Insulation;
import gregtech.common.pipelike.cable.ItemBlockCable;
import gregtech.common.pipelike.cable.tile.TileEntityCable;
import gregtech.core.advancement.AdvancementTriggers;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.lang3.tuple.Pair;
import tjcore.common.pipelike.net.WorldLongDistanceNet;
import tjcore.common.pipelike.tile.TileEntityLongDistanceCable;
import tjcore.common.pipelike.tile.TileEntityLongDistanceCableTickable;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public class BlockCableLongDistance extends BlockMaterialPipe<Insulation, WireProperties, WorldLongDistanceNet> {
    private final Map<Material, WireProperties> enabledMaterials = new TreeMap<>();

    public BlockCableLongDistance(Insulation cableType) {
        super(cableType);
        setHarvestLevel("cutter", 1);
    }

    public void addCableMaterial(Material material, WireProperties wireProperties) {
        Preconditions.checkNotNull(material, "material was null");
        Preconditions.checkNotNull(wireProperties, "material %s wireProperties was null", material);
        Preconditions.checkArgument(GregTechAPI.MATERIAL_REGISTRY.getNameForObject(material) != null, "material %s is not registered", material);
        if (!pipeType.orePrefix.isIgnored(material)) {
            this.enabledMaterials.put(material, wireProperties);
        }
    }

    public Collection<Material> getEnabledMaterials() {
        return Collections.unmodifiableSet(enabledMaterials.keySet());
    }

    public Class<Insulation> getPipeTypeClass() {
        return Insulation.class;
    }

    @Override
    protected WireProperties createProperties(Insulation insulation, Material material) {
        return insulation.modifyProperties(enabledMaterials.getOrDefault(material, getFallbackType()));
    }

    @Override
    protected WireProperties getFallbackType() {
        return enabledMaterials.values().iterator().next();
    }

    @Override
    public WorldLongDistanceNet getWorldPipeNet(World world) {
        return WorldLongDistanceNet.getWorldLongDistanceNet(world);
    }

    @Override
    public void getSubBlocks(@Nonnull CreativeTabs itemIn, @Nonnull NonNullList<ItemStack> items) {
        for (Material material : enabledMaterials.keySet()) {
            items.add(getItem(material));
        }
    }

    @Override
    protected boolean isPipeTool(@Nonnull ItemStack stack) {
        return ToolHelper.isTool(stack, ToolClasses.WIRE_CUTTER);
    }

    @Override
    public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
        //TileEntity tile = world.getTileEntity(pos);
        //if (tile instanceof TileEntityLongDistanceCable) {
        //    TileEntityLongDistanceCable cable = (TileEntityLongDistanceCable) tile;
        //    int temp = cable.getTemperature();
        //    // max light at 5000 K
        //    // min light at 500 K
        //    if(temp >= 5000) {
        //        return 15;
        //    }
        //    if (temp > 500) {
        //        return (temp - 500) * 15 / (4500);
        //    }
        //}
        return 0;
    }

    @Override
    public void breakBlock(@Nonnull World worldIn, @Nonnull BlockPos pos, @Nonnull IBlockState state) {
        if (worldIn.isRemote) {
            TileEntityLongDistanceCable cable = (TileEntityLongDistanceCable) getPipeTileEntity(worldIn, pos);
            cable.killParticle();
        }
        super.breakBlock(worldIn, pos, state);
    }

    @Override
    public boolean canPipesConnect(IPipeTile<Insulation, WireProperties> selfTile, EnumFacing side, IPipeTile<Insulation, WireProperties> sideTile) {
        return selfTile instanceof TileEntityLongDistanceCable && sideTile instanceof TileEntityLongDistanceCable;
    }

    @Override
    public boolean canPipeConnectToBlock(IPipeTile<Insulation, WireProperties> selfTile, EnumFacing side, TileEntity tile) {
        return tile != null && tile.getCapability(GregtechCapabilities.CAPABILITY_ENERGY_CONTAINER, side.getOpposite()) != null;
    }

    @Override
    public boolean isHoldingPipe(EntityPlayer player) {
        if (player == null) {
            return false;
        }
        ItemStack stack = player.getHeldItemMainhand();
        return stack != ItemStack.EMPTY && stack.getItem() instanceof ItemBlockCable;
    }

    @Override
    public void onEntityCollision(World worldIn, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull Entity entityIn) {
        if (worldIn.isRemote) return;
        Insulation insulation = getPipeTileEntity(worldIn, pos).getPipeType();
        if (insulation.insulationLevel == -1 && entityIn instanceof EntityLivingBase) {
            EntityLivingBase entityLiving = (EntityLivingBase) entityIn;
            TileEntityCable cable = (TileEntityCable) getPipeTileEntity(worldIn, pos);
            if (cable != null && cable.getFrameMaterial() == null && cable.getNodeData().getLossPerBlock() > 0) {
                long voltage = cable.getCurrentMaxVoltage();
                double amperage = cable.getAverageAmperage();
                if (voltage > 0L && amperage > 0L) {
                    float damageAmount = (float) ((GTUtility.getTierByVoltage(voltage) + 1) * amperage * 4);
                    entityLiving.attackEntityFrom(DamageSources.getElectricDamage(), damageAmount);
                    if (entityLiving instanceof EntityPlayerMP) {
                        AdvancementTriggers.ELECTROCUTION_DEATH.trigger((EntityPlayerMP) entityLiving);
                    }
                }
            }
        }
    }

    @Override
    public boolean hasPipeCollisionChangingItem(IBlockAccess world, BlockPos pos, ItemStack stack) {
        return ToolHelper.isTool(stack, ToolClasses.WIRE_CUTTER) ||
                GTUtility.isCoverBehaviorItem(stack, () -> hasCover(getPipeTileEntity(world, pos)),
                        coverDef -> ICoverable.canPlaceCover(coverDef, getPipeTileEntity(world, pos).getCoverableImplementation()));
    }

    @Nonnull
    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings("deprecation")
    public EnumBlockRenderType getRenderType(@Nonnull IBlockState state) {
        return CableRenderer.INSTANCE.getBlockRenderType();
    }

    @Override
    public TileEntityPipeBase<Insulation, WireProperties> createNewTileEntity(boolean supportsTicking) {
        return supportsTicking ? new TileEntityLongDistanceCableTickable() : new TileEntityLongDistanceCable();
    }

    @Override
    @SideOnly(Side.CLIENT)
    protected Pair<TextureAtlasSprite, Integer> getParticleTexture(World world, BlockPos blockPos) {
        return CableRenderer.INSTANCE.getParticleTexture((TileEntityLongDistanceCable) world.getTileEntity(blockPos));
    }
}
