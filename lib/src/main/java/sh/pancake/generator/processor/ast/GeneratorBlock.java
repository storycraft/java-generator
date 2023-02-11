/*
 * Created on Fri Feb 10 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

import java.util.ArrayList;

import com.sun.source.tree.CaseTree.CaseKind;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import sh.pancake.generator.processor.TreeMakerUtil;

public class GeneratorBlock {
    private final JCVariableDecl stateField;
    private final JCExpression resultType;
    private final JCVariableDecl catchStateField;

    private int nextBranchId;
    private final ArrayList<GeneratorState> states;

    public GeneratorBlock(JCVariableDecl stateField, JCExpression resultType, JCVariableDecl catchStateField) {
        this.stateField = stateField;
        this.resultType = resultType;
        this.catchStateField = catchStateField;

        nextBranchId = Constants.GENERATOR_STEP_START;
        states = new ArrayList<>();
    }

    public JCExpression getResultType() {
        return resultType;
    }

    public Name getStateFieldName() {
        return stateField.name;
    }

    public Name getCatchStateFieldName() {
        return catchStateField.name;
    }

    public GeneratorState nextState() {
        GeneratorState next = new GeneratorState(nextBranchId++, new ListBuffer<>());
        states.add(next);
        return next;
    }

    public JCStatement createNextStatement(TreeMaker treeMaker, Names names) {
        ListBuffer<JCCase> peekCases = new ListBuffer<>();

        peekCases.add(treeMaker.Case(CaseKind.STATEMENT,
                List.of(treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_FINISH)),
                List.of(treeMaker.Return(treeMaker.Literal(TypeTag.BOT, null))), null));

        for (GeneratorState state : states) {
            peekCases.add(treeMaker.Case(CaseKind.STATEMENT,
                    List.of(treeMaker.Literal(TypeTag.INT, state.id)),
                    state.statements.toList(),
                    null));
        }

        peekCases.add(treeMaker.Case(
                CaseKind.STATEMENT,
                List.of(treeMaker.DefaultCaseLabel()),
                List.of(treeMaker.Throw(treeMaker.NewClass(
                        null,
                        List.nil(),
                        TreeMakerUtil.createClassName(treeMaker, names, "java", "lang", "RuntimeException"),
                        List.of(treeMaker.Literal(Constants.ERR_UNREACHABLE)),
                        null))),
                null));

        return treeMaker.WhileLoop(treeMaker.Literal(TypeTag.BOOLEAN, 1), treeMaker.Try(
                treeMaker.Block(0,
                        List.of(treeMaker.Switch(treeMaker.Ident(stateField.name), peekCases.toList()))),
                List.of(createCatch(treeMaker, names)),
                null));
    }

    private JCCatch createCatch(TreeMaker treeMaker, Names names) {
        JCVariableDecl throwableDecl = treeMaker.VarDef(
                treeMaker.Modifiers(0),
                names.fromString("t"),
                TreeMakerUtil.createClassName(treeMaker, names, "java", "lang",
                        "Throwable"),
                null);

        ListBuffer<JCStatement> catchBuf = new ListBuffer<>();

        JCLiteral finishLiteral = treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_FINISH);

        catchBuf.add(treeMaker.If(
                treeMaker.Binary(Tag.NE, treeMaker.Ident(catchStateField.name), finishLiteral),
                treeMaker.Block(0, List.of(
                        treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(stateField.name),
                                treeMaker.Ident(catchStateField.name))),
                        treeMaker.Break(null))),
                null));

        catchBuf.add(treeMaker.Exec(treeMaker.Assign(
                treeMaker.Ident(stateField.name),
                treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_FINISH))));
        catchBuf.add(treeMaker.Throw(treeMaker.Ident(throwableDecl.name)));

        return treeMaker.Catch(throwableDecl, treeMaker.Block(0, catchBuf.toList()));
    }
}
