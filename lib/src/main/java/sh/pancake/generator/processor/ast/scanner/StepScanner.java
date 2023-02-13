/*
 * Created on Sat Feb 11 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast.scanner;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.tree.JCTree.*;

public class StepScanner extends TreeScanner {

    private boolean doStep = false;

    @Override
    public void scan(JCTree tree) {
        if (doStep) {
            return;
        }

        super.scan(tree);
    }

    @Override
    public void visitApply(JCMethodInvocation tree) {
        super.visitApply(tree);

        String method = tree.meth.toString();

        if ("step".equals(method) || "stepAll".equals(method)) {
            if (!doStep) {
                doStep = true;
            }
        }
    }

    public boolean containsStep() {
        return doStep;
    }
}
