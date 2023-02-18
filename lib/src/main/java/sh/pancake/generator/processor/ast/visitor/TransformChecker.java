/*
 * Created on Sat Feb 11 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast.visitor;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.tree.JCTree.*;

import sh.pancake.generator.processor.ast.Constants;

public class TransformChecker extends TreeScanner {

    private boolean transform;

    public boolean shouldTransform(JCTree tree) {
        transform = false;
        scan(tree);
        return transform;
    }

    @Override
    public void scan(JCTree tree) {
        if (transform) {
            return;
        }

        super.scan(tree);
    }

    @Override
    public void visitBreak(JCBreak tree) {
        if (!transform) {
            transform = true;
        }
    }

    @Override
    public void visitContinue(JCContinue tree) {
        if (!transform) {
            transform = true;
        }
    }

    @Override
    public void visitReturn(JCReturn tree) {
        if (!transform) {
            transform = true;
        }
    }

    @Override
    public void visitApply(JCMethodInvocation tree) {
        String method = tree.meth.toString();

        if (tree.args.size() == 1 && (Constants.GENERATOR_YIELD.equals(method) || Constants.GENERATOR_YIELD_ALL.equals(method))) {
            if (!transform) {
                transform = true;
            }
            return;
        }

        super.visitApply(tree);
    }

    @Override
    public void visitClassDef(JCClassDecl tree) {
    }
}
