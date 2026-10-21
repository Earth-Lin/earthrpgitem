package EarthRPGItem.items;

import java.util.concurrent.ThreadLocalRandom;

public record RandomAttributeRange(int minimum, int maximum) {

    public RandomAttributeRange {
        if (minimum > maximum) {
            int swap = minimum;
            minimum = maximum;
            maximum = swap;
        }
    }

    public int roll() {
        if (minimum == maximum) return minimum;
        return ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
    }
}
