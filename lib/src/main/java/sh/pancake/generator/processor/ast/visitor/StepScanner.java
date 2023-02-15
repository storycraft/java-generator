/*
 * Created on Sat Feb 11 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast.visitor;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.tree.JCTree.*;

public class StepScanner extends TreeScanner {

    private boolean step;

    public boolean scanStep(JCTree tree) {
        step = false;
        scan(tree);
        return step;
    }

    @Override
    public void scan(JCTree tree) {
        if (step) {
            return;
        }

        super.scan(tree);
    }

    @Override
    public void visitApply(JCMethodInvocation tree) {
        String method = tree.meth.toString();

        if (tree.args.size() == 1 && ("step".equals(method) || "stepAll".equals(method))) {
            if (!step) {
                step = true;
            }
            return;
        }

        super.visitApply(tree);
    }

    @Override
    public void visitClassDef(JCClassDecl tree) {
    }
}
