package EarthRPGItem.collection;

import java.util.UUID;

public record HarvestSession(UUID player, String pointKey, long startedAt, long requiredTicks) {
}
