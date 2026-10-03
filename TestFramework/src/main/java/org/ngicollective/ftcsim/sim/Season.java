package org.ngicollective.ftcsim.sim;

import org.ngicollective.ftcsim.camera.GameElement;
import org.ngicollective.ftcsim.camera.ScoringVolume;
import org.ngicollective.ftcsim.camera.SimulatedScene;

import java.util.List;
import java.util.Map;

/**
 * The game a field is set up for: the part of the simulator that changes every September.
 *
 * <p>Everything else is the same from one season to the next. A robot is a chassis on mecanum
 * wheels whatever the game, a ball rolls the same way, a camera projects tags through the same
 * lens, and the field's furniture is posed boxes and cylinders on optional pivots. What a season
 * adds is the vocabulary those are arranged in: how a scenario file names the places a ball can
 * be staged, and what the elements in a region are worth.</p>
 *
 * <p>A season is supplied by the team's {@code SimulatedRobot}, because the team is the one who
 * knows which game it is practising. The core never names one.</p>
 */
public interface Season {

    /** The game's name, as a failure message or a dashboard would show it: {@code "BioBuzz"}. */
    String name();

    /**
     * The field a scenario file describes, ready to render and to simulate.
     *
     * <p>The file has been found and parsed, but its version has not been checked: the schema is
     * this season's, so the version is too. Call {@link ConfigJson#requireVersion} first.</p>
     *
     * @throws IllegalArgumentException naming the file and the field when the file is wrong
     */
    SimulatedScene scenario(ConfigJson file);

    /**
     * What the field is worth right now, or null when this season has nothing to score on it.
     *
     * @param volumes every scoring volume on the field, posed as it is now
     * @param elements every element, where the solver has it now
     * @param swingsByStructure completed swings of each pivot, by structure name, as
     *     {@code PivotState.swingsOf} counts them
     */
    FieldScore score(List<ScoringVolume> volumes, List<GameElement> elements,
                     Map<String, Integer> swingsByStructure);

    /**
     * No game at all: a gym with a robot in it.
     *
     * <p>What a robot that names no season plays. It refuses scenarios, since there is no schema
     * to read one with, and has no score: a 0-0 would be a claim about a game nobody is
     * playing.</p>
     */
    static Season none() {
        return NoSeason.INSTANCE;
    }
}
