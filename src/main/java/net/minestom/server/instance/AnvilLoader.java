package net.minestom.server.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.utils.chunk.ChunkCallback;
import net.minestom.server.world.biomes.Biome;
import net.minestom.server.world.biomes.BiomeManager;
import org.jetbrains.annotations.NotNull;
import org.jglrxavpok.hephaistos.mca.*;
import org.jglrxavpok.hephaistos.nbt.NBTCompound;
import org.jglrxavpok.hephaistos.nbt.NBTList;
import org.jglrxavpok.hephaistos.nbt.NBTTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class AnvilLoader implements IChunkLoader {
    private final static Logger LOGGER = LoggerFactory.getLogger(AnvilLoader.class);
    private static final BlockManager BLOCK_MANAGER = MinecraftServer.getBlockManager();
    private static final BiomeManager BIOME_MANAGER = MinecraftServer.getBiomeManager();
    private static final Biome BIOME = Biome.PLAINS;

    private final Map<String, RegionFile> alreadyLoaded = new ConcurrentHashMap<>();
    private final Path path;
    private final Path regionFolder;

    public AnvilLoader(@NotNull Path path) {
        this.path = path;
        this.regionFolder = path.resolve("region");
    }

    public AnvilLoader(@NotNull String path) {
        this(Path.of(path));
    }

    @Override
    public boolean loadChunk(@NotNull Instance instance, int chunkX, int chunkZ, ChunkCallback callback) {
        LOGGER.debug("Attempt loading at {} {}", chunkX, chunkZ);
        if (!Files.exists(path)) {
            // No world folder
            return false;
        }
        try {
            Chunk chunk = loadMCA(instance, chunkX, chunkZ, callback);
            return chunk != null;
        } catch (IOException | AnvilException e) {
            e.printStackTrace();
        }
        return false;
    }

    private Chunk loadMCA(Instance instance, int chunkX, int chunkZ, ChunkCallback callback) throws IOException, AnvilException {
        RegionFile mcaFile = getMCAFile(chunkX, chunkZ);
        if (mcaFile == null)
            return null;
        ChunkColumn fileChunk = mcaFile.getChunk(chunkX, chunkZ);
        if (fileChunk == null)
            return null;

        Biome[] biomes;
        if (fileChunk.getGenerationStatus().compareTo(ChunkColumn.GenerationStatus.Biomes) > 0) {
            int[] fileChunkBiomes = fileChunk.getBiomes();
            biomes = new Biome[fileChunkBiomes.length];
            for (int i = 0; i < fileChunkBiomes.length; i++) {
                final int id = fileChunkBiomes[i];
                biomes[i] = Objects.requireNonNullElse(BIOME_MANAGER.getById(id), BIOME);
            }
        } else {
            biomes = new Biome[1024]; // TODO don't hardcode
            Arrays.fill(biomes, BIOME);
        }
        Chunk chunk = new DynamicChunk(instance, biomes, chunkX, chunkZ);

        // Blocks
        {
            loadBlocks(chunk, fileChunk);
            loadTileEntities(chunk, fileChunk);
        }

        // Lights
        {
            final var chunkSections = fileChunk.getSections();
            for (var chunkSection : chunkSections) {
                Section section = chunk.getSection(chunkSection.getY());
                section.setSkyLight(chunkSection.getSkyLights());
                section.setBlockLight(chunkSection.getBlockLights());
            }
        }

        if (callback != null) {
            callback.accept(chunk);
        }

        mcaFile.forget(fileChunk);

        return chunk;
    }

    private RegionFile getMCAFile(int chunkX, int chunkZ) {
        final int regionX = CoordinatesKt.chunkToRegion(chunkX);
        final int regionZ = CoordinatesKt.chunkToRegion(chunkZ);
        return alreadyLoaded.computeIfAbsent(RegionFile.Companion.createFileName(regionX, regionZ), n -> {
            try {
                final Path regionPath = regionFolder.resolve(n);
                if (!Files.exists(regionPath)) {
                    return null;
                }
                return new RegionFile(new RandomAccessFile(regionPath.toFile(), "rw"), regionX, regionZ);
            } catch (IOException | AnvilException e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    private void loadBlocks(Chunk chunk, ChunkColumn fileChunk) {
        for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
            for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                for (int y = 0; y < 256; y++) { // TODO don't hardcode height
                    try {
                        // TODO: are there block entities here?
                        final BlockState blockState = fileChunk.getBlockState(x, y, z);
                        Block block = Block.fromNamespaceId(blockState.getName());
                        if (block == null) {
                            // Invalid block
                            continue;
                        }
                        final var properties = blockState.getProperties();
                        if (!properties.isEmpty()) {
                            block = block.withProperties(properties);
                        }
                        chunk.setBlock(x, y, z, block);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void loadTileEntities(Chunk loadedChunk, ChunkColumn fileChunk) {
        for (NBTCompound te : fileChunk.getTileEntities()) {
            final String tileEntityID = te.getString("id");
            final var x = te.getInt("x");
            final var y = te.getInt("y");
            final var z = te.getInt("z");
            if (tileEntityID == null) {
                LOGGER.warn("Tile entity has failed to load due to invalid namespace");
                continue;
            }
            if (x == null || y == null || z == null) {
                LOGGER.warn("Tile entity " + tileEntityID + " has failed to load due to invalid coordinate");
                continue;
            }
            final var handler = BLOCK_MANAGER.getHandler(tileEntityID);
            if (handler == null) {
                LOGGER.warn("Block " + tileEntityID + " does not have any corresponding handler, world will load anyway.");
                continue;
            }
            // Remove anvil tags
            te.removeTag("id")
                    .removeTag("x").removeTag("y").removeTag("z")
                    .removeTag("keepPacked");
            // Place block
            final Block block = loadedChunk.getBlock(x, y, z)
                    .withHandler(handler)
                    .withNbt(te);
            loadedChunk.setBlock(x, y, z, block);
        }
    }

    // TODO: find a way to unload MCAFiles when an entire region is unloaded

    @Override
    public void saveChunk(Chunk chunk, Runnable callback) {
        final int chunkX = chunk.getChunkX();
        final int chunkZ = chunk.getChunkZ();
        RegionFile mcaFile;
        synchronized (alreadyLoaded) {
            mcaFile = getMCAFile(chunkX, chunkZ);
            if (mcaFile == null) {
                final int regionX = CoordinatesKt.chunkToRegion(chunkX);
                final int regionZ = CoordinatesKt.chunkToRegion(chunkZ);
                final String n = RegionFile.Companion.createFileName(regionX, regionZ);
                File regionFile = new File(regionFolder.toFile(), n);
                try {
                    if (!regionFile.exists()) {
                        if (!regionFile.getParentFile().exists()) {
                            regionFile.getParentFile().mkdirs();
                        }
                        regionFile.createNewFile();
                    }
                    mcaFile = new RegionFile(new RandomAccessFile(regionFile, "rw"), regionX, regionZ);
                    alreadyLoaded.put(n, mcaFile);
                } catch (AnvilException | IOException e) {
                    LOGGER.error("Failed to save chunk " + chunkX + ", " + chunkZ, e);
                    e.printStackTrace();
                    return;
                }
            }
        }
        ChunkColumn column;
        try {
            column = mcaFile.getOrCreateChunk(chunkX, chunkZ);
        } catch (AnvilException | IOException e) {
            LOGGER.error("Failed to save chunk " + chunkX + ", " + chunkZ, e);
            e.printStackTrace();
            return;
        }
        save(chunk, column);

        try {
            LOGGER.debug("Attempt saving at {} {}", chunk.getChunkX(), chunk.getChunkZ());
            mcaFile.writeColumn(column);
        } catch (IOException e) {
            LOGGER.error("Failed to save chunk " + chunkX + ", " + chunkZ, e);
            e.printStackTrace();
            return;
        }

        if (callback != null)
            callback.run();
    }

    private void save(Chunk chunk, ChunkColumn chunkColumn) {
        NBTList<NBTCompound> tileEntities = new NBTList<>(NBTTypes.TAG_Compound);
        chunkColumn.setGenerationStatus(ChunkColumn.GenerationStatus.Full);
        for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
            for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                for (int y = 0; y < 256; y++) { // TODO don't hardcode world height
                    final Block block = chunk.getBlock(x, y, z);

                    // Block
                    BlockState state = new BlockState(block.name(), block.properties());
                    chunkColumn.setBlockState(x, y, z, state);

                    // Biome
                    int index = ((y >> 2) & 63) << 4 | ((z >> 2) & 3) << 2 | ((x >> 2) & 3); // https://wiki.vg/Chunk_Format#Biomes
                    Biome biome = chunk.getBiomes()[index];
                    chunkColumn.setBiome(x, 0, z, biome.getId());

                    // Tile entity
                    final BlockHandler handler = block.handler();
                    if (handler != null) {
                        NBTCompound nbt = Objects.requireNonNullElseGet(block.nbt(), NBTCompound::new);
                        nbt.setString("id", handler.getNamespaceId().asString());
                        nbt.setInt("x", x);
                        nbt.setInt("y", y);
                        nbt.setInt("z", z);
                        nbt.setByte("keepPacked", (byte) 0);
                        tileEntities.add(nbt);
                    }
                }
            }
        }
        chunkColumn.setTileEntities(tileEntities);
    }
}
