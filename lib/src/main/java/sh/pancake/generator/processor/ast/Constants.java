/*
 * Created on Thu Feb 09 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

public class Constants {
    public static final int GENERATOR_STEP_FINISH = 0;
    public static final int GENERATOR_STEP_START = 1;

    public static final String GENERATOR_CLASS_NAME = "$Generator";

    public static final String GENERATOR_YIELD = "step";
    public static final String GENERATOR_YIELD_ALL = "stepAll";

    public static final String GENERATOR_INNER_NEXT = "__next";

    public static final String GENERATOR_STATE = "state";
    public static final String GENERATOR_LOOP = "loop";
    public static final String GENERATOR_STATE_SWITCH = "switch";
    public static final String GENERATOR_TMP = "tmp";
    public static final String GENERATOR_RESULT = "result";

    public static final String ERR_NEXT_ON_FINISH_MESSAGE = "Called next on finished generator";
    public static final String ERR_UNREACHABLE = "Unreachable generator step";
}
