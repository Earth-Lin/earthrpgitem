package EarthRPGItem.collection;

import EarthRPGItem.items.RpgItem;

import java.util.Map;

public record CollectionStats(
        double farmingSpeed,
        double miningSpeed,
        double forestSpeed,
        double farmingFortune,
        double miningFortune,
        double forestFortune
) {
    public static CollectionStats empty() {
        return new CollectionStats(0, 0, 0, 0, 0, 0);
    }

    public CollectionStats add(CollectionStats other) {
        return new CollectionStats(
                farmingSpeed + other.farmingSpeed,
                miningSpeed + other.miningSpeed,
                forestSpeed + other.forestSpeed,
                farmingFortune + other.farmingFortune,
                miningFortune + other.miningFortune,
                forestFortune + other.forestFortune
        );
    }

    public static CollectionStats from(RpgItem item, Map<String, Integer> rolled) {
        return new CollectionStats(
                value(rolled, "farmingspeed", item.farmingSpeed()),
                value(rolled, "miningspeed", item.miningSpeed()),
                value(rolled, "forestspeed", item.forestSpeed()),
                value(rolled, "farmingfortune", item.farmingFortune()),
                value(rolled, "miningfortune", item.miningFortune()),
                value(rolled, "forestfortune", item.forestFortune())
        );
    }

    private static double value(Map<String, Integer> rolled, String key, double fallback) {
        Integer value = rolled.get(key);
        return value == null ? fallback : value;
    }
}
