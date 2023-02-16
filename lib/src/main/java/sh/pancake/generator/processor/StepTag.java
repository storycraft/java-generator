/*
 * Created on Thu Feb 16 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor;

import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.JCTree.JCLiteral;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class StepTag {
    private JCLiteral literal;

    public static StepTag create(TreeMaker treeMaker, int step) {
        return new StepTag(treeMaker.Literal(TypeTag.INT, step));
    }

    public JCLiteral getLiteral() {
        return literal;
    }

    public void setStep(int step) {
        literal.value = step;
    }
}
