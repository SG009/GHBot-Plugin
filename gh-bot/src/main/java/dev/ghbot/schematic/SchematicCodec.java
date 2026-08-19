package dev.ghbot.schematic;

import java.util.Map;

/** A codec converts a voxel model into a schematic byte[] in a specific format. */
public interface SchematicCodec {
    String formatName();
    String fileExtension();
    /** Export the model (origin at 0,0,0) to bytes. */
    byte[] export(Map<Long, String> voxels, int minX, int minY, int minZ, int w, int h, int d) throws Exception;
}
