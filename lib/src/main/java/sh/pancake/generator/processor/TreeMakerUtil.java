/*
 * Created on Thu Feb 09 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor;

import javax.annotation.Nullable;

import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;

public class TreeMakerUtil {
    @Nullable
    public static JCExpression createClassName(TreeMaker treeMaker, Names names, String... dotSeparated) {
        if (dotSeparated.length < 1) {
            return null;
        }

        JCExpression expr = treeMaker.Ident(names.fromString(dotSeparated[0]));
        for (int i = 1; i < dotSeparated.length; i++) {
            expr = treeMaker.Select(expr, names.fromString(dotSeparated[i]));
        }

        return expr;
    }
}
