package org.ngicollective.ftcsim.season;

import org.ngicollective.ftcsim.camera.GameElement;
import org.ngicollective.ftcsim.camera.ScoringVolume;
import org.ngicollective.ftcsim.camera.SimulatedScene;
import org.ngicollective.ftcsim.sim.ConfigJson;
import org.ngicollective.ftcsim.sim.FieldScore;
import org.ngicollective.ftcsim.sim.Season;

import java.util.List;
import java.util.Map;

/**
 * The 2026-2027 game as a {@link Season}: scenarios read by {@link BioBuzzScenario}, the field
 * scored by {@link BioBuzzScore}.
 *
 * <p>Stateless, so one instance serves every session.</p>
 */
public final class BioBuzz implements Season {

    public static final BioBuzz SEASON = new BioBuzz();

    private BioBuzz() {
    }

    @Override
    public String name() {
        return "BioBuzz";
    }

    @Override
    public SimulatedScene scenario(ConfigJson file) {
        return BioBuzzScenario.from(file).scene();
    }

    @Override
    public FieldScore score(List<ScoringVolume> volumes, List<GameElement> elements,
                            Map<String, Integer> swingsByStructure) {
        return BioBuzzScore.of(volumes, elements, swingsByStructure).asFieldScore();
    }
}
