/*
 * Created on Fri Feb 10 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

import javax.annotation.Nullable;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

public class ClassMemberAlloc {
    private static final String VAR_PREFIX = "@";
    private static final String METHOD_PREFIX = "b";

    public final TreeMaker treeMaker;
    public final Names names;

    private final JCModifiers emptyModifiers;
    private final JCModifiers privateModifiers;

    private int nextVarId;
    private int nextMethodId;

    public ClassMemberAlloc(Context cx) {
        treeMaker = TreeMaker.instance(cx);
        names = Names.instance(cx);

        emptyModifiers = treeMaker.Modifiers(0);
        privateModifiers = treeMaker.Modifiers(Flags.PRIVATE);

        nextVarId = 0;
        nextMethodId = Constants.GENERATOR_STEP_START;
    }

    public int getNextBranchId() {
        return nextMethodId;
    }

    public GeneratorBranch createBranch() {
        return new GeneratorBranch(nextMethodId, nextMethodName(), new ListBuffer<>());
    }

    private Name nextMethodName() {
        return names.fromString(METHOD_PREFIX + (nextMethodId++));
    }

    public JCMethodDecl createMethod(JCExpression retType, List<JCStatement> stats) {
        return treeMaker.MethodDef(
                privateModifiers,
                nextMethodName(),
                retType,
                List.nil(),
                List.nil(),
                List.nil(),
                treeMaker.Block(0, stats),
                null);
    }

    public JCVariableDecl createPrivateField(JCExpression type) {
        return createPrivateField(type, null);
    }

    public JCVariableDecl createPrivateField(JCExpression type, @Nullable JCExpression init) {
        return createVariable(privateModifiers, type, init);
    }

    public JCVariableDecl createLocalVariable(JCExpression type) {
        return createVariable(emptyModifiers, type, null);
    }

    public JCVariableDecl createVariable(JCModifiers mods, JCExpression type, @Nullable JCExpression init) {
        return treeMaker.VarDef(mods, names.fromString(VAR_PREFIX + (nextVarId++)), type, init);
    }
}
