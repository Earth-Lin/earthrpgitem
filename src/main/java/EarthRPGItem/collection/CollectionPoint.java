package EarthRPGItem.collection;

import org.bukkit.Material;

public final class CollectionPoint {
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final String definitionId;
    private final Material material;
    private boolean ready;
    private long refreshAt;

    public CollectionPoint(String world, int x, int y, int z, String definitionId, Material material, boolean ready, long refreshAt) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.definitionId = definitionId;
        this.material = material;
        this.ready = ready;
        this.refreshAt = refreshAt;
    }

    public String world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public String definitionId() { return definitionId; }
    public Material material() { return material; }
    public boolean ready() { return ready; }
    public long refreshAt() { return refreshAt; }
    public void deplete(long refreshAt) { this.ready = false; this.refreshAt = refreshAt; }
    public void restore() { this.ready = true; this.refreshAt = 0; }
}
