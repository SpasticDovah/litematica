package fi.dy.masa.litematica.scheduler.tasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableCollection;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.render.infohud.IInfoHudRenderer;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.scheduler.TaskBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.structure.StructureBoundingBox;

public class TaskPasteSchematicSetblock extends TaskBase implements IInfoHudRenderer
{
    private final ArrayListMultimap<ChunkPos, StructureBoundingBox> boxesInChunks = ArrayListMultimap.create();
    private final List<String> infoHudLines = new ArrayList<>();
    private final int maxCommandsPerTick;
    private int sentCommandsThisTick;
    private int sentCommandsTotal;
    private boolean finished;

    public TaskPasteSchematicSetblock(SchematicPlacement placement)
    {
        this.maxCommandsPerTick = Configs.Generic.PASTE_COMMAND_LIMIT.getIntegerValue();

        Set<ChunkPos> touchedChunks = placement.getTouchedChunks();

        for (ChunkPos pos : touchedChunks)
        {
            ImmutableCollection<StructureBoundingBox> boxes = placement.getBoxesWithinChunk(pos.x, pos.z).values();
            this.boxesInChunks.putAll(pos, boxes);
        }

        InfoHud.getInstance().addInfoHudRenderer(this, true);
        this.updateInfoHudLines();
    }

    @Override
    public boolean canExecute()
    {
        Minecraft mc = Minecraft.getMinecraft();

        // Only use this command-based task in multiplayer
        return this.boxesInChunks.isEmpty() == false && mc.world != null && mc.player != null && mc.isSingleplayer() == false;
    }

    @Override
    public boolean shouldRemove()
    {
        return this.canExecute() == false;
    }

    @Override
    public boolean execute()
    {
        Minecraft mc = Minecraft.getMinecraft();
        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        WorldClient worldClient = mc.world;
        this.sentCommandsThisTick = 0;
        int processed = 0;

        if (this.boxesInChunks.isEmpty() == false)
        {
            Set<ChunkPos> keys = new HashSet<>(this.boxesInChunks.keySet());

            for (ChunkPos pos : keys)
            {
                if (this.canProcessChunk(pos, worldSchematic, worldClient))
                {
                    List<StructureBoundingBox> boxes = this.boxesInChunks.get(pos);

                    for (int i = 0; i < boxes.size(); ++i)
                    {
                        StructureBoundingBox box = boxes.get(i);

                        if (this.processBox(pos, box, worldSchematic, worldClient, mc.player))
                        {
                            boxes.remove(i);
                            --i;

                            if (boxes.isEmpty())
                            {
                                this.boxesInChunks.remove(pos, boxes);
                                ++processed;
                            }
                        }

                        if (this.sentCommandsThisTick >= this.maxCommandsPerTick)
                        {
                            return false;
                        }
                    }
                }
            }

            if (processed > 0)
            {
                this.updateInfoHudLines();
            }
        }

        if (this.boxesInChunks.isEmpty())
        {
            this.finished = true;
            return true;
        }

        return false;
    }

    protected boolean canProcessChunk(ChunkPos pos, World worldSchematic, World worldClient)
    {
        if (worldSchematic.getChunkProvider().isChunkGeneratedAt(pos.x, pos.z) == false)
        {
            return false;
        }

        for (int cx = pos.x - 1; cx <= pos.x + 1; ++cx)
        {
            for (int cz = pos.z - 1; cz <= pos.z + 1; ++cz)
            {
                if (worldClient.getChunkProvider().isChunkGeneratedAt(cx, cz) == false)
                {
                    return false;
                }
            }
        }

        // Chunk exists in the schematic world, and all the surrounding chunks are loaded in the client world, good to go
        return true;
    }

    protected boolean processBox(ChunkPos pos, StructureBoundingBox box,
            WorldSchematic worldSchematic, WorldClient worldClient, EntityPlayerSP player)
    {
        final int minX = box.minX;
        final int minY = box.minY;
        final int minZ = box.minZ;
        final int maxX = box.maxX;
        final int maxY = box.maxY;
        final int maxZ = box.maxZ;
        BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos();
        Chunk chunkSchematic = worldSchematic.getChunkProvider().getLoadedChunk(pos.x, pos.z);
        Chunk chunkClient = worldClient.getChunkProvider().getLoadedChunk(pos.x, pos.z);

        for (int z = minZ; z <= maxZ; ++z)
        {
            for (int x = minX; x <= maxX; ++x)
            {
                for (int y = minY; y <= maxY; ++y)
                {
                    posMutable.setPos(x, y, z);
                    IBlockState stateSchematic = chunkSchematic.getBlockState(posMutable);

                    if (chunkClient.getBlockState(posMutable).getActualState(worldClient, posMutable) != stateSchematic)
                    {
                        this.sendSetBlockCommand(x, y, z, stateSchematic, player);

                        if (++this.sentCommandsThisTick >= this.maxCommandsPerTick)
                        {
                            // All finished for this box
                            if (z >= maxZ && x >= maxX && y >= maxY)
                            {
                                return true;
                            }
                            else
                            {
                                return false;
                            }
                        }
                    }
                }
            }
        }

        return true;
    }

    private void sendSetBlockCommand(int x, int y, int z, IBlockState state, EntityPlayerSP player)
    {
        Block block = state.getBlock();
        ResourceLocation rl = Block.REGISTRY.getNameForObject(block);

        if (rl == null)
        {
            return;
        }

        String blockName = rl.toString();
        String cmdName = Configs.Generic.PASTE_COMMAND_SETBLOCK.getStringValue();
        String strCommand = String.format("/%s %d %d %d %s %d", cmdName, x, y, z, blockName, block.getMetaFromState(state));

        player.sendChatMessage(strCommand);
        ++this.sentCommandsTotal;
    }

    @Override
    public void stop()
    {
        if (this.finished)
        {
            InfoUtils.showMessage(MessageType.SUCCESS, "litematica.message.schematic_pasted_using_setblock", this.sentCommandsTotal);
        }
        else
        {
            InfoUtils.showMessage(MessageType.ERROR, "litematica.message.error.schematic_paste_failed");
        }

        InfoHud.getInstance().removeInfoHudRenderer(this, false);
    }

    private void updateInfoHudLines()
    {
        this.infoHudLines.clear();
        EntityPlayer player = Minecraft.getMinecraft().player;

        if (player != null)
        {
            List<ChunkPos> list = new ArrayList<>();
            list.addAll(this.boxesInChunks.keySet());
            PositionUtils.CHUNK_POS_COMPARATOR.setReferencePosition(new BlockPos(player.getPositionVector()));
            PositionUtils.CHUNK_POS_COMPARATOR.setClosestFirst(true);
            Collections.sort(list, PositionUtils.CHUNK_POS_COMPARATOR);

            String pre = TextFormatting.WHITE.toString() + TextFormatting.BOLD.toString();
            String title = I18n.format("litematica.gui.label.schematic_paste.missing_chunks", list.size());
            this.infoHudLines.add(String.format("%s%s%s", pre, title, TextFormatting.RESET.toString()));

            int maxLines = Math.min(list.size(), Configs.InfoOverlays.INFO_HUD_MAX_LINES.getIntegerValue());

            for (int i = 0; i < maxLines; ++i)
            {
                ChunkPos pos = list.get(i);
                this.infoHudLines.add(String.format("cx: %5d, cz: %5d (x: %d, z: %d)", pos.x, pos.z, pos.x << 4, pos.z << 4));
            }
        }
    }

    @Override
    public boolean getShouldRender()
    {
        return true;
    }

    @Override
    public List<String> getText()
    {
        return this.infoHudLines;
    }
}
