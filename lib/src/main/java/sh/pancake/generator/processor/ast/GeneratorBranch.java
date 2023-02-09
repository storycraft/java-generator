/*
 * Created on Thu Feb 09 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;import com.sun.tools.javac.util.ListBuffer;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class GeneratorBranch {
    public int id;
    public ListBuffer<JCStatement> statements;
}
