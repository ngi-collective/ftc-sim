package org.ngicollective.testframework.dashboard.protocol;

import java.util.Collections;
import java.util.List;

/**
 * The field arrangements this session can be put into, and which one it is in.
 *
 * <p>A scenario used to be a command-line flag, which meant choosing one cost a restart and
 * knowing what there was to choose from cost a look in a directory. Both of those are things the
 * browser is already the right place for, and neither needs the simulation to stop.</p>
 *
 * <p>In the connect greeting, for the same reason {@link ScorePayload} is: nothing else on this
 * socket tells a browser what the alternatives are. {@code sim/scene} describes the arrangement
 * in force and says nothing about the files beside it.</p>
 */
public final class ScenariosPayload {

    /**
     * Every scenario on the server's disk, by the name {@code sim/scenario} takes, sorted.
     *
     * <p>Possibly empty, which is a team that has written none rather than an error. The browser
     * still offers {@link #active}'s null case, because the field without a scenario is always a
     * legitimate thing to be looking at.</p>
     */
    public final List<String> scenarios;

    /**
     * Where those files live, absolute, so the UI can say where to put a new one.
     *
     * <p>The same courtesy {@code layout/list} extends with its own directory: a picker with three
     * entries and no indication of where they came from is a dead end for anyone wanting a
     * fourth.</p>
     */
    public final String directory;

    /**
     * The one in force, or null for the robot's own field.
     *
     * <p>Null is a real answer and not a missing one. A session started without {@code --scenario}
     * is showing the official BioBuzz field with nothing staged on it, and that is a state a
     * browser has to be able to display and to return to &mdash; so it is also what
     * {@code sim/scenario} takes to get back there.</p>
     */
    public final String active;

    public ScenariosPayload(List<String> scenarios, String directory, String active) {
        this.scenarios = Collections.unmodifiableList(scenarios);
        this.directory = directory;
        this.active = active;
    }
}
