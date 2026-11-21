package com.thetowers.arena;

/**
 * Successive states of an arena.
 *
 * <p>The state is carried by each {@link Arena} and not by the plugin:
 * several arenas can run in parallel, each in its own phase.
 */
public enum State {

    /** The arena is waiting for players. */
    WAITING,

    /** The minimum of players is reached, the countdown is running. */
    STARTING,

    /** Game running (including sudden death, see {@link Arena#isOvertime()}). */
    PLAYING,

    /** Game finished, reset in progress. */
    FINISH
}
