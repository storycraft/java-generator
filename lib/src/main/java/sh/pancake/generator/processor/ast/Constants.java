/*
 * Created on Thu Feb 09 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

public class Constants {
    public static final int GENERATOR_STEP_FINISH = 0;
    public static final int GENERATOR_STEP_START = 1;

    public static final String RESULT_VAR_NAME = "0";
    public static final String STEP_VAR_NAME = "1";

    public static final String PEEK_METHOD_NAME = "2";

    public static final String VAR_NAME_SEPARATOR = "#";
    public static final String ITERATOR_TMP_PREFIX = "@";

    public static final String ERR_NEXT_ON_FINISH_MESSAGE = "Called next on finished generator";
    public static final String ERR_UNREACHABLE = "Unreachable generator step";

    public static final String BRANCH_METHOD_PREFIX = "b";
}
