package EarthRPGItem.items;

import java.util.List;

public record MessageSkill(List<String> texts, boolean selfOnly, double radius, boolean repeating, double durationSeconds, double intervalSeconds) {
}
