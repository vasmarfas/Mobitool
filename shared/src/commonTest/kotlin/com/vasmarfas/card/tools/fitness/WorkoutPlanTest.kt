package com.vasmarfas.card.tools.fitness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val benchPress = WorkoutExercise(
    id = "e1",
    name = "Bench press",
    sets = 3,
    reps = 10,
    weightKg = 50.0,
    restSec = 60,
    restAfterSec = 120,
)

private val plank = WorkoutExercise(
    id = "e2",
    name = "Plank",
    sets = 2,
    reps = 0,
    durationSec = 45,
    restSec = 30,
    restAfterSec = 90,
)

class WorkoutPlanTest {
    @Test
    fun durationAndVolume() {
        val single = WorkoutPlan("p1", "Push", listOf(benchPress))
        assertEquals(30, WorkoutPlans.workSeconds(benchPress))
        assertEquals(45, WorkoutPlans.workSeconds(plank))
        assertEquals(210, WorkoutPlans.estimatedSeconds(single))
        assertEquals(1500.0, WorkoutPlans.totalVolume(single), 1e-9)

        val both = WorkoutPlan("p2", "Push", listOf(benchPress, plank))
        assertEquals(210 + 120 + 120, WorkoutPlans.estimatedSeconds(both))
        assertEquals(5, WorkoutPlans.totalSets(both))
        assertEquals(1500.0, WorkoutPlans.totalVolume(both), 1e-9)
    }

    @Test
    fun jsonRoundTrip() {
        val plans = listOf(WorkoutPlan("p1", "Push", listOf(benchPress, plank)))
        val restored = WorkoutPlans.decode(WorkoutPlans.encode(plans))
        assertEquals(plans, restored)
        assertEquals(plans[0], WorkoutPlans.decodeOne(WorkoutPlans.encodeOne(plans[0])))
        assertNull(WorkoutPlans.decode("not json"))
        assertNull(WorkoutPlans.decodeOne("[]"))

        val fresh = WorkoutPlans.withFreshIds(plans[0])
        assertTrue(fresh.id != plans[0].id)
        assertTrue(fresh.exercises.map { it.id }.toSet().size == 2)
        assertEquals(plans[0].exercises.map { it.name }, fresh.exercises.map { it.name })
    }

    @Test
    fun planStepsAlternateWorkAndRest() {
        val steps = WorkoutSteps.fromPlan(WorkoutPlan("p1", "Push", listOf(benchPress)))
        assertEquals(5, steps.size)
        assertEquals(listOf(StepKind.WORK, StepKind.REST, StepKind.WORK, StepKind.REST, StepKind.WORK), steps.map { it.kind })
        assertEquals(210, WorkoutSteps.totalSeconds(steps))
        assertEquals(3, steps.first().setCount)
    }

    @Test
    fun tabataIntervals() {
        val steps = WorkoutSteps.interval(10, 20, 10, 8, 1, 60, 0)
        assertEquals(16, steps.size)
        assertEquals(StepKind.PREPARE, steps.first().kind)
        assertEquals(240, WorkoutSteps.totalSeconds(steps))
        assertEquals(8, steps.count { it.kind == StepKind.WORK })

        val twoSets = WorkoutSteps.interval(0, 30, 15, 4, 2, 60, 120)
        assertEquals(8, twoSets.count { it.kind == StepKind.WORK })
        assertEquals(1, twoSets.count { it.kind == StepKind.COOLDOWN })
        assertEquals(8 * 30 + 6 * 15 + 60 + 120, WorkoutSteps.totalSeconds(twoSets))
    }

    @Test
    fun intervalsOutsideTheLimitsBuildNothing() {
        assertTrue(WorkoutSteps.interval(10, 0, 10, 8, 1, 60, 0).isEmpty())
        assertTrue(WorkoutSteps.interval(10, 20, 10, 100_000_000, 1, 60, 0).isEmpty())
        assertTrue(WorkoutSteps.interval(10, 20, 10, 8, WorkoutSteps.MAX_SETS + 1, 60, 0).isEmpty())
        assertEquals(WorkoutSteps.MAX_ROUNDS, WorkoutSteps.interval(0, 20, 0, WorkoutSteps.MAX_ROUNDS, 1, 0, 0).size)
    }
}
