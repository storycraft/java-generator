/*
 * Created on Fri Feb 10 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.sun.source.tree.CaseTree.CaseKind;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

import sh.pancake.generator.processor.TreeMakerUtil;

public class GeneratorClass {
    public final JCVariableDecl resultField;
    public final JCVariableDecl stateField;

    public final Map<Name, JCVariableDecl> fields;

    public final Collection<JCMethodDecl> methods;
    public final Collection<GeneratorBranch> branches;

    public GeneratorClass(ClassMemberAlloc alloc, JCExpression resultType) {
        this.resultField = alloc.createPrivateField(resultType);
        this.stateField = alloc.createPrivateField(
                alloc.treeMaker.TypeIdent(TypeTag.INT),
                alloc.treeMaker.Literal(TypeTag.INT, alloc.getNextBranchId()));

        this.fields = new HashMap<>();
        this.methods = new ArrayList<>();
        this.branches = new ArrayList<>();
    }

    public JCStatement createPeekStatement(ClassMemberAlloc alloc) {
        ListBuffer<JCCase> peekCases = new ListBuffer<>();

        for (GeneratorBranch branch : branches) {
            peekCases.add(alloc.treeMaker.Case(CaseKind.STATEMENT,
                    List.of(alloc.treeMaker.Literal(TypeTag.INT, branch.id)),
                    List.of(alloc.treeMaker.Block(0, List.of(alloc.treeMaker.Exec(
                            alloc.treeMaker.Apply(List.nil(), alloc.treeMaker.Ident(branch.name),
                                    List.nil())),
                            alloc.treeMaker.Break(null)))),
                    null));
        }

        peekCases.add(alloc.treeMaker.Case(CaseKind.STATEMENT,
                List.of(alloc.treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_FINISH)),
                List.of(alloc.treeMaker.Break(null)), null));

        peekCases.add(alloc.treeMaker.Case(CaseKind.STATEMENT, List.of(alloc.treeMaker.DefaultCaseLabel()),
                List.of(alloc.treeMaker.Throw(alloc.treeMaker.NewClass(
                        null,
                        List.nil(),
                        TreeMakerUtil.createClassName(alloc.treeMaker, alloc.names, "java", "lang", "RuntimeException"),
                        List.of(alloc.treeMaker.Literal(Constants.ERR_UNREACHABLE)),
                        null))),
                null));

        Name throwableVar = alloc.names.fromString("t");

        return alloc.treeMaker.Try(
                alloc.treeMaker.Block(0,
                        List.of(alloc.treeMaker.Switch(alloc.treeMaker.Ident(stateField.name), peekCases.toList()))),
                List.of(alloc.treeMaker.Catch(
                        alloc.treeMaker.VarDef(
                                alloc.treeMaker.Modifiers(0),
                                throwableVar,
                                TreeMakerUtil.createClassName(alloc.treeMaker, alloc.names, "java", "lang",
                                        "Throwable"),
                                null),
                        alloc.treeMaker.Block(0, List.of(
                                alloc.treeMaker.Exec(alloc.treeMaker.Assign(
                                        alloc.treeMaker.Ident(stateField.name),
                                        alloc.treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_FINISH))),
                                alloc.treeMaker.Throw(alloc.treeMaker.Ident(throwableVar)))))),
                null);
    }
}
