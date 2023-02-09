/*
 * Created on Thu Feb 09 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

import java.util.Map;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class GeneratorMap {
    public Map<Name, JCTree.JCExpression> variables;
    public ListBuffer<GeneratorBranch> branches;
}
