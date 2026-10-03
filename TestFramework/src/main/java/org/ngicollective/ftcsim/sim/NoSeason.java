package org.ngicollective.ftcsim.sim;

import org.ngicollective.ftcsim.camera.GameElement;
import org.ngicollective.ftcsim.camera.ScoringVolume;
import org.ngicollective.ftcsim.camera.SimulatedScene;

import java.util.List;
import java.util.Map;

/** {@link Season#none()}. */
final class NoSeason implements Season {

    static final NoSeason INSTANCE = new NoSeason();

    private NoSeason() {
    }

    @Override
    public String name() {
        return "none";
    }

    @Override
    public SimulatedScene scenario(ConfigJson file) {
        throw new IllegalArgumentException(file.source() + ": this robot plays no season, so"
                + " there is no schema to stage a scenario with; return one from the robot's"
                + " SimulatedRobot.season()");
    }

    @Override
    public FieldScore score(List<ScoringVolume> volumes, List<GameElement> elements,
                            Map<String, Integer> swingsByStructure) {
        return null;
    }
}
