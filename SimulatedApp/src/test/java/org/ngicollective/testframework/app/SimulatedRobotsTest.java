package org.ngicollective.testframework.app;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.ngicollective.testframework.hardware.FakeHardwareMap;
import org.ngicollective.testframework.hardware.SimulatedRobot;

import java.util.Arrays;
import java.util.Collections;

class SimulatedRobotsTest {

    @Test
    void theOneRegisteredRobotIsTheRobot() {
        SimulatedRobot robot = new Named("Verity");

        assertSame(robot, SimulatedRobots.single(Collections.singletonList(robot)));
    }

    @Test
    void noRobotNamesTheFileToCreate() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SimulatedRobots.single(Collections.<SimulatedRobot>emptyList()));

        assertTrue(refused.getMessage().contains(
                "META-INF/services/org.ngicollective.testframework.hardware.SimulatedRobot"),
                refused.getMessage());
    }

    @Test
    void twoRobotsAreAMistakeRatherThanAChoice() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SimulatedRobots.single(Arrays.<SimulatedRobot>asList(
                        new Named("Verity"), new Named("Copy"))));

        assertTrue(refused.getMessage().startsWith("2 SimulatedRobots"), refused.getMessage());
    }

    private static final class Named implements SimulatedRobot {

        private final String name;

        Named(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public FakeHardwareMap create() {
            return FakeHardwareMap.builder().build();
        }
    }
}
