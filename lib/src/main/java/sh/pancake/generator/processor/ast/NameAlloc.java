/*
 * Created on Fri Feb 10 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

public class NameAlloc {
    private static final String NAME_PREFIX = "@";

    private final Names names;

    private int nextNameId;

    public NameAlloc(Context cx) {
        names = Names.instance(cx);

        nextNameId = 0;
    }

    public Name nextName() {
        return names.fromString(NAME_PREFIX + (nextNameId++));
    }
}
