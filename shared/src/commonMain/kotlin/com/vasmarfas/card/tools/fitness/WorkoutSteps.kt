package com.vasmarfas.card.tools.fitness

enum class StepKind { PREPARE, WORK, REST, COOLDOWN }

class TimerStep(
    val kind: StepKind,
    val seconds: Int,
    val name: String = "",
    val setIndex: Int = 0,
    val setCount: Int = 0,
    val round: Int = 0,
    val roundCount: Int = 0,
)

object WorkoutSteps {
    const val MAX_ROUNDS = 999
    const val MAX_SETS = 99

    fun fromPlan(plan: WorkoutPlan): List<TimerStep> = buildList {
        plan.exercises.forEachIndexed { index, exercise ->
            val sets = exercise.sets.coerceIn(1, MAX_SETS)
            val work = WorkoutPlans.workSeconds(exercise).coerceAtLeast(1)
            for (set in 1..sets) {
                add(TimerStep(StepKind.WORK, work, exercise.name, set, sets))
                if (set < sets && exercise.restSec > 0) {
                    add(TimerStep(StepKind.REST, exercise.restSec, exercise.name, set, sets))
                }
            }
            if (index < plan.exercises.lastIndex && exercise.restAfterSec > 0) {
                add(TimerStep(StepKind.REST, exercise.restAfterSec, plan.exercises[index + 1].name))
            }
        }
    }

    fun interval(
        prepareSec: Int,
        workSec: Int,
        restSec: Int,
        rounds: Int,
        sets: Int,
        restBetweenSetsSec: Int,
        cooldownSec: Int,
    ): List<TimerStep> = buildList {
        if (workSec <= 0 || rounds !in 1..MAX_ROUNDS || sets !in 1..MAX_SETS) return@buildList
        if (prepareSec > 0) add(TimerStep(StepKind.PREPARE, prepareSec))
        for (set in 1..sets) {
            for (round in 1..rounds) {
                add(TimerStep(StepKind.WORK, workSec, setIndex = set, setCount = sets, round = round, roundCount = rounds))
                if (round < rounds && restSec > 0) {
                    add(TimerStep(StepKind.REST, restSec, setIndex = set, setCount = sets, round = round, roundCount = rounds))
                }
            }
            if (set < sets && restBetweenSetsSec > 0) {
                add(TimerStep(StepKind.REST, restBetweenSetsSec, setIndex = set, setCount = sets))
            }
        }
        if (cooldownSec > 0) add(TimerStep(StepKind.COOLDOWN, cooldownSec))
    }

    fun totalSeconds(steps: List<TimerStep>): Int = steps.sumOf { it.seconds }
}
